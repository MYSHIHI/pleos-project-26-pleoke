package io.github.myshihi.pleosproject26.n02vehiclekaraoke

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/** One real playback clock drives lyrics, progress and the on-screen stage. No background playback. */
class KaraokePlayer(context: Context, private val allowed: () -> Boolean,
                    private val change: (String) -> Unit, private val complete: () -> Unit) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs)
        .setWillPauseWhenDucked(true).setOnAudioFocusChangeListener({ value ->
            if (value != AudioManager.AUDIOFOCUS_GAIN) { pause(); change("통화·차량 안내로 멈췄어요. 준비되면 직접 이어 불러 주세요.") }
        }, handler).build()
    private val session = MediaSession(app, "Pleoke").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() { if (allowed()) play() }
            override fun onPause() { pause(); change("공연을 잠시 멈췄어요") }
            override fun onStop() = onPause()
            override fun onSeekTo(pos: Long) { if (allowed()) seek(pos.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()) }
        }, handler)
    }
    private var media: MediaPlayer? = null
    private var song: Song? = null
    private var offset = 0
    private var volume = .65f
    var playing = false; private set
    val position: Int get() = try { media?.currentPosition ?: offset } catch (_: IllegalStateException) { offset }
    val duration: Int get() = song?.durationMs ?: 0

    fun load(value: Song?, at: Int) {
        pause(); media?.release(); media = null; song = value
        offset = at.coerceIn(0, value?.durationMs ?: 0)
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, value?.title ?: "플레오케")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "PLEOKE ORIGINAL")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, value?.durationMs?.toLong() ?: 0).build())
        publish()
    }

    fun play() {
        if (!allowed()) return
        val track = song ?: return
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            change("오디오를 사용할 수 없어요. 잠시 후 다시 눌러 주세요."); return
        }
        try {
            if (media == null) {
                val resource = when (track.id) { "starlight" -> R.raw.starlight; "weekend" -> R.raw.weekend; else -> R.raw.campfire }
                media = MediaPlayer().also { p ->
                    p.setAudioAttributes(attrs)
                    app.resources.openRawResourceFd(resource).use { p.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                    p.prepare()
                    p.setVolume(volume, volume)
                    p.seekTo(offset.coerceAtMost(p.duration - 1).toLong(), MediaPlayer.SEEK_CLOSEST)
                    p.setOnCompletionListener {
                        playing = false; offset = track.durationMs
                        audio.abandonAudioFocusRequest(focus); publish(); complete()
                    }
                    p.setOnErrorListener { _, _, _ ->
                        pause(); media?.release(); media = null; change("반주를 열지 못했어요. 다시 시도해 주세요."); true
                    }
                }
            }
            media?.start(); playing = true; publish(); change("함께 부르는 중 · 현재 파트를 확인해 주세요")
        } catch (_: Exception) {
            pause(); media?.release(); media = null; change("반주 재생에 실패했어요. 다시 시도해 주세요.")
        }
    }
    fun pause() {
        offset = position
        try { if (playing) media?.pause() } catch (_: IllegalStateException) { }
        playing = false; audio.abandonAudioFocusRequest(focus); publish()
    }
    fun seek(ms: Int) {
        offset = ms.coerceIn(0, ((song?.durationMs ?: 1) - 1).coerceAtLeast(0))
        try { media?.seekTo(offset.toLong(), MediaPlayer.SEEK_CLOSEST) } catch (_: IllegalStateException) { }
        publish()
    }
    fun setVolume(value: Int) { volume = value.coerceIn(0, 100) / 100f; media?.setVolume(volume, volume) }
    fun publish() {
        session.isActive = song != null
        val actions = PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or
            if (allowed()) PlaybackState.ACTION_PLAY or PlaybackState.ACTION_SEEK_TO else 0L
        session.setPlaybackState(PlaybackState.Builder().setActions(actions)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, position.toLong(), if (playing) 1f else 0f).build())
    }
    fun release() { pause(); media?.release(); media = null; session.release() }
}
