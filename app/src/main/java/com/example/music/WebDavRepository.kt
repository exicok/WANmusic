package com.example.music

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Xml
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

data class WebDavConfig(val url: String, val username: String, val password: String)

object WebDavRepository {
    private const val PREFS = "music_prefs"

    fun load(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let {
        WebDavConfig(
            it.getString("webdav_url", "") ?: "",
            it.getString("webdav_username", "") ?: "",
            it.getString("webdav_password", "") ?: ""
        )
    }

    fun save(context: Context, config: WebDavConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("webdav_url", config.url.trim())
            .putString("webdav_username", config.username)
            .putString("webdav_password", config.password)
            .apply()
    }

    fun authorization(config: WebDavConfig): String? = if (config.username.isBlank()) null else {
        "Basic " + Base64.encodeToString(
            "${config.username}:${config.password}".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
    }

    fun scan(config: WebDavConfig): List<Song> {
        require(config.url.startsWith("http://") || config.url.startsWith("https://")) {
            "WebDAV 地址必须以 http:// 或 https:// 开头"
        }
        val connection = URL(config.url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "PROPFIND"
            connection.setRequestProperty("Depth", "1")
            connection.setRequestProperty("Content-Type", "application/xml; charset=utf-8")
            authorization(config)?.let { connection.setRequestProperty("Authorization", it) }
            connection.doOutput = true
            connection.outputStream.use {
                it.write("<?xml version=\"1.0\"?><propfind xmlns=\"DAV:\"><prop><displayname/><getcontentlength/><resourcetype/></prop></propfind>".toByteArray())
            }
            if (connection.responseCode !in 200..299) {
                error("服务器返回 HTTP ${connection.responseCode}")
            }
            parseMultiStatus(connection.inputStream, config.url)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseMultiStatus(input: java.io.InputStream, baseUrl: String): List<Song> {
        val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
        val songs = mutableListOf<Song>()
        var href: String? = null
        var displayName: String? = null
        var length = 0L
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "response" -> { href = null; displayName = null; length = 0L }
                    "href" -> href = parser.nextText()
                    "displayname" -> displayName = parser.nextText()
                    "getcontentlength" -> length = parser.nextText().toLongOrNull() ?: 0L
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> if (parser.name.equals("response", true)) {
                    val path = href
                    val decodedName = displayName?.takeIf { it.isNotBlank() }
                        ?: path?.substringAfterLast('/')?.let { URLDecoder.decode(it, "UTF-8") }
                    if (path != null && decodedName != null && isMusicFile(decodedName)) {
                        val remoteUrl = URL(URL(baseUrl), path).toString()
                        songs += Song(decodedName.substringBeforeLast('.'), "WebDAV", formatFileSize(length), Uri.parse(remoteUrl))
                    }
                }
            }
            event = parser.next()
        }
        return songs.distinctBy { it.uri }
    }
}
