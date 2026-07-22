extends Control

const BG := Color("101318")
const PANEL := Color(0.10, 0.12, 0.15, 0.84)
const GLASS := Color(0.16, 0.18, 0.22, 0.68)
const GLASS_HOVER := Color(0.21, 0.24, 0.28, 0.82)
const TEXT := Color("f4f6f8")
const MUTED := Color("99a2ad")
const TEAL := Color("52e0c4")
const CORAL := Color("ff6d72")
const AMBER := Color("f6bd60")

var tracks := [
	{"title":"Midnight Bloom", "artist":"Luna Vale", "album":"Afterglow", "time":"4:02", "color":Color("6c63ff"), "accent":Color("52e0c4")},
	{"title":"Soft Focus", "artist":"The Marías", "album":"Cinema", "time":"3:41", "color":Color("ef476f"), "accent":Color("ffc857")},
	{"title":"Still Water", "artist":"Odesza", "album":"The Last Goodbye", "time":"3:28", "color":Color("118ab2"), "accent":Color("90e0ef")},
	{"title":"Porcelain Sky", "artist":"Mira Nocturne", "album":"Blue Hour", "time":"4:17", "color":Color("ff8c42"), "accent":Color("ffd166")},
	{"title":"Slow Motion", "artist":"Men I Trust", "album":"Untourable Album", "time":"3:52", "color":Color("5f6f52"), "accent":Color("e7b10a")},
	{"title":"Lover's Code", "artist":"Chromatics", "album":"Night Drive", "time":"4:36", "color":Color("8a4fff"), "accent":Color("ff4f81")}
]

var current_track := 0
var playing := true
var liked := true
var elapsed := 127.0
var duration := 242.0
var nav_buttons: Array[Button] = []
var track_rows: Array[Button] = []
var search_edit: LineEdit
var content_title: Label
var content_subtitle: Label
var track_list: VBoxContainer
var current_art: AlbumArt
var title_label: Label
var artist_label: Label
var play_button: Button
var like_button: Button
var progress_slider: HSlider
var elapsed_label: Label
var duration_label: Label
var sidebar: PanelContainer
var queue_panel: PanelContainer
var hero: PanelContainer
var status_label: Label
var nav_index := 0

func _ready() -> void:
	set_process(true)
	build_ui()
	resized.connect(_on_resized)
	_on_resized()
	if "--capture" in OS.get_cmdline_user_args():
		capture_preview.call_deferred()

func capture_preview() -> void:
	await get_tree().process_frame
	await get_tree().process_frame
	get_viewport().get_texture().get_image().save_png("res://preview.png")
	get_tree().quit()

func glass_style(color := GLASS, radius := 18, border := Color(1, 1, 1, 0.12)) -> StyleBoxFlat:
	var s := StyleBoxFlat.new()
	s.bg_color = color
	s.border_color = border
	s.set_border_width_all(1)
	s.corner_radius_top_left = radius
	s.corner_radius_top_right = radius
	s.corner_radius_bottom_left = radius
	s.corner_radius_bottom_right = radius
	s.shadow_color = Color(0, 0, 0, 0.25)
	s.shadow_size = 14
	s.shadow_offset = Vector2(0, 7)
	s.content_margin_left = 16
	s.content_margin_right = 16
	s.content_margin_top = 14
	s.content_margin_bottom = 14
	return s

func button_style(color: Color, radius := 12) -> StyleBoxFlat:
	var s := StyleBoxFlat.new()
	s.bg_color = color
	s.corner_radius_top_left = radius
	s.corner_radius_top_right = radius
	s.corner_radius_bottom_left = radius
	s.corner_radius_bottom_right = radius
	s.content_margin_left = 13
	s.content_margin_right = 13
	s.content_margin_top = 9
	s.content_margin_bottom = 9
	return s

func make_button(text_value: String, tooltip := "", flat := true) -> Button:
	var b := Button.new()
	b.text = text_value
	b.tooltip_text = tooltip
	b.focus_mode = Control.FOCUS_NONE
	b.add_theme_color_override("font_color", TEXT)
	b.add_theme_color_override("font_hover_color", TEXT)
	b.add_theme_color_override("font_pressed_color", TEXT)
	b.add_theme_stylebox_override("normal", button_style(Color(1,1,1,0.0) if flat else Color(1,1,1,0.09)))
	b.add_theme_stylebox_override("hover", button_style(Color(1,1,1,0.10)))
	b.add_theme_stylebox_override("pressed", button_style(Color(1,1,1,0.16)))
	return b

func make_label(text_value: String, size := 14, color := TEXT) -> Label:
	var l := Label.new()
	l.text = text_value
	l.add_theme_font_size_override("font_size", size)
	l.add_theme_color_override("font_color", color)
	l.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	return l

func build_ui() -> void:
	add_child(AmbientBackground.new())
	var page := VBoxContainer.new()
	page.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	page.add_theme_constant_override("separation", 0)
	add_child(page)
	page.add_child(build_topbar())
	var body_margin := MarginContainer.new()
	body_margin.size_flags_vertical = Control.SIZE_EXPAND_FILL
	body_margin.add_theme_constant_override("margin_left", 22)
	body_margin.add_theme_constant_override("margin_right", 22)
	body_margin.add_theme_constant_override("margin_top", 12)
	body_margin.add_theme_constant_override("margin_bottom", 16)
	page.add_child(body_margin)
	var body := HBoxContainer.new()
	body.add_theme_constant_override("separation", 16)
	body_margin.add_child(body)
	sidebar = build_sidebar()
	body.add_child(sidebar)
	var main := build_main_content()
	main.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	body.add_child(main)
	queue_panel = build_queue()
	body.add_child(queue_panel)
	page.add_child(build_player())

func build_topbar() -> Control:
	var margin := MarginContainer.new()
	margin.custom_minimum_size.y = 76
	margin.add_theme_constant_override("margin_left", 28)
	margin.add_theme_constant_override("margin_right", 28)
	margin.add_theme_constant_override("margin_top", 14)
	margin.add_theme_constant_override("margin_bottom", 8)
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 16)
	margin.add_child(row)
	var brand := HBoxContainer.new()
	brand.custom_minimum_size.x = 214
	brand.add_theme_constant_override("separation", 10)
	row.add_child(brand)
	var mark := Label.new()
	mark.text = "◉"
	mark.add_theme_font_size_override("font_size", 25)
	mark.add_theme_color_override("font_color", TEAL)
	brand.add_child(mark)
	var name := make_label("AURALIS", 17, TEXT)
	name.add_theme_constant_override("outline_size", 1)
	brand.add_child(name)
	var search_panel := PanelContainer.new()
	search_panel.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	search_panel.custom_minimum_size.y = 46
	search_panel.add_theme_stylebox_override("panel", glass_style(Color(0.12,0.14,0.17,0.78), 16))
	row.add_child(search_panel)
	var search_row := HBoxContainer.new()
	search_row.add_theme_constant_override("separation", 10)
	search_panel.add_child(search_row)
	search_row.add_child(make_label("⌕", 24, MUTED))
	search_edit = LineEdit.new()
	search_edit.placeholder_text = "搜索歌曲、艺人或专辑"
	search_edit.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	search_edit.add_theme_font_size_override("font_size", 14)
	search_edit.add_theme_color_override("font_color", TEXT)
	search_edit.add_theme_color_override("font_placeholder_color", MUTED)
	search_edit.add_theme_stylebox_override("normal", StyleBoxEmpty.new())
	search_edit.add_theme_stylebox_override("focus", StyleBoxEmpty.new())
	search_edit.text_changed.connect(_on_search)
	search_row.add_child(search_edit)
	var profile := make_button("林  ⌄", "账户菜单", false)
	profile.custom_minimum_size.x = 96
	profile.pressed.connect(func(): toast("个人资料与设置"))
	row.add_child(profile)
	return margin

func build_sidebar() -> PanelContainer:
	var panel := PanelContainer.new()
	panel.custom_minimum_size.x = 220
	panel.add_theme_stylebox_override("panel", glass_style(PANEL, 20))
	var box := VBoxContainer.new()
	box.add_theme_constant_override("separation", 5)
	panel.add_child(box)
	var entries := [["⌂", "为你推荐"], ["◈", "探索"], ["◉", "电台"], ["▤", "最近播放"], ["♡", "已收藏"], ["▥", "专辑"], ["♬", "艺人"]]
	for i in entries.size():
		if i == 3:
			box.add_child(make_label("音乐资料库", 11, MUTED))
		var b := make_button(entries[i][0] + "   " + entries[i][1])
		b.alignment = HORIZONTAL_ALIGNMENT_LEFT
		b.custom_minimum_size.y = 42
		b.add_theme_font_size_override("font_size", 14)
		b.pressed.connect(_navigate.bind(i, entries[i][1]))
		nav_buttons.append(b)
		box.add_child(b)
	var spacer := Control.new()
	spacer.size_flags_vertical = Control.SIZE_EXPAND_FILL
	box.add_child(spacer)
	select_nav(0)
	return panel

func build_main_content() -> Control:
	var root := VBoxContainer.new()
	root.add_theme_constant_override("separation", 14)
	hero = PanelContainer.new()
	hero.custom_minimum_size.y = 250
	hero.add_theme_stylebox_override("panel", glass_style(Color(0.10,0.12,0.15,0.74), 22))
	root.add_child(hero)
	var hero_row := HBoxContainer.new()
	hero_row.add_theme_constant_override("separation", 24)
	hero.add_child(hero_row)
	var hero_copy := VBoxContainer.new()
	hero_copy.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	hero_copy.add_theme_constant_override("separation", 8)
	hero_row.add_child(hero_copy)
	var eyebrow := make_label("为你精选  ·  星期四", 12, TEAL)
	hero_copy.add_child(eyebrow)
	content_title = make_label("让夜晚慢下来", 34, TEXT)
	content_title.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	hero_copy.add_child(content_title)
	content_subtitle = make_label("柔和电子、梦幻流行与一些恰到好处的安静。", 14, MUTED)
	content_subtitle.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	hero_copy.add_child(content_subtitle)
	var hero_spacer := Control.new()
	hero_spacer.size_flags_vertical = Control.SIZE_EXPAND_FILL
	hero_copy.add_child(hero_spacer)
	var actions := HBoxContainer.new()
	actions.add_theme_constant_override("separation", 10)
	hero_copy.add_child(actions)
	var listen := make_button("▶  开始聆听", "播放全部歌曲", false)
	listen.add_theme_stylebox_override("normal", button_style(TEAL, 16))
	listen.add_theme_stylebox_override("hover", button_style(TEAL.lightened(0.1), 16))
	listen.add_theme_color_override("font_color", Color("071713"))
	listen.pressed.connect(func(): set_track(0); set_playing(true))
	actions.add_child(listen)
	var more := make_button("•••", "更多选项", false)
	more.pressed.connect(func(): toast("已打开更多选项"))
	actions.add_child(more)
	var hero_art := AlbumArt.new()
	hero_art.custom_minimum_size = Vector2(210, 210)
	hero_art.primary = Color("6c63ff")
	hero_art.accent = TEAL
	hero_row.add_child(hero_art)
	var list_header := HBoxContainer.new()
	root.add_child(list_header)
	list_header.add_child(make_label("最近常听", 21, TEXT))
	var hs := Control.new()
	hs.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	list_header.add_child(hs)
	var all := make_button("查看全部  ›")
	all.add_theme_color_override("font_color", TEAL)
	all.pressed.connect(func(): toast("已显示全部歌曲"))
	list_header.add_child(all)
	var scroll := ScrollContainer.new()
	scroll.size_flags_vertical = Control.SIZE_EXPAND_FILL
	scroll.horizontal_scroll_mode = ScrollContainer.SCROLL_MODE_DISABLED
	root.add_child(scroll)
	track_list = VBoxContainer.new()
	track_list.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	track_list.add_theme_constant_override("separation", 5)
	scroll.add_child(track_list)
	for i in tracks.size(): add_track_row(i)
	return root

func add_track_row(index: int) -> void:
	var t: Dictionary = tracks[index]
	var row := Button.new()
	row.focus_mode = Control.FOCUS_NONE
	row.custom_minimum_size.y = 66
	row.add_theme_stylebox_override("normal", button_style(Color(1,1,1,0.025), 14))
	row.add_theme_stylebox_override("hover", button_style(Color(1,1,1,0.085), 14))
	row.add_theme_stylebox_override("pressed", button_style(Color(1,1,1,0.13), 14))
	row.pressed.connect(func(): set_track(index); set_playing(true))
	track_list.add_child(row)
	track_rows.append(row)
	var h := HBoxContainer.new()
	h.mouse_filter = Control.MOUSE_FILTER_IGNORE
	h.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT, Control.PRESET_MODE_MINSIZE, 10)
	h.add_theme_constant_override("separation", 12)
	row.add_child(h)
	var n := make_label(str(index + 1).pad_zeros(2), 12, MUTED)
	n.custom_minimum_size.x = 24
	h.add_child(n)
	var art := AlbumArt.new()
	art.custom_minimum_size = Vector2(44,44)
	art.primary = t.color
	art.accent = t.accent
	h.add_child(art)
	var copy := VBoxContainer.new()
	copy.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	h.add_child(copy)
	copy.add_child(make_label(t.title, 14, TEXT))
	copy.add_child(make_label(t.artist, 12, MUTED))
	var album := make_label(t.album, 12, MUTED)
	album.custom_minimum_size.x = 150
	h.add_child(album)
	var time := make_label(t.time, 12, MUTED)
	time.custom_minimum_size.x = 40
	h.add_child(time)

func build_queue() -> PanelContainer:
	var panel := PanelContainer.new()
	panel.custom_minimum_size.x = 270
	panel.add_theme_stylebox_override("panel", glass_style(PANEL, 20))
	var box := VBoxContainer.new()
	box.add_theme_constant_override("separation", 12)
	panel.add_child(box)
	var head := HBoxContainer.new()
	box.add_child(head)
	head.add_child(make_label("接下来播放", 17, TEXT))
	var sp := Control.new(); sp.size_flags_horizontal = Control.SIZE_EXPAND_FILL; head.add_child(sp)
	var clear := make_button("清除")
	clear.add_theme_color_override("font_color", MUTED)
	clear.pressed.connect(func(): toast("播放队列已清除"))
	head.add_child(clear)
	var wave := Waveform.new()
	wave.custom_minimum_size.y = 72
	box.add_child(wave)
	box.add_child(make_label("正在播放", 11, TEAL))
	for i in [0, 1, 2, 3]:
		var t: Dictionary = tracks[i]
		var b := make_button(t.title + "\n" + t.artist)
		b.alignment = HORIZONTAL_ALIGNMENT_LEFT
		b.custom_minimum_size.y = 56
		b.add_theme_font_size_override("font_size", 13)
		b.pressed.connect(func(): set_track(i); set_playing(true))
		box.add_child(b)
	var sp2 := Control.new(); sp2.size_flags_vertical = Control.SIZE_EXPAND_FILL; box.add_child(sp2)
	status_label = make_label("高解析度无损  ·  24-bit", 11, MUTED)
	status_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	box.add_child(status_label)
	return panel

func build_player() -> Control:
	var outer := MarginContainer.new()
	outer.custom_minimum_size.y = 120
	outer.add_theme_constant_override("margin_left", 22)
	outer.add_theme_constant_override("margin_right", 22)
	outer.add_theme_constant_override("margin_bottom", 18)
	var panel := PanelContainer.new()
	panel.add_theme_stylebox_override("panel", glass_style(Color(0.10,0.12,0.15,0.94), 22, Color(1,1,1,0.16)))
	outer.add_child(panel)
	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 14)
	panel.add_child(row)
	current_art = AlbumArt.new()
	current_art.custom_minimum_size = Vector2(62,62)
	row.add_child(current_art)
	var copy := VBoxContainer.new()
	copy.custom_minimum_size.x = 185
	copy.alignment = BoxContainer.ALIGNMENT_CENTER
	row.add_child(copy)
	title_label = make_label("Midnight Bloom", 15, TEXT)
	artist_label = make_label("Luna Vale", 12, MUTED)
	copy.add_child(title_label); copy.add_child(artist_label)
	like_button = make_button("♥", "取消收藏")
	like_button.add_theme_font_size_override("font_size", 20)
	like_button.add_theme_color_override("font_color", CORAL)
	like_button.pressed.connect(_toggle_like)
	row.add_child(like_button)
	var center := VBoxContainer.new()
	center.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	center.add_theme_constant_override("separation", 4)
	row.add_child(center)
	var controls := HBoxContainer.new()
	controls.alignment = BoxContainer.ALIGNMENT_CENTER
	controls.add_theme_constant_override("separation", 8)
	center.add_child(controls)
	var shuffle := make_button("⌘", "随机播放")
	shuffle.pressed.connect(func(): toast("随机播放已开启"))
	controls.add_child(shuffle)
	var prev := make_button("◀", "上一首")
	prev.pressed.connect(_previous)
	controls.add_child(prev)
	play_button = make_button("Ⅱ", "暂停", false)
	play_button.custom_minimum_size = Vector2(50,50)
	play_button.add_theme_font_size_override("font_size", 18)
	play_button.add_theme_stylebox_override("normal", button_style(TEXT, 25))
	play_button.add_theme_stylebox_override("hover", button_style(TEXT.darkened(0.08), 25))
	play_button.add_theme_color_override("font_color", BG)
	play_button.pressed.connect(func(): set_playing(not playing))
	controls.add_child(play_button)
	var next := make_button("▶", "下一首")
	next.pressed.connect(_next)
	controls.add_child(next)
	var repeat := make_button("↻", "循环播放")
	repeat.pressed.connect(func(): toast("单曲循环已开启"))
	controls.add_child(repeat)
	var timeline := HBoxContainer.new()
	timeline.add_theme_constant_override("separation", 10)
	center.add_child(timeline)
	elapsed_label = make_label("2:07", 11, MUTED)
	duration_label = make_label("4:02", 11, MUTED)
	timeline.add_child(elapsed_label)
	progress_slider = HSlider.new()
	progress_slider.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	progress_slider.max_value = duration
	progress_slider.value = elapsed
	progress_slider.custom_minimum_size.y = 20
	progress_slider.value_changed.connect(func(v): elapsed = v; elapsed_label.text = format_time(v))
	timeline.add_child(progress_slider)
	timeline.add_child(duration_label)
	var volume_icon := make_label("◖", 18, MUTED)
	row.add_child(volume_icon)
	var volume := HSlider.new()
	volume.custom_minimum_size.x = 92
	volume.max_value = 100
	volume.value = 72
	volume.tooltip_text = "音量"
	row.add_child(volume)
	var device := make_button("▣", "播放设备")
	device.pressed.connect(func(): toast("正在此电脑播放"))
	row.add_child(device)
	return outer

func _process(delta: float) -> void:
	if playing:
		elapsed += delta
		if elapsed >= duration: _next()
		progress_slider.set_value_no_signal(elapsed)
		elapsed_label.text = format_time(elapsed)

func set_track(index: int) -> void:
	current_track = posmod(index, tracks.size())
	var t: Dictionary = tracks[current_track]
	title_label.text = t.title
	artist_label.text = t.artist
	current_art.primary = t.color
	current_art.accent = t.accent
	current_art.queue_redraw()
	duration = parse_duration(t.time)
	elapsed = 0
	progress_slider.max_value = duration
	progress_slider.value = 0
	duration_label.text = t.time
	for i in track_rows.size():
		track_rows[i].modulate = Color(1,1,1,1) if i != current_track else Color(0.65,1.0,0.92,1)
	toast("正在播放 · " + t.title)

func set_playing(value: bool) -> void:
	playing = value
	play_button.text = "Ⅱ" if playing else "▶"
	play_button.tooltip_text = "暂停" if playing else "播放"

func _next() -> void:
	set_track(current_track + 1)
	set_playing(true)

func _previous() -> void:
	if elapsed > 4:
		elapsed = 0
	else:
		set_track(current_track - 1)
	set_playing(true)

func _toggle_like() -> void:
	liked = not liked
	like_button.text = "♥" if liked else "♡"
	like_button.tooltip_text = "取消收藏" if liked else "添加到收藏"
	like_button.add_theme_color_override("font_color", CORAL if liked else MUTED)
	toast("已添加到收藏" if liked else "已从收藏移除")

func _navigate(index: int, label_text: String) -> void:
	nav_index = index
	select_nav(index)
	var titles := ["让夜晚慢下来", "发现新鲜声音", "为此刻选一首", "最近播放", "你的心动收藏", "专辑收藏", "关注的艺人"]
	content_title.text = titles[index]
	content_subtitle.text = "Auralis 根据你的聆听习惯持续更新。"
	toast("已切换到「" + label_text + "」")

func select_nav(index: int) -> void:
	for i in nav_buttons.size():
		nav_buttons[i].add_theme_stylebox_override("normal", button_style(Color(0.32,0.88,0.77,0.16) if i == index else Color(1,1,1,0.0)))
		nav_buttons[i].add_theme_color_override("font_color", TEAL if i == index else TEXT)

func _on_search(query: String) -> void:
	var q := query.strip_edges().to_lower()
	for i in tracks.size():
		var t: Dictionary = tracks[i]
		track_rows[i].visible = q.is_empty() or q in (t.title + " " + t.artist + " " + t.album).to_lower()

func _on_resized() -> void:
	if not sidebar: return
	var w := size.x
	sidebar.visible = w >= 1060
	queue_panel.visible = w >= 1260
	hero.custom_minimum_size.y = 230 if w < 1100 else 250

func toast(message: String) -> void:
	status_label.text = message
	var tween := create_tween()
	tween.tween_interval(2.0)
	tween.tween_callback(func(): status_label.text = "高解析度无损  ·  24-bit")

func format_time(value: float) -> String:
	return "%d:%02d" % [int(value) / 60, int(value) % 60]

func parse_duration(value: String) -> float:
	var parts := value.split(":")
	return float(parts[0].to_int() * 60 + parts[1].to_int())

class AmbientBackground extends Control:
	func _ready() -> void:
		set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
		mouse_filter = Control.MOUSE_FILTER_IGNORE

	func _draw() -> void:
		draw_rect(Rect2(Vector2.ZERO, size), BG)
		for i in 18:
			var p := Vector2(fmod(i * 313.0, size.x + 200.0) - 100.0, fmod(i * 197.0, size.y + 180.0) - 90.0)
			var c := [Color(0.32,0.88,0.77,0.035), Color(1.0,0.43,0.45,0.025), Color(0.97,0.74,0.38,0.02)][i % 3]
			draw_circle(p, 90.0 + (i % 4) * 28.0, c)

	func _notification(what: int) -> void:
		if what == NOTIFICATION_RESIZED: queue_redraw()

class AlbumArt extends Control:
	var primary := Color("6c63ff")
	var accent := Color("52e0c4")

	func _ready() -> void:
		mouse_filter = Control.MOUSE_FILTER_IGNORE

	func _draw() -> void:
		var r := Rect2(Vector2.ZERO, size)
		var base := StyleBoxFlat.new()
		base.bg_color = primary.darkened(0.38)
		base.corner_radius_top_left = 12; base.corner_radius_top_right = 12
		base.corner_radius_bottom_left = 12; base.corner_radius_bottom_right = 12
		draw_style_box(base, r)
		draw_circle(Vector2(size.x * .72, size.y * .26), size.x * .38, Color(primary, .78))
		draw_circle(Vector2(size.x * .28, size.y * .76), size.x * .34, Color(accent, .70))
		var center := size * .5
		for i in 5:
			var radius := size.x * (.10 + i * .055)
			draw_arc(center, radius, 0, TAU, 48, Color(1,1,1,.14), maxf(1.0, size.x * .008))
		draw_circle(center, size.x * .055, Color(0.05,0.06,0.08,.85))
		draw_circle(center, size.x * .018, accent)

	func _notification(what: int) -> void:
		if what == NOTIFICATION_RESIZED: queue_redraw()

class Waveform extends Control:
	var phase := 0.0
	func _ready() -> void:
		mouse_filter = Control.MOUSE_FILTER_IGNORE
		set_process(true)
	func _process(delta: float) -> void:
		phase += delta * 2.0
		queue_redraw()
	func _draw() -> void:
		var count := 32
		var gap := size.x / count
		for i in count:
			var h := (sin(i * .71 + phase) * .5 + .5) * 36.0 + 8.0
			var color := TEAL if i < 13 else Color(1,1,1,.16)
			draw_line(Vector2(i*gap + gap*.5, size.y*.5-h*.5), Vector2(i*gap + gap*.5, size.y*.5+h*.5), color, maxf(2.0,gap*.35), true)
