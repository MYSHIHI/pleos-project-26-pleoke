package io.github.myshihi.pleosproject26.n02vehiclekaraoke

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.media.MediaBrowserService
import android.service.notification.NotificationListenerService
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

/** The vehicle app owns its subscription, login and audio. No credentials, audio files or lyrics are copied. */
data class SubscriptionApp(val pkg: String, val label: String, val service: String?) {
    companion object {
        val known = mapOf("com.iloen.melon" to "멜론", "com.ktmusic.geniemusic" to "지니뮤직")
        fun discover(context: Context): List<SubscriptionApp> {
            val pm = context.packageManager
            val services = pm.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE), 0)
                .mapNotNull { it.serviceInfo }.filter { it.exported && it.packageName != context.packageName && it.packageName != "com.android.bluetooth" }
                .associateBy { it.packageName }
            val installed = known.keys.filter { try { pm.getApplicationInfo(it, 0); true } catch (_: Exception) { false } }
            return (services.keys + installed).distinct().map { pkg ->
                val label = known[pkg] ?: try { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() } catch (_: Exception) { pkg }
                SubscriptionApp(pkg, label, services[pkg]?.name)
            }.sortedBy { if (it.label.contains("멜론") || it.label.contains("지니")) 0 else 1 }
        }
    }
}

/** Optional Android media-session access. Notification contents are never read. */
class SessionAccessService : NotificationListenerService()
object SessionAccess {
    fun component(context: Context) = ComponentName(context, SessionAccessService::class.java)
    fun granted(context: Context) = context.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(component(context))
    fun find(context: Context, pkg: String): MediaControllerCompat? = try {
        context.getSystemService(MediaSessionManager::class.java).getActiveSessions(component(context))
            .firstOrNull { it.packageName == pkg }?.let { MediaControllerCompat(context, MediaSessionCompat.Token.fromToken(it.sessionToken)) }
    } catch (_: Exception) { null }
}

/** Browser search + controllable playback only. Never fire an unobservable play intent for a park-only app. */
class SubscriptionMusic(private val context: Context, private val allowed: () -> Boolean,
                        private val changed: (String) -> Unit, private val completed: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    var apps = emptyList<SubscriptionApp>(); private set
    var selected: SubscriptionApp? = null; private set
    var status = "차량 구독 음악 앱을 선택해 주세요"; private set
    private var browser: MediaBrowserCompat? = null
    private var controller: MediaControllerCompat? = null
    private var generation = 0
    private var queryVersion = 0
    private var song: Song? = null
    private var managed = false
    private var started = false
    private var observed = false
    private var offset = 0
    private var pendingSeek = false
    private var lastPlaybackState = -1
    private val callback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(s: PlaybackStateCompat?) {
            if (!managed || s == null) return
            if (s.state == PlaybackStateCompat.STATE_PLAYING && (!allowed() || !started)) {
                controller?.transportControls?.pause(); return
            }
            if (s.state == PlaybackStateCompat.STATE_PLAYING) {
                observed = true
                if (pendingSeek && offset > 0 && s.actions and PlaybackStateCompat.ACTION_SEEK_TO != 0L) {
                    pendingSeek = false; controller?.transportControls?.seekTo(offset.toLong())
                }
            }
            if (s.state == PlaybackStateCompat.STATE_STOPPED && observed) {
                offset = position; started = false; observed = false; completed(); return
            }
            if (lastPlaybackState != s.state) {
                lastPlaybackState = s.state
                changed(when (s.state) {
                    PlaybackStateCompat.STATE_PLAYING -> "${selected?.label} 차량 구독으로 재생 중"
                    PlaybackStateCompat.STATE_ERROR -> "음악 앱 재생 실패 · ${s.errorMessage ?: "앱에서 구독 상태를 확인해 주세요"}"
                    else -> "${selected?.label} · 재생 상태 확인 중 또는 일시정지"
                })
            }
        }
        override fun onSessionDestroyed() { disconnect(); changed("차량 음악 앱 연결이 끊겼어요. 다시 연결해 주세요.") }
    }
    val playing get() = managed && started && controller?.playbackState?.state == PlaybackStateCompat.STATE_PLAYING
    val position: Int get() {
        if (!managed) return offset
        val s = controller?.playbackState ?: return offset
        if (!started && s.state == PlaybackStateCompat.STATE_PLAYING) return offset
        val value = s.position + if (playing) ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime).coerceAtLeast(0) * s.playbackSpeed).toLong() else 0
        return value.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }
    val duration get() = if (!managed) 0 else (controller?.metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0)
        .coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    val canSeek get() = managed && (controller?.playbackState?.actions ?: 0) and PlaybackStateCompat.ACTION_SEEK_TO != 0L

    fun refresh() { apps = SubscriptionApp.discover(context) }
    fun disconnect() {
        generation++; queryVersion++; pause()
        try { controller?.unregisterCallback(callback) } catch (_: Exception) { }
        try { browser?.disconnect() } catch (_: Exception) { }
        controller = null; browser = null; selected = null; managed = false; started = false; observed = false
    }
    private fun attach(c: MediaControllerCompat) {
        controller = c; c.registerCallback(callback, handler)
    }
    fun connect(app: SubscriptionApp, done: (Boolean) -> Unit) {
        if (selected?.pkg == app.pkg && controller != null) { done(true); return }
        disconnect(); selected = app; val token = generation
        status = "${app.label} 연결 확인 중"
        var answered = false
        fun finish(ok: Boolean) {
            if (answered || token != generation) return
            answered = true
            status = if (ok) "${app.label} · 차량 구독 계정으로 연결" else "${app.label} 외부 제어 미확인 · 음악 앱에서 구독·제어 권한을 확인해 주세요"
            done(ok)
        }
        fun fallback() {
            if (token != generation) return
            SessionAccess.find(context, app.pkg)?.let { attach(it); finish(true) } ?: finish(false)
        }
        val service = app.service ?: return fallback()
        val b = MediaBrowserCompat(context, ComponentName(app.pkg, service), object : MediaBrowserCompat.ConnectionCallback() {
            override fun onConnected() {
                if (token != generation || answered) return
                try { attach(MediaControllerCompat(context, requireNotNull(browser).sessionToken)); finish(true) }
                catch (_: Exception) { fallback() }
            }
            override fun onConnectionFailed() { fallback() }
            override fun onConnectionSuspended() { if (token == generation) { disconnect(); changed("차량 음악 앱 연결이 중단됐어요") } }
        }, null)
        browser = b
        try { b.connect() } catch (_: Exception) { fallback() }
        handler.postDelayed({ if (token == generation && !answered) { try { b.disconnect() } catch (_: Exception) { }; browser = null; fallback() } }, 6_000)
    }

    fun search(query: String, done: (List<Song>, String) -> Unit) {
        val q = query.trim().take(120); val app = selected
        if (q.isEmpty() || app == null) { done(emptyList(), "음악 앱을 연결하고 곡 제목·가수를 입력해 주세요"); return }
        val b = browser
        if (b?.isConnected != true) {
            done(emptyList(), "이 음악 앱은 곡 목록 검색을 공개하지 않았어요. 음악 앱에서 곡을 선택한 뒤 현재 곡을 가져올 수 있어요."); return
        }
        val token = generation; val version = ++queryVersion; var answered = false
        fun answer(found: List<Song>, message: String) {
            if (!answered && token == generation && version == queryVersion) { answered = true; done(found, message) }
        }
        try { b.search(q, Bundle(), object : MediaBrowserCompat.SearchCallback() {
            override fun onSearchResult(query: String, extras: Bundle?, items: List<MediaBrowserCompat.MediaItem>) {
                val results = items.filter { it.isPlayable && !it.mediaId.isNullOrBlank() }.take(30).map { item ->
                    Song.subscription(app.pkg, app.label, item.mediaId!!, item.description.title?.toString().orEmpty().ifBlank { "제목 없음" }, item.description.subtitle?.toString().orEmpty())
                }
                answer(results, if (results.isEmpty()) "곡을 찾지 못했어요. 음악 앱에서 검색 지원·구독 상태를 확인해 주세요." else "${app.label} 실제 검색 결과 ${results.size}곡")
            }
            override fun onError(query: String, extras: Bundle?) { answer(emptyList(), "이 음악 앱이 외부 검색을 허용하지 않아요. 음악 앱에서 곡을 골라 주세요.") }
        }) } catch (_: Exception) { answer(emptyList(), "곡 검색을 요청하지 못했어요") }
        handler.postDelayed({ answer(emptyList(), "음악 앱 검색 응답이 없어요. 앱에서 구독 상태를 확인해 주세요.") }, 7_000)
    }

    fun currentSong(): Song? {
        val app = selected ?: return null; val meta = controller?.metadata ?: return null
        val id = meta.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)?.takeIf { it.isNotBlank() } ?: return null
        val title = meta.getString(MediaMetadataCompat.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return null
        return Song.subscription(app.pkg, app.label, id, title, meta.getString(MediaMetadataCompat.METADATA_KEY_ARTIST).orEmpty())
    }
    fun load(value: Song?, at: Int) { pause(); song = value; offset = at.coerceAtLeast(0); managed = false; observed = false; lastPlaybackState = -1 }
    fun play() {
        if (!allowed()) return
        val target = song ?: return
        val app = apps.firstOrNull { it.pkg == target.app } ?: run { changed("이 차량에 ${target.appLabel} 앱이 없어요. 앱 마켓에서 설치·구독을 확인해 주세요."); return }
        val expected = target.id
        connect(app) { ok ->
            if (!allowed() || song?.id != expected) return@connect
            if (!ok) { changed(status); return@connect }
            val c = controller ?: return@connect
            val actions = c.playbackState?.actions ?: 0L
            if (actions and PlaybackStateCompat.ACTION_PAUSE == 0L) { changed("차량 제한 시 중단을 제어할 수 없어 플레오케에서 재생을 시작하지 않았어요."); return@connect }
            try {
                if (managed && observed) { started = true; c.transportControls.play() }
                else {
                    if (actions and PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID == 0L) {
                        changed("이 음악 앱은 곡 ID 재생을 지원하지 않아요. 차량 음악 앱에서 재생해 주세요."); return@connect
                    }
                    managed = true; started = true; observed = false; pendingSeek = offset > 0
                    c.transportControls.playFromMediaId(target.mediaId, Bundle())
                }
                changed("${app.label}에 재생을 요청했어요. 반주·시간 가사는 음악 앱의 별도 제공이 필요해요.")
            } catch (_: Exception) { started = false; changed("차량 음악 앱에 재생 요청을 전달하지 못했어요") }
        }
    }
    fun pause() {
        if (managed) { offset = position; started = false; try { controller?.transportControls?.pause() } catch (_: Exception) { } }
    }
    fun seek(ms: Int) { if (allowed() && canSeek) { offset = ms; controller?.transportControls?.seekTo(ms.toLong()) } }
}

/** Keeps local karaoke and external subscription playback separate; no invented lyrics for external songs. */
class Playback(private val local: KaraokePlayer, val subscription: SubscriptionMusic) {
    private var external = false
    val playing get() = if (external) subscription.playing else local.playing
    val position get() = if (external) subscription.position else local.position
    val duration get() = if (external) subscription.duration else local.duration
    val canSeek get() = if (external) subscription.canSeek else true
    fun load(song: Song?, at: Int) {
        pause(); external = song?.external == true
        if (external) { local.load(null, 0); subscription.load(song, at) }
        else { subscription.load(null, 0); local.load(song, at) }
    }
    fun play() { if (external) subscription.play() else local.play() }
    fun pause() { local.pause(); subscription.pause() }
    fun seek(ms: Int) { if (external) subscription.seek(ms) else local.seek(ms) }
    fun setVolume(v: Int) { local.setVolume(v) }
    fun publish() { local.publish() }
    fun release() { local.release(); subscription.disconnect() }
}
