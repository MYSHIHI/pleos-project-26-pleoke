package io.github.myshihi.pleosproject26.n02vehiclekaraoke

import org.json.JSONArray
import org.json.JSONObject

enum class SeatId(val label: String, val place: String, val sdkArea: String) {
    A("좌석 A", "운전석", "ROW_1_LEFT"), B("좌석 B", "동승석", "ROW_1_RIGHT"),
    C("좌석 C", "뒷좌석 왼쪽", "ROW_2_LEFT"), D("좌석 D", "뒷좌석 오른쪽", "ROW_2_RIGHT")
}
enum class Singer(val label: String) { A("좌석 A"), B("좌석 B"), C("좌석 C"), D("좌석 D"), ALL("함께") }
data class Part(val title: String, val startBeat: Int, val endBeat: Int, val recommended: Singer)
data class Lyric(val beat: Int, val text: String)
data class Song(val id: String, val title: String, val mood: String, val bpm: Int, val key: String, val lines: List<String>,
                val app: String? = null, val mediaId: String? = null, val appLabel: String? = null) {
    val external get() = app != null
    val durationMs get() = if (external) 0 else beatMs(96)
    fun beatMs(beat: Int) = (beat * 60_000L / bpm).toInt()
    val parts = if (external) emptyList() else listOf(
        Part("1절", 8, 24, Singer.A), Part("2절", 24, 40, Singer.B),
        Part("첫 후렴", 40, 56, Singer.ALL), Part("브리지", 56, 72, Singer.A),
        Part("마지막 절", 72, 88, Singer.B), Part("마지막 합창", 88, 96, Singer.ALL)
    )
    val lyrics get() = lines.mapIndexed { i, line -> Lyric(8 + i * 4, line) }
    fun partAt(ms: Int) = parts.indexOfFirst { ms >= beatMs(it.startBeat) && ms < beatMs(it.endBeat) }
    fun lyricAt(ms: Int) = lyrics.indexOfLast { ms >= beatMs(it.beat) }
    companion object {
        fun subscription(app: String, label: String, mediaId: String, title: String, artist: String) =
            Song("$app:$mediaId", title, artist, 0, "", emptyList(), app, mediaId, label)
    }
}

object Catalog {
    val songs = listOf(
        Song("campfire", "우리의 작은 무대", "따뜻한 캠핑 팝", 96, "C major", listOf(
            "긴 하루를 잠시 접어 두고", "창밖의 별을 하나 세어 봐", "네가 먼저 작은 노랠 부르면", "나는 다음 멜로디가 될게",
            "익숙한 길을 함께 지나서", "오늘은 여기 쉬어 가도 돼", "서로 다른 목소릴 모아서", "같은 박자 위를 걸어가",
            "우리의 작은 무대 위에서", "너와 나의 밤이 빛나고", "멀리 가지 않아도 괜찮아", "지금 여기 함께 있으니",
            "조용하던 마음의 창문에", "따스한 노랫말이 번져 가", "내 차례가 조금 서툴러도", "웃으며 다시 불러 주면 돼",
            "반짝이는 저 별의 이름을", "우리만의 노래로 남겨 둬", "오늘의 이 멜로딜 기억해", "다음 여행에도 함께 불러",
            "우리의 작은 무대 위에서", "이 밤을 함께 노래해"
        )),
        Song("starlight", "별빛 주차장", "느긋한 나이트 팝", 90, "A minor", listOf(
            "달빛이 지붕 위에 내려와", "느린 바람 창가를 스치면", "바쁘게 달려왔던 마음도", "이 노래 곁에 쉬어 가네",
            "말하지 못한 작은 이야기", "음표를 따라 너에게 닿아", "저마다 다른 오늘의 색이", "별빛 아래 하나가 되네",
            "별빛 주차장 불을 밝혀", "너의 목소리를 들려줘", "오늘 밤은 우리 차례야", "함께라서 좋은 이 순간",
            "잠깐 길을 잃어도 괜찮아", "다음 박자 기다려 줄게", "내 노래와 너의 노래가", "만나는 곳 여기 있으니",
            "밤하늘에 작은 선을 그려", "잊지 못할 지도를 만들자", "다음에도 이 자리에서", "웃으면서 다시 만나자",
            "별빛 주차장 불을 밝혀", "함께 부른 밤을 기억해"
        )),
        Song("weekend", "주말의 리듬", "경쾌한 드라이브 팝", 120, "F major", listOf(
            "운동화 끈을 느슨히 풀고", "숨겨 둔 리듬을 꺼내 봐", "창밖엔 초록빛이 춤추고", "오늘의 기분은 맑음이야",
            "오른손으로 박자를 맞춰", "왼손엔 작은 꿈을 담아서", "네가 웃으면 나도 따라서", "가벼운 멜로디를 불러",
            "주말의 리듬 함께 타고", "좋아하는 순간을 모아", "서로에게 차례를 건네", "하나 둘 셋 같이 노래해",
            "완벽하지 않아도 좋아", "조금 다른 음도 괜찮아", "지금 우리 함께 웃는 게", "가장 멋진 화음이니까",
            "지나간 걱정은 내려놓고", "새로운 이야기를 만들어", "다음 노랠 고르는 동안", "좋은 기억 하나 더 쌓여",
            "주말의 리듬 함께 타고", "하나 둘 셋 같이 노래해"
        ))
    )
    fun find(id: String) = songs.firstOrNull { it.id == id }
}

data class QueueEntry(val songId: String, val requester: SeatId, val singers: MutableList<Singer>, var confirmed: Boolean = false,
                      val subscription: Song? = null) {
    val song get() = subscription ?: requireNotNull(Catalog.find(songId))
}

/** Local session rules, independent of device signals and playback. Occupancy never edits participation. */
class Session {
    val seats = linkedSetOf<SeatId>()
    val queue = mutableListOf<QueueEntry>()
    var positionMs = 0
    var volume = 65
    var finished = false
    var viewSeat = SeatId.A
    val current get() = queue.firstOrNull()
    val ready get() = seats.isNotEmpty() && current?.confirmed == true && !finished

    fun recommendations(song: Song): MutableList<Singer> {
        var solo = 0
        return song.parts.map { p ->
            when {
                seats.isEmpty() -> p.recommended
                p.recommended == Singer.ALL && seats.size > 1 -> Singer.ALL
                else -> Singer.valueOf(seats.elementAt(solo++ % seats.size).name)
            }
        }.toMutableList()
    }

    fun participate(id: SeatId) {
        if (!seats.remove(id)) seats.add(id)
        queue.forEach { entry -> entry.singers.clear(); entry.singers.addAll(recommendations(entry.song)); entry.confirmed = entry.song.external && seats.isNotEmpty() }
        if (viewSeat !in seats && seats.isNotEmpty()) viewSeat = seats.first()
    }

    fun enqueue(song: Song, by: SeatId): Boolean {
        if (by !in seats || queue.size >= 12) return false
        queue.add(QueueEntry(song.id, by, recommendations(song), song.external, song.takeIf { it.external }))
        return true
    }

    fun cyclePart(index: Int) {
        val e = current ?: return
        if (index !in e.singers.indices || seats.isEmpty()) return
        val choices = seats.map { Singer.valueOf(it.name) } + if (seats.size > 1) listOf(Singer.ALL) else emptyList()
        e.singers[index] = choices[(choices.indexOf(e.singers[index]) + 1) % choices.size]
        e.confirmed = false
    }

    fun claimPart(index: Int, seat: SeatId): Boolean {
        val e = current ?: return false
        if (seat !in seats || index !in e.singers.indices) return false
        e.singers[index] = Singer.valueOf(seat.name); e.confirmed = false
        return true
    }

    fun recommend() { current?.let { it.singers.clear(); it.singers.addAll(recommendations(it.song)); it.confirmed = false } }
    fun confirm(): Boolean {
        val e = current ?: return false
        if (seats.isEmpty() || e.singers.size != e.song.parts.size) return false
        e.confirmed = e.singers.all { it == Singer.ALL || SeatId.valueOf(it.name) in seats }
        return e.confirmed
    }
    fun next() { if (queue.isNotEmpty()) queue.removeAt(0); positionMs = 0; finished = false }
    fun reset() { seats.clear(); queue.clear(); positionMs = 0; finished = false; viewSeat = SeatId.A }
    fun singerAt(ms: Int): Singer? = current?.let { it.singers.getOrNull(it.song.partAt(ms)) }
    fun isMine(singer: Singer?) = singer == Singer.ALL || singer?.name == viewSeat.name

    fun encode(): String = JSONObject().put("version", 1).put("seats", JSONArray(seats.map { it.name }))
        .put("queue", JSONArray(queue.map { e -> JSONObject().put("song", e.songId).put("by", e.requester.name)
            .put("parts", JSONArray(e.singers.map { it.name })).put("confirmed", e.confirmed)
            .also { j -> e.subscription?.let { s -> j.put("subscription", JSONObject().put("app", s.app).put("label", s.appLabel)
                .put("mediaId", s.mediaId).put("title", s.title).put("artist", s.mood)) } } }))
        .put("position", positionMs).put("volume", volume).put("finished", finished).put("viewSeat", viewSeat.name).toString()

    companion object {
        /** A corrupt/unknown file becomes a fresh session; never restores automatic playback. */
        fun decode(raw: String?): Session = try {
            if (raw == null) Session() else {
                val j = JSONObject(raw); require(j.getInt("version") == 1)
                Session().apply {
                    j.getJSONArray("seats").let { a -> for (i in 0 until a.length()) seats.add(SeatId.valueOf(a.getString(i))) }
                    j.getJSONArray("queue").let { a ->
                        require(a.length() <= 12)
                        for (i in 0 until a.length()) {
                            val q = a.getJSONObject(i)
                            val external = q.optJSONObject("subscription")?.let { e ->
                                val app = e.getString("app"); val id = e.getString("mediaId")
                                require(app.isNotBlank() && id.isNotBlank())
                                Song.subscription(app, e.getString("label"), id, e.getString("title"), e.optString("artist"))
                            }
                            val song = external ?: requireNotNull(Catalog.find(q.getString("song")))
                            require(song.id == q.getString("song"))
                            val singers = q.getJSONArray("parts").let { p -> (0 until p.length()).map { Singer.valueOf(p.getString(it)) }.toMutableList() }
                            require(singers.size == song.parts.size)
                            if (q.optBoolean("confirmed")) require(singers.all { it == Singer.ALL || SeatId.valueOf(it.name) in seats })
                            queue.add(QueueEntry(song.id, SeatId.valueOf(q.getString("by")), singers, q.optBoolean("confirmed"), external))
                        }
                    }
                    positionMs = j.optInt("position").coerceIn(0, if (current?.song?.external == true) Int.MAX_VALUE else current?.song?.durationMs ?: 0)
                    volume = j.optInt("volume", 65).coerceIn(0, 100)
                    finished = current != null && j.optBoolean("finished")
                    viewSeat = SeatId.valueOf(j.optString("viewSeat", "A"))
                }
            }
        } catch (_: Exception) { Session() }
    }
}

data class ParkState(val gear: String? = null, val uxAllowed: Boolean = false, val interrupted: Boolean = false) {
    val allowed get() = gear == "P" && uxAllowed && !interrupted
}
