package io.github.myshihi.pleosproject26.n02vehiclekaraoke

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Light
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sin

/** Optional actual Pleos lighting control. Save all originals before the first write, restore on every stop.
 * Uses supported writable areas only. No Virtual Console endpoint is called by the app.
 */
class AmbientLights(context: Context, private val changed: (String) -> Unit) {
    private val app = context.applicationContext
    private val store = app.getSharedPreferences("ambient_recovery", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var api: Light? = null
    private var bounds = emptyMap<SeatArea, AreaBounds<Int>>()
    private var switches = emptyMap<SeatArea, List<LightSwitch>>()
    private var originals = emptyList<Original>()
    private var colorAreas = emptyList<SeatArea>()
    private var generation = 0
    private var starting = false
    private var busy = false
    private var shutdown = false
    private var restart = false
    private var lastSent = 0L
    private var autoOriginal: Boolean? = null
    private var autoWritable = false
    private var dirty = false
    var ready = false; private set
    var enabled = false; private set
    var status = "차량 조명 지원 확인 전"; private set
    private data class Original(val area: SeatArea, val color: Int, val brightness: Int, val mode: LightSwitch)
    private fun say(value: String) { if (status != value) { status = value; changed(value) } }
    private fun writable(access: AccessType) = access == AccessType.READ_WRITE
    private fun readable(access: AccessType) = access == AccessType.READ || access == AccessType.READ_WRITE
    private fun post(token: Int, f: () -> Unit) { handler.post { if (token == generation) f() } }

    fun start() {
        if (shutdown) { restart = true; return }
        if (api != null || starting) return
        starting = true; val token = ++generation
        say("차량 조명 지원·권한 확인 중")
        try {
            val v = Vehicle(app).also { it.initialize() }; vehicle = v; api = v.getLight()
            if (store.contains("originals")) {
                restoreSaved(); restore { checkCapabilities(token) }; return
            }
            checkCapabilities(token)
        } catch (_: Exception) { starting = false; say("차량 조명 연결 불가 · 화면 조명만 사용") }
        catch (_: LinkageError) { starting = false; say("차량 조명 SDK 미지원 · 화면 조명만 사용") }
    }

    private fun checkCapabilities(token: Int) {
        val light = api ?: return
        var pending = 4
        fun done() { if (--pending == 0) {
            starting = false
            val areas = colorAreas.filter { it in bounds && switches[it]?.containsAll(listOf(LightSwitch.ON, LightSwitch.OFF)) == true }
            colorAreas = areas
            if (areas.isEmpty()) { say("차량 조명 쓰기 지원·권한 없음 · 화면 조명 사용"); return }
            if (autoOriginal == true && !autoWritable) { say("주행 모드 자동 조명을 먼저 해제해 주세요"); return }
            ready = true; say("차량 조명 연결 가능 · ${areas.size}개 지원 영역 · 사용 꺼짐")
        } }
        try {
            light.checkAmbientLightBrightnessColorCapabilities({ config, areas -> post(token) { colorAreas = if (writable(config.accessType)) areas else emptyList(); done() } }, { post(token) { done() } })
            light.checkAmbientLightBrightnessCapabilities({ config, areas -> post(token) { bounds = if (writable(config.accessType)) areas else emptyMap(); done() } }, { post(token) { done() } })
            light.checkAmbientLightSwitchCapabilities({ config, areas -> post(token) { switches = if (writable(config.accessType)) areas else emptyMap(); done() } }, { post(token) { done() } })
            light.checkAmbientColorAutoByDriveModeEnabledCapabilities({ config -> post(token) {
                autoWritable = writable(config.accessType)
                if (readable(config.accessType)) light.getAmbientColorAutoByDriveModeEnabled({ value -> post(token) { autoOriginal = value; done() } }, { post(token) { done() } }) else done()
            } }, { post(token) { done() } })
        } catch (_: Exception) { starting = false; say("차량 조명 지원 확인 실패 · 화면 조명 사용") }
        handler.postDelayed({ if (token == generation && starting) { starting = false; ready = false; say("차량 조명 응답 없음 · 화면 조명 사용") } }, 7000)
    }

    fun setEnabled(value: Boolean) {
        enabled = value && ready
        if (!enabled) { restore(); say(if (ready) "차량 조명 사용 꺼짐 · 원래 설정 복원" else status) }
        else say("차량 조명 사용 켜짐 · 공연 시작 대기")
    }

    private fun snapshot(done: () -> Unit) {
        val light = api ?: return
        busy = true
        val captured = mutableListOf<Original>()
        val token = generation
        fun read(index: Int) {
            if (index == colorAreas.size) {
                originals = captured.toList()
                try { persist() } catch (_: Exception) {
                    originals = emptyList(); busy = false; enabled = false
                    say("원래 조명값을 저장하지 못해 차량 제어를 시작하지 않았어요"); finishShutdown(); return
                }
                busy = false; done(); if (shutdown) restore(); return
            }
            val area = colorAreas[index]
            fun fail() { busy = false; enabled = false; say("원래 조명값을 읽지 못해 차량 제어를 시작하지 않았어요"); finishShutdown() }
            try {
                light.getAmbientLightBrightnessColor(area, { color -> post(token) {
                    if (color == null) { fail(); return@post }
                    light.getAmbientLightBrightness(area, { brightness -> post(token) {
                        if (brightness == null) { fail(); return@post }
                        light.getAmbientLightSwitch(area, { mode -> post(token) {
                            if (mode == null) { fail(); return@post }
                            captured.add(Original(area, color, brightness, mode)); read(index + 1)
                        } }, { post(token) { fail() } })
                    } }, { post(token) { fail() } })
                } }, { post(token) { fail() } })
            } catch (_: Exception) { fail() }
        }
        read(0)
    }

    private fun persist() {
        val j = JSONObject().put("areas", JSONArray(originals.map { o -> JSONObject()
            .put("area", JSONArray(o.area.values.map { it.name })).put("color",o.color)
            .put("brightness",o.brightness).put("mode",o.mode.name) }))
        if (autoWritable && autoOriginal == true) j.put("auto", true)
        // Commit before any vehicle write so recovery survives process death.
        if (!store.edit().putString("originals",j.toString()).commit()) throw IllegalStateException("Recovery snapshot not saved")
    }
    private fun restoreSaved() {
        try {
            val j = JSONObject(store.getString("originals", "{}")!!)
            originals = j.getJSONArray("areas").let { a -> (0 until a.length()).map { i ->
                val o = a.getJSONObject(i); val ids = o.getJSONArray("area")
                Original(SeatArea(*(0 until ids.length()).map { SeatValue.valueOf(ids.getString(it)) }.toTypedArray()),
                    o.getInt("color"),o.getInt("brightness"),LightSwitch.valueOf(o.getString("mode")))
            } }
            autoOriginal = if (j.has("auto")) j.getBoolean("auto") else null
            autoWritable = autoOriginal != null
            dirty = true
        } catch (_: Exception) { ready = false; say("저장된 조명 복원 정보를 확인할 수 없어요") }
    }

    fun sync(playing: Boolean, allowed: Boolean, color: Int, positionMs: Int) {
        if (!playing || !allowed || !enabled) { if (dirty && !busy) restore(); return }
        if (!ready || busy || shutdown) return
        if (originals.isEmpty()) { snapshot { if (enabled) sync(playing, allowed, color, positionMs) }; return }
        val now = SystemClock.elapsedRealtime()
        if (now - lastSent < 1250) return
        lastSent = now; busy = true; dirty = true
        val light = api ?: return
        val token = generation
        val palette = listOf(C.A,C.B,C.REAR_LEFT,C.REAR_RIGHT,C.LIME)
        val tint = if (color == C.LIME) palette[(positionMs / 2500) % palette.size] else color
        val fraction = .42 + .12 * sin(positionMs / 1800.0)
        var pending = originals.size * 3 + if (autoOriginal == true && autoWritable) 1 else 0
        var failed = false
        fun done(ok: Boolean) {
            failed = failed || !ok
            if (--pending == 0) {
                busy = false
                if (failed) { enabled = false; say("차량 조명 요청 실패 · 원래 설정 복원 중"); restore() }
                else if (!enabled || shutdown) restore()
                else light.getAmbientLightBrightnessColor(originals.first().area, { value -> post(token) {
                    say(if (value != null && (value and 0xffffff) == (tint and 0xffffff)) "차량 조명 연동 중 · 색상 수신 확인" else "차량 조명 요청 완료 · 실제 색상 확인 대기")
                } }, { post(token) { say("차량 조명 요청 완료 · 실제 색상 수신 실패") } })
            }
        }
        try {
            if (autoOriginal == true && autoWritable) light.setAmbientColorAutoByDriveModeEnabled(false, { post(token) { done(true) } }, { post(token) { done(false) } })
            originals.forEach { o ->
                val range = bounds.getValue(o.area)
                val brightness = (range.minValue + (range.maxValue - range.minValue) * fraction).toInt()
                light.setAmbientLightSwitch(o.area, LightSwitch.ON, { _, _ -> post(token) { done(true) } }, { post(token) { done(false) } })
                light.setAmbientLightBrightnessColor(o.area, tint, { _, _ -> post(token) { done(true) } }, { post(token) { done(false) } })
                light.setAmbientLightBrightness(o.area, brightness, { _, _ -> post(token) { done(true) } }, { post(token) { done(false) } })
            }
        } catch (_: Exception) { busy = false; enabled = false; restore(); say("차량 조명 제어 오류 · 복원 요청") }
    }

    private fun restore(after: (() -> Unit)? = null) {
        if (busy) return
        if (originals.isEmpty()) { after?.invoke(); finishShutdown(); return }
        val light = api ?: return
        busy = true; val token = generation
        var pending = originals.size * 3 + if (autoOriginal == true && autoWritable) 1 else 0
        var failed = false
        fun done(ok: Boolean) {
            failed = failed || !ok
            if (--pending == 0) {
                busy = false
                if (!failed) {
                    originals = emptyList(); dirty = false; store.edit().remove("originals").commit()
                    say("차량 조명 원래 설정 복원 완료")
                } else { enabled = false; ready = false; say("차량 조명 복원 실패 · 저장값을 보관했어요. 재연결 후 복원을 확인해 주세요") }
                if (!failed) after?.invoke() else starting = false
                finishShutdown()
            }
        }
        try {
            originals.forEach { o ->
                light.setAmbientLightBrightnessColor(o.area,o.color,{ _, _ -> post(token) { done(true) } },{ post(token) { done(false) } })
                light.setAmbientLightBrightness(o.area,o.brightness,{ _, _ -> post(token) { done(true) } },{ post(token) { done(false) } })
                light.setAmbientLightSwitch(o.area,o.mode,{ _, _ -> post(token) { done(true) } },{ post(token) { done(false) } })
            }
            if (autoOriginal == true && autoWritable) light.setAmbientColorAutoByDriveModeEnabled(true,{ post(token) { done(true) } },{ post(token) { done(false) } })
        } catch (_: Exception) { busy = false; say("차량 조명 복원 요청 실패 · 저장값 유지"); finishShutdown() }
    }
    fun stop() { enabled = false; shutdown = true; restore(); finishShutdown() }
    private fun finishShutdown() {
        if (!shutdown || busy) return
        generation++; try { vehicle?.release() } catch (_: Exception) { }
        vehicle = null; api = null; ready = false; starting = false; shutdown = false
        if (restart) { restart = false; start() }
    }
}
