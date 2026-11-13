package io.github.myshihi.pleosproject26.n02vehiclekaraoke

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var state: Session
    private lateinit var player: Playback
    private lateinit var vehicle: VehicleLink
    private lateinit var ambient: AmbientLights
    private val handler = Handler(Looper.getMainLooper())
    private var park = ParkState()
    private var parkLabel = "차량 상태 확인 중"
    private var active = false
    private var seatsVerified = false
    private var tab = 0
    private var fullStage = false
    private var subscriptionCatalog = false
    private var queryText = ""
    private var searchResults = emptyList<Song>()
    private var searchNote = "차량의 멜론·지니뮤직 구독 계정으로 연결해 주세요."
    private val seatAccess = SeatAccess()
    private val remote = PartyServer(8792, ::remoteRequest)
    private val lastPhoneSeen = mutableMapOf<SeatId, Long>()
    private var note = "함께 부를 좌석을 선택해 주세요."
    private val occupancy = mutableMapOf<SeatId, Boolean?>()
    private val occupancyText = mutableMapOf<SeatId, String>()
    private var cue: TextView? = null
    private var lyric: TextView? = null
    private var nextLyric: TextView? = null
    private var personal: TextView? = null
    private var nextTurn: TextView? = null
    private var transport: TextView? = null
    private var clock: TextView? = null
    private var progress: SeekBar? = null
    private var art: StageArt? = null
    private var stageBackdrop: KaraokeBackdrop? = null
    private var karaokeLine: KaraokeLine? = null
    private var scrubbing = false
    private var lastSave = 0L
    private var lastUiSecond = -1
    private var renderedPlaying = false
    private var renderedDuration = 0
    private val partLabels = mutableListOf<Pair<Int, TextView>>()
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            state.positionMs = player.position
            // External music apps can report state after their callback; redraw controls when the real state differs.
            if (player.playing != renderedPlaying || player.duration != renderedDuration) render()
            updateLive()
            ambient.sync(player.playing, park.allowed, C.singer(state.singerAt(state.positionMs)), state.positionMs)
            if (player.playing && android.os.SystemClock.elapsedRealtime() - lastSave > 1500) save()
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        state = Session.decode(getSharedPreferences("pleoke", MODE_PRIVATE).getString("session", null))
        tab = if (state.current == null) 0 else 2
        if (state.current != null) note = "저장한 공연을 불러왔어요. 좌석을 다시 확인하고 이어 불러 주세요."
        val allowed = { active && park.allowed && seatsVerified && state.ready }
        val changed: (String) -> Unit = { message -> note = message; state.positionMs = player.position; save(); render() }
        val ended = {
            state.positionMs = player.position; state.finished = true
            note = "함께 불러서 더 좋았어요. 다음 곡도 나눠 불러 볼까요?"; save(); render()
        }
        val local = KaraokePlayer(this, allowed, { message ->
            note = message; save(); render()
        }, {
            state.positionMs = state.current?.song?.durationMs ?: 0; state.finished = true
            note = "함께 불러서 더 좋았어요. 다음 곡도 나눠 불러 볼까요?"; save(); render()
        })
        player = Playback(local, SubscriptionMusic(this, allowed, changed, ended))
        ambient = AmbientLights(this) { if (active) render() }
        player.load(state.current?.song, state.positionMs); player.setVolume(state.volume)
        vehicle = VehicleLink(this, { gear, ux, label ->
            val before = park.allowed
            park = park.copy(gear = gear, uxAllowed = ux)
            val changed = parkLabel != label || before != park.allowed
            parkLabel = label
            if (!park.allowed && player.playing) pause("차량 상태가 바뀌어 공연을 저장하고 멈췄어요.")
            else if (changed) { player.publish(); render() }
        }, { seat, value, detail ->
            val changed = occupancy.containsKey(seat) && occupancy[seat] != value
            occupancy[seat] = value; occupancyText[seat] = detail
            if (changed && seat in state.seats) {
                seatsVerified = false; pause("${seat.label} 점유 상태가 바뀌었어요. 참여 좌석을 다시 확인해 주세요.")
            } else if (tab == 0) render()
        })
        if (android.os.Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(0) {
                if (fullStage) { fullStage = false; render() }
                else if (tab > 0) { tab--; render() } else moveTaskToBack(true)
        }
        render()
    }

    override fun onStart() {
        super.onStart(); active = true; park = ParkState(); player.subscription.refresh(); remote.start(); ambient.start(); vehicle.start(); handler.post(tick)
    }
    override fun onPause() {
        if (player.playing) note = "앱 화면을 벗어나 공연을 저장하고 멈췄어요. 준비되면 직접 이어 불러 주세요."
        player.pause(); state.positionMs = player.position; save()
        ambient.sync(false, false, C.A, 0)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }
    override fun onResume() { super.onResume(); player.publish(); render() }
    override fun onStop() {
        active = false; handler.removeCallbacks(tick)
        player.pause(); state.positionMs = player.position; save()
        vehicle.stop(); park = ParkState(); seatsVerified = false
        remote.stop(); player.subscription.disconnect(); ambient.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }
    override fun onDestroy() { player.release(); super.onDestroy() }

    private fun save() {
        getSharedPreferences("pleoke", MODE_PRIVATE).edit().putString("session", state.encode()).apply()
        lastSave = android.os.SystemClock.elapsedRealtime()
    }
    private fun pause(message: String) {
        player.pause(); ambient.sync(false, false, C.A, 0); state.positionMs = player.position; note = message; save(); render()
    }
    private fun play() {
        if (!seatsVerified) { tab = 0; note = "참여 좌석을 확인한 뒤 시작해 주세요."; render(); return }
        if (!park.allowed || !state.ready) return
        player.play()
    }
    private fun switchTab(value: Int) { tab = value; fullStage = false; render() }
    private fun editParts(action: () -> Unit) {
        player.pause(); state.positionMs = player.position; action(); save(); render()
    }
    private fun refreshTrack() { player.load(state.current?.song, state.positionMs); save(); render() }

    private fun render() {
        if (!::ui.isInitialized) return
        cue = null; lyric = null; nextLyric = null; personal = null; nextTurn = null
        transport = null; progress = null; clock = null; art = null; partLabels.clear()
        stageBackdrop = null; karaokeLine = null
        lastUiSecond = -1; renderedPlaying = player.playing; renderedDuration = player.duration
        val root = ui.column().apply {
            setBackgroundColor(C.BG); setPadding(ui.dp(34), ui.dp(22), ui.dp(34), ui.dp(16))
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(ui.dp(34) + bars.left, ui.dp(22) + bars.top, ui.dp(34) + bars.right, ui.dp(16) + bars.bottom)
                insets
            }
        }
        val head = ui.row()
        val brand = ui.row().apply {
            addView(ui.text("PLEOKE", 30f, C.LIME, true)); addView(ui.space(w = 18))
            addView(ui.text("PARK & SING", 14f, C.MUTED, true))
        }
        head.addView(ui.weight(brand)); head.addView(ui.chip(if (park.interrupted) "공연 중단 체험 중" else parkLabel, if (park.allowed) C.LIME else C.WARN))
        head.addView(ui.space(w = 14)); head.addView(ui.button("연결·설정") { settings() })
        if (!fullStage) { root.addView(head); root.addView(ui.space(h = 18)) }
        val nav = ui.row()
        listOf("01  좌석 참여", "02  곡 · 파트", "03  라이브").forEachIndexed { i, title ->
            nav.addView(ui.weight(ui.button(title, if (tab == i) C.LIME else C.PANEL, if (tab == i) C.BG else C.MUTED) { switchTab(i) }))
            if (i < 2) nav.addView(ui.space(w = 10))
        }
        if (!fullStage) { root.addView(nav); root.addView(ui.space(h = 14)) }
        val banner = ui.text(note, 17f, if (park.allowed) C.MUTED else C.WARN).apply { maxLines = 2 }
        if (!fullStage) { root.addView(banner); root.addView(ui.space(h = 12)) }
        val content = when (tab) { 0 -> seatsPage(); 1 -> songsPage(); else -> livePage() }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(ui.space(h = 16))
        if (state.current != null) root.addView(playerBar())
        root.addView(ui.text("수신기 보컬 입력은 검증 전 · ${ambient.status}", 12f, C.MUTED), ui.lp(top = 8))
        setContentView(root); root.requestApplyInsets(); updateLive()
    }

    private fun seatsPage(): View {
        val body = ui.column()
        ui.title(body, "YOUR CABIN, YOUR STAGE", "뒷자리까지, 모두의 노래방", "참여 좌석을 정하고 QR로 내 가사 화면을 열어 주세요. 마이크는 공용 수신기로 연결해요.")
        SeatId.entries.chunked(2).forEach { pair ->
        val cards = ui.row()
        pair.forEachIndexed { i, seat ->
            val joined = seat in state.seats; val color = C.seat(seat)
            val card = ui.card(stroke = if (joined) color else C.LINE)
            val top = ui.row(); top.addView(ui.weight(ui.text(seat.label, 29f, color, true)))
            top.addView(ui.chip(if (joined) "참여 중" else "참여 전", if (joined) color else C.MUTED)); card.addView(top)
            card.addView(ui.text(seat.place, 18f, C.MUTED), ui.lp(top = 6))
            card.addView(ui.space(h = 10))
            card.addView(ui.text(when (occupancy[seat]) { true -> "차량 신호: 점유"; false -> "차량 신호: 비점유"; null -> "차량 신호: 확인 필요" }, 21f, bold = true))
            card.addView(ui.text(occupancyText[seat] ?: "Pleos Seat · 수신 대기", 15f, C.MUTED), ui.lp(top = 8))
            card.addView(ui.text(if ((lastPhoneSeen[seat] ?: 0) > android.os.SystemClock.elapsedRealtime() - 4000) "휴대폰 연결됨 · 내 가사 화면" else "휴대폰 QR · 가사와 차례를 내 손안에", 16f, color), ui.lp(top = 10))
            val actions = ui.row()
            actions.addView(ui.weight(ui.button(if (joined) "참여 취소" else "이 좌석 참여", if (joined) C.SOFT else color, if (joined) C.WHITE else C.BG) {
                editParts {
                    state.participate(seat); seatsVerified = false
                    note = "참여 좌석을 바꿨어요. 좌석 확인 후 곡의 파트를 다시 확정해 주세요."
                }
            }))
            actions.addView(ui.space(w = 10)); actions.addView(ui.weight(ui.button("휴대폰 QR", enabled = remote.running) { showQr(seat) }))
            card.addView(actions, ui.lp(top = 14))
            cards.addView(ui.weight(card)); if (i == 0) cards.addView(ui.space(w = 20))
        }
        body.addView(cards, ui.lp(bottom = 14))
        }
        val footer = ui.row(); footer.addView(ui.weight(ui.text("${state.seats.size}명 참여 · 앞좌석 A·B / 뒷좌석 C·D", 18f, C.MUTED)))
        footer.addView(ui.button(if (seatsVerified) "곡 고르러 가기 →" else "참여 좌석 확인 완료 →", C.LIME, C.BG, state.seats.isNotEmpty()) {
            seatsVerified = true; tab = 1; note = "부르고 싶은 곡을 예약하고 파트를 나눠 보세요."; save(); render()
        })
        body.addView(footer, ui.lp(top = 26))
        return ui.scroll(body)
    }

    private fun songsPage(): View {
        val panels = ui.row().apply { gravity = Gravity.TOP }
        val catalog = ui.column()
        ui.title(catalog, if (subscriptionCatalog) "VEHICLE SUBSCRIPTION" else "KARAOKE SONGBOOK", "어떤 노래로 시작할까요?",
            if (subscriptionCatalog) "차량의 음악 앱 구독 계정으로 검색·재생해요." else "원저작 반주·가사·좌석 파트가 준비된 시연곡 3곡")
        val sources = ui.row()
        sources.addView(ui.weight(ui.button("시연 노래방", if (!subscriptionCatalog) C.LIME else C.SOFT, if (!subscriptionCatalog) C.BG else C.WHITE) { subscriptionCatalog = false; render() }))
        sources.addView(ui.space(w = 10)); sources.addView(ui.weight(ui.button("차량 구독 음악", if (subscriptionCatalog) C.A else C.SOFT, if (subscriptionCatalog) C.BG else C.WHITE) { subscriptionCatalog = true; musicApps() }))
        catalog.addView(sources, ui.lp(bottom = 12))
        val picker = ui.row()
        SeatId.entries.forEach { id ->
            picker.addView(ui.weight(ui.button("${id.label}에서 예약", if (state.viewSeat == id) C.seat(id) else C.SOFT,
                if (state.viewSeat == id) C.BG else C.WHITE, id in state.seats) { state.viewSeat = id; save(); render() }))
            picker.addView(ui.space(w = 8))
        }
        catalog.addView(picker); catalog.addView(ui.space(h = 16))
        if (subscriptionCatalog) {
            val search = ui.row()
            val canSearch = player.subscription.selected != null && park.allowed
            val input = EditText(this).apply {
                setSingleLine(true); hint = "곡 제목 · 가수"; setText(queryText); textSize = 20f * ui.scale
                // Car displays are landscape: keep the app visible instead of a full-screen extract keyboard.
                imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            }
            fun runSearch() {
                if (!canSearch) return
                getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
                queryText = input.text.toString(); searchNote = "차량 음악 앱에서 검색 중…"; render()
                player.subscription.search(queryText) { songs, message -> searchResults = songs; searchNote = message; render() }
            }
            input.setOnEditorActionListener { _, action, event ->
                val enter = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN
                if (action == EditorInfo.IME_ACTION_SEARCH || enter) { runSearch(); true } else false
            }
            search.addView(ui.weight(input)); search.addView(ui.button("검색", enabled = canSearch) { runSearch() }); catalog.addView(search)
            catalog.addView(ui.text("${player.subscription.selected?.label ?: "음악 앱 선택 필요"} · $searchNote", 16f, C.MUTED), ui.lp(top = 10, bottom = 10))
            catalog.addView(ui.button("차량 음악 앱에서 고른 현재 곡 가져오기", enabled = park.allowed) {
                val song = player.subscription.currentSong()
                if (song == null) { searchNote = "공개된 현재 곡 ID가 없어요. 차량 음악 앱에서 곡을 선택하고 연결을 확인해 주세요."; render() }
                else { searchResults = listOf(song); searchNote = "음악 앱의 현재 곡 정보 · 파일·가사는 가져오지 않아요"; render() }
            }, ui.lp(bottom = 12))
        }
        val shown = if (subscriptionCatalog) searchResults else Catalog.songs
        shown.forEachIndexed { i, song ->
            val card = ui.card(pad = 22)
            val row = ui.row()
            row.addView(ui.text("%02d".format(i + 1), 28f, listOf(C.LIME, C.A, C.B)[i % 3], true)); row.addView(ui.space(w = 20))
            val info = ui.column(); info.addView(ui.text(song.title, 24f, bold = true))
            info.addView(ui.text(if (song.external) "${song.mood} · ${song.appLabel} 구독" else "${song.mood}  ·  ${time(song.durationMs)}  ·  ${song.bpm} BPM", 16f, C.MUTED), ui.lp(top = 8))
            row.addView(ui.weight(info)); row.addView(ui.button("+ 예약", enabled = state.viewSeat in state.seats && state.queue.size < 12) {
                reserve(song, state.viewSeat)
            }); card.addView(row); catalog.addView(card, ui.lp(bottom = 12))
        }
        catalog.addView(ui.text(if (subscriptionCatalog) "음악 앱에서 재생해요. 노래방 반주·시간 가사·파트 데이터는 별도 제공이 필요해요." else "휴대폰에서도 이 곡을 예약하고 내 가사·차례를 볼 수 있어요.", 15f, C.MUTED))
        panels.addView(ui.weight(ui.scroll(catalog), 1.05f, -1)); panels.addView(ui.space(w = 24))
        val right = ui.column()
        val e = state.current
        if (e == null) {
            val empty = ui.card(); empty.addView(ui.text("아직 무대가 비어 있어요", 27f, bold = true))
            empty.addView(ui.text("왼쪽에서 노래를 예약하면\n좌석별 파트를 추천해 드려요.", 20f, C.MUTED), ui.lp(top = 18)); right.addView(empty)
        } else if (e.song.external) {
            val card = ui.card(); card.addView(ui.chip("차량 구독 음악", C.A))
            card.addView(ui.text(e.song.title, 29f, bold = true), ui.lp(top = 16))
            card.addView(ui.text("${e.song.appLabel} 차량 구독 계정으로 재생합니다.\n원곡 재생과 노래방 반주·가사는 별도예요.\n이 곡의 가사·파트 데이터는 제공되지 않았어요.", 20f, C.MUTED), ui.lp(top = 18))
            card.addView(ui.button("음악 무대로 →", C.LIME, C.BG) { tab = 2; render() }, ui.lp(top = 22)); right.addView(card)
        } else {
            val plan = ui.card()
            val heading = ui.row(); heading.addView(ui.weight(ui.text("파트 나누기", 27f, bold = true)))
            heading.addView(ui.chip(if (e.confirmed) "확정됨" else "합의 전", if (e.confirmed) C.LIME else C.WARN)); plan.addView(heading)
            plan.addView(ui.text(e.song.title, 20f, C.MUTED), ui.lp(top = 8, bottom = 12))
            e.song.parts.forEachIndexed { i, part ->
                val row = ui.row(); row.addView(ui.weight(ui.text("${part.title}  ${time(e.song.beatMs(part.startBeat))}", 18f)))
                row.addView(ui.button("${e.singers[i].label}  ↔", C.alpha(C.singer(e.singers[i]), 30), C.singer(e.singers[i])) {
                    editParts { state.cyclePart(i); note = "파트를 바꿨어요. 함께 확인하고 확정해 주세요." }
                }); plan.addView(row, ui.lp(top = 6))
            }
            val actions = ui.row(); actions.addView(ui.weight(ui.button("다시 추천") { editParts { state.recommend() } }))
            actions.addView(ui.space(w = 10)); actions.addView(ui.weight(ui.button("파트 확정 →", C.LIME, C.BG, state.seats.isNotEmpty()) {
                if (state.confirm()) { tab = 2; note = "파트가 정해졌어요. 준비되면 함께 불러 주세요."; save(); render() }
            })); plan.addView(actions, ui.lp(top = 18)); right.addView(plan)
        }
        val queue = ui.card(); queue.addView(ui.text("예약 목록  ${state.queue.size} / 12", 22f, bold = true))
        state.queue.forEachIndexed { i, item ->
            val row = ui.row(); row.addView(ui.weight(ui.text("${if (i == 0) "NOW" else "%02d".format(i)}  ${item.song.title}\n${item.requester.label} 예약", 16f, if (i == 0) C.LIME else C.MUTED)))
            row.addView(ui.button("삭제") {
                if (i == 0) AlertDialog.Builder(this).setTitle("현재 곡을 예약에서 뺄까요?")
                    .setMessage("현재 곡의 진행 위치가 지워지고 다음 곡을 준비해요.").setNegativeButton("유지", null)
                    .setPositiveButton("빼기") { _, _ -> player.pause(); state.next(); refreshTrack() }.show()
                else { state.queue.removeAt(i); save(); render() }
            }); queue.addView(row, ui.lp(top = 12))
        }
        right.addView(queue, ui.lp(top = 18)); panels.addView(ui.weight(ui.scroll(right), 1f, -1)); return panels
    }

    private fun livePage(): View {
        val e = state.current ?: return ui.scroll(ui.column().apply {
            ui.title(this, "READY WHEN YOU ARE", "첫 노래를 골라 주세요", "곡과 파트를 준비하면 나만의 가사 화면이 열려요.")
            addView(ui.button("노래 고르기", C.LIME, C.BG) { switchTab(1) })
        })
        val frame = FrameLayout(this)
        stageBackdrop = KaraokeBackdrop(this).also { frame.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        val stage = ui.column().apply { setPadding(ui.dp(30), ui.dp(20), ui.dp(30), ui.dp(20)) }
        val top = ui.row()
        val title = ui.column()
        title.addView(ui.text(if (e.song.external) "VEHICLE MUSIC  /  ${e.song.appLabel}" else "PLEOKE KARAOKE  /  01", 15f, C.A, true))
        title.addView(ui.text(e.song.title, 27f, bold = true), ui.lp(top = 5))
        top.addView(ui.weight(title)); top.addView(ui.button("휴대폰 QR") { showQr(state.viewSeat) })
        top.addView(ui.space(w = 10)); top.addView(ui.button(if (fullStage) "무대 줄이기" else "전체 무대") { fullStage = !fullStage; render() })
        stage.addView(top)
        val picker = ui.row()
        SeatId.entries.forEach { id ->
            picker.addView(ui.weight(ui.button("${id.label} 화면", if (state.viewSeat == id) C.seat(id) else C.alpha(C.SOFT, 180),
                if (state.viewSeat == id) C.BG else C.WHITE, id in state.seats) { state.viewSeat = id; save(); render() }))
            picker.addView(ui.space(w = 8))
        }
        stage.addView(picker, ui.lp(top = 14))
        val words = ui.column().apply { gravity = Gravity.CENTER }
        cue = ui.text("", 23f, C.LIME, true).also { it.gravity = Gravity.CENTER; words.addView(it) }
        karaokeLine = KaraokeLine(this).also { words.addView(it, ui.lp(h = ui.dp(if (fullStage) 210 else 165), top = 8)) }
        nextLyric = ui.text("", 28f, C.alpha(C.WHITE, 175), true).also { it.gravity = Gravity.CENTER; words.addView(it, ui.lp(top = 6)) }
        stage.addView(words, LinearLayout.LayoutParams(-1, 0, 1f))
        val bottom = ui.row()
        val personalBox = ui.column()
        personal = ui.text("", 24f, C.seat(state.viewSeat), true).also { personalBox.addView(it) }
        nextTurn = ui.text("", 17f, C.MUTED).also { personalBox.addView(it, ui.lp(top = 6)) }
        bottom.addView(ui.weight(personalBox)); bottom.addView(ui.button("파트 보기 · 편집") {
            val message = if (e.song.external) "이 구독곡의 노래방 가사·파트 데이터는 제공되지 않았어요." else
                e.song.parts.mapIndexed { i, p -> "${time(e.song.beatMs(p.startBeat))}  ${p.title}  ·  ${e.singers[i].label}" }.joinToString("\n")
            AlertDialog.Builder(this).setTitle("우리의 파트").setMessage(message).setNegativeButton("닫기", null)
                .setPositiveButton("편집") { _, _ -> switchTab(1) }.show()
        }); stage.addView(bottom)
        frame.addView(stage, FrameLayout.LayoutParams(-1, -1)); return frame
    }

    private fun playerBar(): View {
        val e = state.current!!
        val box = ui.card(pad = 18)
        val seekRow = ui.row()
        clock = ui.text("", 16f, C.MUTED).also { seekRow.addView(it, LinearLayout.LayoutParams(ui.dp(140), -2)) }
        progress = SeekBar(this).also { seek ->
            seek.max = player.duration; seek.progress = state.positionMs; seek.isEnabled = park.allowed && !state.finished && player.canSeek && player.duration > 0
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(s: SeekBar) { scrubbing = true }
                override fun onProgressChanged(s: SeekBar, value: Int, user: Boolean) { if (user) clock?.text = "${time(value)} / ${time(player.duration)}" }
                override fun onStopTrackingTouch(s: SeekBar) {
                    if (park.allowed) { player.seek(s.progress); state.positionMs = s.progress; save() }
                    scrubbing = false; updateLive()
                }
            }); seekRow.addView(ui.weight(seek))
        }; box.addView(seekRow)
        val row = ui.row()
        val labels = ui.column(); labels.addView(ui.text(e.song.title, 20f, bold = true)); labels.addView(ui.text("${state.seats.size}명 참여 · 남은 예약 ${state.queue.size - 1}곡", 14f, C.MUTED), ui.lp(top = 4))
        row.addView(ui.weight(labels))
        row.addView(ui.button("처음부터", enabled = park.allowed) {
            player.pause(); state.finished = false; state.positionMs = 0; player.load(e.song, 0); save(); render()
        }); row.addView(ui.space(w = 10))
        val label = if (state.finished) "한 곡 완료 ✓" else if (player.playing) "Ⅱ  잠시 멈춤" else if (e.song.external) "▶  구독 음악 재생" else "▶  함께 부르기"
        transport = ui.button(label, C.LIME, C.BG, !state.finished && park.allowed && e.confirmed) {
            if (player.playing) pause("잠시 멈췄어요. 같은 파트에서 이어 부를 수 있어요.") else play()
        }.also { row.addView(it) }
        row.addView(ui.space(w = 10)); row.addView(ui.button("다음 곡 준비", enabled = state.queue.size > 1 && park.allowed) {
            if (state.finished) { player.pause(); state.next(); tab = 1; fullStage = false; note = "다음 곡의 파트를 확인해 주세요."; refreshTrack() }
            else AlertDialog.Builder(this).setTitle("현재 곡을 마치고 다음 곡을 준비할까요?")
                .setNegativeButton("계속 부르기", null).setPositiveButton("다음 곡") { _, _ ->
                    player.pause(); state.next(); tab = 1; fullStage = false; note = "다음 곡의 파트를 확인해 주세요."; refreshTrack()
                }.show()
        }); box.addView(row, ui.lp(top = 6)); return box
    }

    private fun updateLive() {
        val e = state.current ?: return
        val song = e.song; val ms = state.positionMs; val part = song.partAt(ms); val li = song.lyricAt(ms)
        val who = state.singerAt(ms); val color = C.singer(who)
        val words = displayWords(state.viewSeat)
        stageBackdrop?.let {
            if (it.position != ms || it.running != player.playing || it.tint != color) {
                it.position = ms; it.running = player.playing; it.tint = color; it.invalidate()
            }
        }
        karaokeLine?.let { view ->
            if (view.line != words.lyric || view.fill != words.fill || view.tint != words.color) {
                view.line = words.lyric; view.fill = words.fill; view.tint = words.color; view.invalidate()
            }
        }
        if (lastUiSecond == ms / 1000) return
        lastUiSecond = ms / 1000
        if (!scrubbing) {
            progress?.max = player.duration; progress?.progress = ms
            progress?.isEnabled = park.allowed && !state.finished && player.canSeek && player.duration > 0
            clock?.text = "${time(ms)} / ${if (player.duration > 0) time(player.duration) else "—"}"
        }
        cue?.text = words.cue; cue?.setTextColor(words.color)
        nextLyric?.text = words.next; personal?.text = words.mine; nextTurn?.text = words.nextTurn
        partLabels.forEach { (i, view) ->
            view.text = "${if (i == part) "●" else if (i < part) "✓" else "○"}  ${song.parts[i].title}    ${e.singers[i].label}"
            view.setTextColor(if (i == part) C.singer(e.singers[i]) else C.MUTED)
            view.background = ui.bg(if (i == part) C.alpha(C.singer(e.singers[i]), 25) else C.PANEL, 12)
        }
        if (player.playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private data class Words(val cue: String, val lyric: String, val next: String, val mine: String,
                             val nextTurn: String, val color: Int, val fill: Float)

    private fun displayWords(seat: SeatId): Words {
        val e = state.current ?: return Words("노래와 파트를 준비해 주세요", "함께 부를 준비됐나요?", "", "", "", C.LIME, 0f)
        val song = e.song; val ms = player.position
        if (song.external) return Words("${song.appLabel} · 차량 구독 음악", "노래방 가사 제공 전",
            "${song.title} · ${song.mood}", if (player.playing) "차량 음악 앱에서 재생 중" else "차량 음악 앱 재생 대기",
            "원곡 재생 · 반주·시간 가사는 별도 제공 필요", C.A, 0f)
        val p = song.partAt(ms); val li = song.lyricAt(ms); val who = e.singers.getOrNull(p)
        val mine = who == Singer.ALL || who?.name == seat.name
        val nextMine = song.parts.indices.firstOrNull { it > p && (e.singers[it] == Singer.ALL || e.singers[it].name == seat.name) }
        val lyric = when { state.finished -> "우리의 다음 노래도 함께"; li < 0 -> "목소리를 들려 주세요"; else -> song.lyrics[li].text }
        val next = when { state.finished -> "다음 곡을 준비하거나 한 번 더 불러 보세요"; li < 0 -> "첫 가사  ·  ${song.lyrics.first().text}"; li + 1 < song.lyrics.size -> song.lyrics[li + 1].text; else -> "마지막까지 함께 불러요" }
        val count = ((song.beatMs(8) - ms) / (60_000f / song.bpm)).let { kotlin.math.ceil(it).toInt() }
        val cue = when {
            state.finished -> "함께 부른 노래, 완료!"
            !e.confirmed -> "파트를 함께 확인하고 확정해 주세요"
            p < 0 -> if (count in 1..4) "준비  $count  ·  잠시 후 ${e.singers.first().label}" else "전주  ·  잠시 후 ${e.singers.first().label} 차례"
            else -> "${who?.label}  /  ${song.parts[p].title}"
        }
        val personal = when { state.finished -> "수고했어요, ${seat.label}!"; !e.confirmed -> "파트를 먼저 확정해 주세요"; who == null -> "곧 시작해요"; mine -> if (who == Singer.ALL) "지금은 함께 부르는 파트!" else "지금은 내 차례!"; else -> "지금은 ${who.label} 차례예요" }
        val turn = if (nextMine == null || state.finished) "오늘의 목소리를 함께 기억해요" else "내 다음 차례  ${song.parts[nextMine].title} · ${time(song.beatMs(song.parts[nextMine].startBeat))}"
        val start = song.lyrics.getOrNull(li)?.let { song.beatMs(it.beat) } ?: 0
        val end = song.lyrics.getOrNull(li + 1)?.let { song.beatMs(it.beat) } ?: song.durationMs
        val fill = if (li < 0 || state.finished) 0f else ((ms - start).toFloat() / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f)
        return Words(cue, lyric, next, personal, turn, C.singer(who).takeIf { who != null } ?: C.A, fill)
    }

    private fun reserve(song: Song, seat: SeatId): Boolean {
        val empty = state.current == null
        if (!state.enqueue(song, seat)) return false
        note = "${seat.label}에서 ‘${song.title}’ 예약 · ${if (song.external) "차량 구독 음악" else "파트를 확인해 주세요"}."
        if (empty) player.load(song, 0)
        save(); render(); return true
    }

    private fun showQr(seat: SeatId) {
        val hosts = LocalAddress.candidates()
        val box = ui.column().apply { gravity = Gravity.CENTER; setPadding(ui.dp(24), ui.dp(16), ui.dp(24), ui.dp(20)) }
        val host = hosts.firstOrNull()
        if (host != null && remote.running) {
            val url = seatAccess.url(host, remote.port, seat)
            val qr = QrCode.encode(url); val margin = 4; val unit = 8
            val bmp = Bitmap.createBitmap((qr.size + margin * 2) * unit, (qr.size + margin * 2) * unit, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            for (y in 0 until qr.size) for (x in 0 until qr.size) if (qr[x,y]) {
                for (dy in 0 until unit) for (dx in 0 until unit) bmp.setPixel((x+margin)*unit+dx, (y+margin)*unit+dy, Color.BLACK)
            }
            box.addView(ImageView(this).apply { setImageBitmap(bmp); contentDescription = "${seat.label} 휴대폰 접속 QR" }, ui.lp(ui.dp(300), ui.dp(300)))
            box.addView(ui.text("${seat.label} · ${seat.place}", 25f, C.seat(seat), true), ui.lp(top = 16))
            box.addView(ui.text("휴대폰을 차량과 같은 Wi-Fi에 연결한 뒤 QR을 찍어 주세요.\n노래 소리는 차량에서, 내 가사와 차례는 휴대폰에서 확인해요.", 18f, C.MUTED), ui.lp(top = 12))
            box.addView(ui.text(url, 13f, C.MUTED).apply { setTextIsSelectable(true) }, ui.lp(top = 18))
            box.addView(ui.text("연결되지 않으면 Wi-Fi의 기기 간 접속 허용을 확인해 주세요. 에뮬레이터 주소는 실제 휴대폰에서 바로 열리지 않을 수 있어요.", 14f, C.MUTED), ui.lp(top = 10))
        } else box.addView(ui.text("차량의 Wi-Fi·핫스팟 연결을 먼저 확인해 주세요.", 22f))
        AlertDialog.Builder(this).setTitle("내 자리의 노래방 화면").setView(ui.scroll(box)).setPositiveButton("닫기", null).show()
    }

    private fun remoteRequest(request: HttpRequest): HttpResponse {
        val result = CompletableFuture<HttpResponse>()
        val deadline = android.os.SystemClock.elapsedRealtime() + 2500
        handler.post {
            if (result.isCancelled || android.os.SystemClock.elapsedRealtime() > deadline) return@post
            try {
                val seat = try { SeatId.valueOf(request.query["seat"].orEmpty()) } catch (_: Exception) { null }
                if (!active || seat == null || !seatAccess.accepts(seat, request.query["token"])) {
                    result.complete(HttpResponse.json(403, mapOf("message" to "최신 좌석 QR로 연결해 주세요"))); return@post
                }
                if (request.method == "GET" && request.path == "/join") result.complete(HttpResponse.html(SeatPage.html))
                else if (request.method == "GET" && request.path == "/api/state") {
                    lastPhoneSeen[seat] = android.os.SystemClock.elapsedRealtime()
                    val e = state.current; val words = displayWords(seat)
                    result.complete(HttpResponse.json(200, mapOf(
                        "seatLabel" to seat.label, "place" to seat.place, "seatColor" to hex(C.seat(seat)),
                        "allowed" to park.allowed, "joined" to (seat in state.seats), "confirmed" to (e?.confirmed == true),
                        "playing" to player.playing, "title" to e?.song?.title, "position" to player.position,
                        "duration" to player.duration, "external" to (e?.song?.external == true),
                        "cue" to words.cue, "lyric" to words.lyric, "next" to words.next, "mine" to words.mine, "color" to hex(words.color),
                        "parts" to e?.song?.parts.orEmpty().mapIndexed { i,p -> mapOf("title" to p.title, "owner" to e?.singers?.get(i)?.label, "index" to i) },
                        "catalog" to (Catalog.songs + searchResults).distinctBy { it.id }.map { mapOf("id" to it.id,"title" to it.title,"subtitle" to (it.appLabel ?: it.mood)) },
                        "queue" to state.queue.map { mapOf("title" to it.song.title, "by" to it.requester.label) }
                    )))
                } else if (request.method == "POST" && request.path == "/api/action") {
                    if (!park.allowed) { result.complete(HttpResponse.json(409, mapOf("message" to "주차·차량 상태 확인 후 이용해 주세요"))); return@post }
                    val form = request.form
                    val ok = when (form["kind"]) {
                        "join" -> {
                            if (seat !in state.seats) editParts { state.participate(seat); seatsVerified = false; note = "${seat.label} 휴대폰에서 참여 요청 · 차량에서 좌석과 파트를 확인해 주세요." }
                            true
                        }
                        "reserve" -> (Catalog.songs + searchResults).firstOrNull { it.id == form["value"] }?.let { reserve(it, seat) } ?: false
                        "part" -> {
                            val index = form["value"]?.toIntOrNull()
                            if (index == null || seat !in state.seats || index !in state.current?.singers.orEmpty().indices) false
                            else { editParts { state.claimPart(index, seat); note = "${seat.label}에서 파트를 요청했어요. 차량 화면에서 다시 확정해 주세요." }; true }
                        }
                        else -> false
                    }
                    result.complete(HttpResponse.json(if (ok) 200 else 400, mapOf("message" to if (ok) "반영했어요. 차량 화면에서 확인해 주세요." else "참여 좌석·예약·파트를 다시 확인해 주세요")))
                } else result.complete(HttpResponse.text(404, "not found"))
            } catch (_: Exception) { result.complete(HttpResponse.json(500, mapOf("message" to "차량 화면을 확인해 주세요"))) }
        }
        return try { result.get(3, TimeUnit.SECONDS) } catch (_: Exception) { result.cancel(false); HttpResponse.text(503, "vehicle app busy") }
    }

    private fun hex(color: Int) = "#%06x".format(color and 0xFFFFFF)

    private fun musicApps() {
        player.subscription.refresh()
        val box = ui.column().apply { setPadding(ui.dp(22), ui.dp(12), ui.dp(22), ui.dp(18)) }
        box.addView(ui.text("차량에 설치한 음악 앱의 구독·로그인을 그대로 사용해요.\n외부 검색·곡 재생은 해당 차량 앱이 허용하는 범위에서 연결됩니다.", 19f, C.MUTED))
        val dialog = AlertDialog.Builder(this).setTitle("차량 구독 음악 연결").setView(ui.scroll(box)).setPositiveButton("닫기", null).create()
        listOf("멜론", "지니뮤직").filter { name -> player.subscription.apps.none { it.label.contains(name) || (name == "지니뮤직" && it.label.contains("지니")) } }.forEach { name ->
            box.addView(ui.text("$name · 이 기기에 설치된 차량 앱을 찾지 못했어요", 20f, C.WARN), ui.lp(top = 18))
        }
        player.subscription.apps.forEach { app ->
            val row = ui.row(); row.addView(ui.weight(ui.text(app.label, 23f, bold = true)))
            row.addView(ui.button("연결", enabled = park.allowed) {
                searchResults = emptyList(); searchNote = "${app.label} 연결 확인 중…"; subscriptionCatalog = true
                player.subscription.connect(app) { ok -> searchNote = player.subscription.status; note = searchNote; tab = 1; render() }
                dialog.dismiss(); tab = 1; render()
            }); row.addView(ui.space(w = 8)); row.addView(ui.button("앱 열기", enabled = park.allowed) {
                try { packageManager.getLaunchIntentForPackage(app.pkg)?.let { startActivity(it) } } catch (_: Exception) { note = "음악 앱을 열지 못했어요" }
            }); box.addView(row, ui.lp(top = 18))
        }
        box.addView(ui.text("곡 파일·반주·가사는 추출하지 않아요. 구독곡의 노래방 가사·파트는 서비스 제공이 필요합니다.", 16f, C.MUTED), ui.lp(top = 20))
        box.addView(ui.button("음악 앱 제어 권한 확인") {
            try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) { }
        }, ui.lp(top = 18))
        dialog.show()
    }

    private fun receiverStatus(): String {
        val inputs = getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY }
        return if (inputs.isEmpty()) "수신기: USB 오디오 입력 미감지 · 실제 음성 입력 미구현"
        else "USB 오디오 입력 감지: ${inputs.joinToString { it.productName }}\n수신기·채널 분리·보컬 출력은 실물 검증 전"
    }

    private fun settings() {
        lateinit var dialog: AlertDialog
        val box = ui.column().apply { setPadding(ui.dp(28), ui.dp(12), ui.dp(28), ui.dp(12)) }
        box.addView(ui.text("연결 상태", 24f, bold = true))
        box.addView(ui.text("기어: ${park.gear ?: "미확인"} · Pleos Gear 실제 입력\n차량 UX: ${if (park.uxAllowed) "조작 허용" else "제한 또는 미확인"}\n무선 마이크 여러 개 → 공용 수신기 → 차량 오디오\n${receiverStatus()}\n뒷좌석 화면: 휴대폰 QR · 가사와 차례\n차량 앰비언트: ${ambient.status}", 18f, C.MUTED), ui.lp(top = 16))
        box.addView(ui.button(if (ambient.enabled) "차량 노래방 조명 끄기" else "차량 노래방 조명 켜기", C.SOFT, C.LIME, ambient.ready && park.allowed) {
            ambient.setEnabled(!ambient.enabled); dialog.dismiss(); render()
        }, ui.lp(top = 18))
        box.addView(ui.text("지원되는 차량 조명만 파트 색·완만한 밝기로 연출해요. 일시정지·출발·앱 종료 시 원래 설정으로 복원합니다.", 15f, C.MUTED), ui.lp(top = 10))
        box.addView(ui.button("멜론 · 지니뮤직 차량 구독 연결") { dialog.dismiss(); musicApps() }, ui.lp(top = 18))
        val volumeText = ui.text("반주 음량 ${state.volume}%", 20f); box.addView(volumeText, ui.lp(top = 20))
        box.addView(SeekBar(this).apply {
            max = 100; progress = state.volume
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(s: SeekBar) { }
                override fun onStopTrackingTouch(s: SeekBar) { save() }
                override fun onProgressChanged(s: SeekBar, value: Int, user: Boolean) {
                    if (user) { state.volume = value; player.setVolume(value); volumeText.text = "반주 음량 $value%" }
                }
            })
        })
        dialog = AlertDialog.Builder(this).setTitle("플레오케 · v0.2.0").setView(ui.scroll(box)).setPositiveButton("닫기", null).create()
        box.addView(ui.button(if (park.interrupted) "중단 체험 해제" else "공연 중단 체험", C.SOFT) {
            park = park.copy(interrupted = !park.interrupted)
            pause(if (park.interrupted) "중단 체험으로 멈췄어요. 곡·파트·위치가 저장됐어요." else "중단 체험을 해제했어요. 직접 이어 불러 주세요.")
            dialog.dismiss()
        }, ui.lp(top = 16))
        box.addView(ui.text("중단 체험은 앱 동작 확인용이에요. 실제 차량 신호를 바꾸지 않아요.", 14f, C.MUTED), ui.lp(top = 10))
        box.addView(ui.button("세션 새로 만들기", C.SOFT, C.WARN) {
            AlertDialog.Builder(this).setTitle("새로운 공연을 시작할까요?").setMessage("참여 좌석, 예약곡, 파트와 재생 위치가 지워져요.")
                .setNegativeButton("유지", null).setPositiveButton("새로 시작") { _, _ ->
                    player.pause(); state.reset(); seatAccess.renew(); seatsVerified = false; park = park.copy(interrupted = false); tab = 0; fullStage = false
                    note = "새로운 무대를 만들었어요. 참여 좌석을 골라 주세요."; refreshTrack(); dialog.dismiss()
                }.show()
        }, ui.lp(top = 16)); dialog.show()
    }

    private fun time(ms: Int) = "%d:%02d".format(ms.coerceAtLeast(0) / 60_000, ms.coerceAtLeast(0) / 1000 % 60)
}
