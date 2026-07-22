package com.example.music.lyrics

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.InputStream
import java.nio.charset.Charset

private const val TAG = "EmbeddedLyrics"

data class LyricsResult(
    val plainText: String? = null,
    val synced: List<Pair<Long, String>> = emptyList()
)

/**
 * 直接读取音频文件内嵌歌词，绕过 MediaMetadataRetriever 的限制。
 *
 * 支持：
 *  - MP3：ID3v2.2 (ULT/SLT) 与 v2.3/v2.4 (USLT/SYLT) 帧，含同步时间戳
 *  - M4A/MP4：iTunes "©lyr" 原子（plain text 与 metadata 两种）
 *  - FLAC：Vorbis Comment 中的 LYRICS / UNSYNCEDLYRICS 字段
 *  - OGG/Opus：Vorbis Comment 中的 LYRICS 字段
 */
object EmbeddedLyricsReader {

    fun read(context: Context, uri: Uri): LyricsResult {
        val name = uri.lastPathSegment?.lowercase().orEmpty()
        val ext = name.substringAfterLast('.', "")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                when (ext) {
                    "mp3" -> parseId3v2(input)
                    "flac" -> parseFlac(input)
                    "ogg", "oga", "opus" -> parseOgg(input)
                    "m4a", "mp4", "aac" -> parseMp4(input)
                    else -> LyricsResult()
                }
            } ?: LyricsResult()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read embedded lyrics: ${e.message}")
            LyricsResult()
        }
    }

    // ===================== ID3v2 (MP3) =====================

    private fun parseId3v2(input: InputStream): LyricsResult {
        val header = readBytes(input, 10)
        if (header.size < 10) return LyricsResult()
        if (header[0] != 'I'.code.toByte() ||
            header[1] != 'D'.code.toByte() ||
            header[2] != '3'.code.toByte()
        ) return LyricsResult()

        val version = header[3].toInt() and 0xFF
        val revision = header[4].toInt() and 0xFF
        val flags = header[5].toInt() and 0xFF
        val tagSize = readSyncsafeInt(header, 6)
        if (tagSize <= 0) return LyricsResult()

        val tagData = readBytes(input, tagSize)
        if (tagData.isEmpty()) return LyricsResult()

        var offset = 0
        // 跳过扩展头
        if (flags and 0x40 != 0 && tagData.size >= 4) {
            val extSize = if (version >= 4) {
                readSyncsafeInt(tagData, 0)
            } else {
                readBeInt(tagData, 0)
            }
            offset += extSize
        }

        val usltId = if (version == 2) "ULT" else "USLT"
        val syltId = if (version == 2) "SLT" else "SYLT"
        val frameIdLen = if (version == 2) 3 else 4

        var plainLyrics: String? = null
        var syncedLyrics: List<Pair<Long, String>> = emptyList()

        while (offset + frameIdLen + 4 <= tagData.size) {
            val id = String(tagData, offset, frameIdLen, Charsets.ISO_8859_1)
            // 帧 ID 首字节为 0 时表示填充区
            if (id[0] == '\u0000') break

            val frameSize = if (version >= 4) {
                readSyncsafeInt(tagData, offset + frameIdLen)
            } else {
                readBeInt(tagData, offset + frameIdLen)
            }
            // flags 占 2 字节
            val dataStart = offset + frameIdLen + 4 + 2
            if (frameSize <= 0 || dataStart + frameSize > tagData.size) break

            when (id) {
                usltId -> {
                    val text = parseUslt(tagData, dataStart, frameSize)
                    if (!text.isNullOrBlank() && plainLyrics == null) {
                        plainLyrics = text
                    }
                }
                syltId -> {
                    val synced = parseSylt(tagData, dataStart, frameSize)
                    if (synced.isNotEmpty() && syncedLyrics.isEmpty()) {
                        syncedLyrics = synced
                    }
                }
            }
            offset = dataStart + frameSize
        }

        return LyricsResult(plainLyrics, syncedLyrics)
    }

    /** 解析 USLT 帧：encoding(1) + language(3) + descriptor(以 NUL 结尾) + lyrics */
    private fun parseUslt(data: ByteArray, offset: Int, size: Int): String? {
        if (size < 4) return null
        val encoding = data[offset].toInt() and 0xFF
        val bodyStart = offset + 4
        val bodyLen = size - 4
        // 跳过 content descriptor
        val (descriptorLen, _) = skipId3String(data, bodyStart, bodyLen, encoding)
        val textStart = bodyStart + descriptorLen
        val textLen = bodyLen - descriptorLen
        if (textLen <= 0) return null
        val (text, _) = readId3String(data, textStart, textLen, encoding, nullTerminated = false)
        return text?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** 解析 SYLT 帧：encoding(1) + language(3) + timestamp_format(1) + content_type(1) + descriptor + (text + NUL + 4-byte timestamp) ... */
    private fun parseSylt(data: ByteArray, offset: Int, size: Int): List<Pair<Long, String>> {
        if (size < 6) return emptyList()
        val encoding = data[offset].toInt() and 0xFF
        val tsFormat = data[offset + 4].toInt() and 0xFF
        val headerStart = offset + 6
        val headerLen = size - 6
        val (descriptorLen, _) = skipId3String(data, headerStart, headerLen, encoding)
        var pos = headerStart + descriptorLen
        val end = offset + size
        val result = mutableListOf<Pair<Long, String>>()

        while (pos + 6 < end) {
            val remaining = end - pos
            val (text, textBytes) = readId3String(data, pos, remaining - 4, encoding, nullTerminated = true)
            if (text == null || textBytes <= 0) break
            pos += textBytes
            if (pos + 4 > end) break
            val ts = readBeUInt(data, pos)
            pos += 4
            val ms = when (tsFormat) {
                1 -> ts * 1000L / 44100L  // 近似按 44.1kHz 估算 MPEG 帧
                else -> ts                // 毫秒
            }
            if (text.isNotBlank()) {
                result.add(ms to text)
            }
        }
        return result
    }

    // ===================== M4A/MP4 =====================

    private fun parseMp4(input: InputStream): LyricsResult {
        // moov.udta 可能在文件末尾；扫描前 2MB + 末尾 2MB
        val headSize = 2 * 1024 * 1024
        val tailSize = 2 * 1024 * 1024

        val head = readBytes(input, headSize)
        val totalSize = estimateStreamSize(input, head)
        if (totalSize > headSize + tailSize) {
            val skip = totalSize - tailSize - head.size
            if (skip > 0) skipBytes(input, skip)
        }
        val tail = readBytes(input, tailSize)

        // 在头部与尾部数据中查找 ©lyr 原子
        val plainFromHead = scanMp4ForLyricsAtom(head)
        val plainFromTail = if (plainFromHead == null) scanMp4ForLyricsAtom(tail) else null
        return LyricsResult(plainFromHead ?: plainFromTail, emptyList())
    }

    private fun scanMp4ForLyricsAtom(data: ByteArray): String? {
        if (data.size < 8) return null
        var i = 0
        while (i + 8 <= data.size) {
            val size = readBeUInt(data, i)
            val type = String(data, i + 4, 4, Charsets.US_ASCII)
            if (size < 8) {
                // 扩展大小 (size == 1) 跳过
                if (size == 1L && i + 16 <= data.size) {
                    val extSize = readLongBe(data, i + 8)
                    val headerLen = 16
                    if (type == "©lyr" || type == "lyr ") {
                        val start = i + headerLen
                        val end = (i + extSize).coerceAtMost(data.size.toLong()).toInt()
                        return decodeMp4LyricsPayload(data, start, end - start)
                    }
                    if (extSize <= 0) break
                    i += extSize.toInt()
                    continue
                }
                break
            }
            if (type == "©lyr" || type == "lyr ") {
                val start = i + 8
                val end = (i + size).toInt().coerceAtMost(data.size)
                return decodeMp4LyricsPayload(data, start, end - start)
            }
            // mdta 形式 key=value 命名空间
            if (type == "keys" || type == "ilst" || type == "----") {
                // 暂不深入解析自定义命名空间
            }
            i += size.toInt()
        }
        return null
    }

    /**
     * ©lyr 原子负载：可能直接是 UTF-8 文本，也可能是 metadata 容器（data 原子）。
     */
    private fun decodeMp4LyricsPayload(data: ByteArray, offset: Int, length: Int): String? {
        if (length <= 0) return null
        // 直接 UTF-8
        val direct = String(data, offset, length, Charsets.UTF_8).trim()
        if (direct.isNotEmpty() && !direct.startsWith("\u0000")) {
            // 简单判断：是否包含 data 原子头部
            if (!looksLikeAtomHeader(direct) && direct.first().isLetterOrDigit() || direct.contains('\n')) {
                return direct.takeIf { it.isNotBlank() }
            }
        }
        // 解析 data 原子
        return parseMp4DataAtom(data, offset, length)
    }

    private fun parseMp4DataAtom(data: ByteArray, offset: Int, length: Int): String? {
        if (length < 16) return null
        // data atom: size(4) + "data"(4) + type(4) + locale(4) + payload
        if (offset + 16 > data.size) return null
        val type = String(data, offset + 4, 4, Charsets.US_ASCII)
        if (type != "data") return null
        val payloadStart = offset + 16
        val payloadLen = length - 16
        if (payloadStart + payloadLen > data.size || payloadLen <= 0) return null
        return String(data, payloadStart, payloadLen, Charsets.UTF_8).trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun looksLikeAtomHeader(s: String): Boolean {
        if (s.length < 8) return false
        val first4 = s.substring(0, 4)
        return first4.all { it.isDigit() || it == ' ' }
    }

    // ===================== FLAC =====================

    private fun parseFlac(input: InputStream): LyricsResult {
        val magic = readBytes(input, 4)
        if (String(magic) != "fLaC") return LyricsResult()

        var plainLyrics: String? = null
        while (true) {
            val blockHeader = readBytes(input, 4)
            if (blockHeader.size < 4) break
            val isLast = (blockHeader[0].toInt() and 0x80) != 0
            val blockType = blockHeader[0].toInt() and 0x7F
            val blockSize = ((blockHeader[1].toInt() and 0xFF) shl 16) or
                    ((blockHeader[2].toInt() and 0xFF) shl 8) or
                    (blockHeader[3].toInt() and 0xFF)
            val data = readBytes(input, blockSize)
            if (blockType == 4) { // VORBIS_COMMENT
                val comments = parseVorbisComments(data)
                plainLyrics = comments["LYRICS"] ?: comments["UNSYNCEDLYRICS"]
            }
            if (isLast) break
        }
        return LyricsResult(plainLyrics, emptyList())
    }

    private fun parseVorbisComments(data: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (data.size < 8) return result
        val vendorLen = readLeInt(data, 0)
        var pos = 4 + vendorLen
        if (pos + 4 > data.size) return result
        val numComments = readLeInt(data, pos)
        pos += 4
        repeat(numComments) {
            if (pos + 4 > data.size) return result
            val len = readLeInt(data, pos)
            pos += 4
            if (pos + len > data.size) return result
            val comment = String(data, pos, len, Charsets.UTF_8)
            pos += len
            val eq = comment.indexOf('=')
            if (eq > 0) {
                result[comment.substring(0, eq).uppercase()] = comment.substring(eq + 1)
            }
        }
        return result
    }

    // ===================== OGG/Opus =====================

    private fun parseOgg(input: InputStream): LyricsResult {
        // 直接扫描前 256KB 中的 Vorbis Comment 文本
        val buf = readBytes(input, 256 * 1024)
        val text = String(buf, Charsets.ISO_8859_1)
        val match = Regex("LYRICS=([^\u0000]+)").find(text)
            ?: Regex("UNSYNCEDLYRICS=([^\u0000]+)").find(text)
        val value = match?.groupValues?.getOrNull(1)?.trim()
        return LyricsResult(value?.takeIf { it.isNotEmpty() }, emptyList())
    }

    // ===================== 工具方法 =====================

    private fun readBytes(input: InputStream, count: Int): ByteArray {
        if (count <= 0) return ByteArray(0)
        val buf = ByteArray(count)
        var total = 0
        while (total < count) {
            val r = input.read(buf, total, count - total)
            if (r <= 0) break
            total += r
        }
        return if (total == count) buf else buf.copyOf(total)
    }

    private fun skipBytes(input: InputStream, count: Long) {
        var remaining = count
        val skipBuf = ByteArray(8192)
        while (remaining > 0) {
            val toRead = minOf(remaining, skipBuf.size.toLong()).toInt()
            val r = input.read(skipBuf, 0, toRead)
            if (r <= 0) break
            remaining -= r
        }
    }

    private fun estimateStreamSize(input: InputStream, head: ByteArray): Long {
        // 对 SAF 输入流，available() 不可靠；退化为读取一个尾部标记
        // 简单策略：若 head 已读够，返回 head.size；否则假设流至少为 head 大小
        return head.size.toLong()
    }

    private fun readSyncsafeInt(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return ((data[offset].toInt() and 0x7F) shl 21) or
                ((data[offset + 1].toInt() and 0x7F) shl 14) or
                ((data[offset + 2].toInt() and 0x7F) shl 7) or
                (data[offset + 3].toInt() and 0x7F)
    }

    private fun readBeInt(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }

    private fun readBeUInt(data: ByteArray, offset: Int): Long {
        if (offset + 4 > data.size) return 0L
        return ((data[offset].toInt() and 0xFF).toLong() shl 24) or
                ((data[offset + 1].toInt() and 0xFF).toLong() shl 16) or
                ((data[offset + 2].toInt() and 0xFF).toLong() shl 8) or
                (data[offset + 3].toInt() and 0xFF).toLong()
    }

    private fun readLongBe(data: ByteArray, offset: Int): Long {
        if (offset + 8 > data.size) return 0L
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (data[offset + i].toInt() and 0xFF).toLong()
        }
        return result
    }

    private fun readLeInt(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return 0
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    /** 跳过以指定编码结尾于 NUL 的字符串，返回跳过的字节数与剩余可读区间。 */
    private fun skipId3String(
        data: ByteArray,
        offset: Int,
        maxLength: Int,
        encoding: Int
    ): Pair<Int, Int> {
        val (str, bytes) = readId3String(data, offset, maxLength, encoding, nullTerminated = true)
        return bytes to (if (str != null) bytes else 0)
    }

    /**
     * 读取 ID3 字符串并返回 (字符串, 实际消费的字节数)。
     * @param nullTerminated true 时遇到终止符就停止；false 时按 maxLength 读取全部。
     */
    private fun readId3String(
        data: ByteArray,
        offset: Int,
        maxLength: Int,
        encoding: Int,
        nullTerminated: Boolean
    ): Pair<String?, Int> {
        if (maxLength <= 0 || offset >= data.size) return null to 0
        val available = (data.size - offset).coerceAtMost(maxLength)

        return when (encoding) {
            0 -> readEncoded(data, offset, available, Charsets.ISO_8859_1, 1, nullTerminated)
            1 -> {
                // UTF-16 with BOM
                if (available >= 2) {
                    val bom = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
                    if (bom == 0xFEFF || bom == 0xFFFE) {
                        val charset = if (bom == 0xFEFF) Charsets.UTF_16BE else Charsets.UTF_16LE
                        // 跳过 BOM
                        val (str, len) = readEncoded(data, offset + 2, available - 2, charset, 2, nullTerminated)
                        return str to (len + 2)
                    }
                }
                readEncoded(data, offset, available, Charsets.UTF_16BE, 2, nullTerminated)
            }
            2 -> readEncoded(data, offset, available, Charsets.UTF_16BE, 2, nullTerminated)
            3 -> readEncoded(data, offset, available, Charsets.UTF_8, 1, nullTerminated)
            else -> readEncoded(data, offset, available, Charsets.ISO_8859_1, 1, nullTerminated)
        }
    }

    private fun readEncoded(
        data: ByteArray,
        offset: Int,
        available: Int,
        charset: Charset,
        charSize: Int,
        nullTerminated: Boolean
    ): Pair<String?, Int> {
        if (available <= 0) return null to 0
        if (!nullTerminated) {
            val raw = String(data, offset, available, charset)
            return raw to available
        }
        val limit = available - (available % charSize)
        var i = 0
        while (i < limit) {
            val isTerm = when (charSize) {
                1 -> data[offset + i] == 0.toByte()
                2 -> data[offset + i] == 0.toByte() && data[offset + i + 1] == 0.toByte()
                else -> false
            }
            if (isTerm) {
                val str = String(data, offset, i, charset)
                return str to (i + charSize)
            }
            i += charSize
        }
        // 没有终止符：读取全部
        val str = String(data, offset, limit, charset)
        return str to limit
    }
}
