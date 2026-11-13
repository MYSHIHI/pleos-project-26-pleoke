package io.github.myshihi.pleosproject26.n02vehiclekaraoke

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Actual read-only SDK inputs. No sample gear or bypass; absent/stale/failed safety inputs block playback. */
class VehicleLink(private val context: Context, private val park: (String?, Boolean, String) -> Unit,
                  private val seat: (SeatId, Boolean?, String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var ux: CarUxRestrictionsManager? = null
    private var gearListener: GearSelectionListener? = null
    private val listeners = mutableMapOf<SeatId, SeatOccupancyListener>()
    private var gear: String? = null
    private var gearAt = 0L
    private var uxAllowed = false
    private var gearVersion = 0
    private fun area(id: SeatId) = SeatArea(SeatValue.valueOf(id.sdkArea))
    private fun post(token: Int, f: () -> Unit) { handler.post { if (token == generation) f() } }
    private fun publish() {
        val fresh = SystemClock.elapsedRealtime() - gearAt < 12_000
        val g = gear.takeIf { fresh }
        park(g, uxAllowed, when {
            g == null -> "차량 기어 확인 중 · 공연 대기"
            g != "P" -> "기어 $g · 주차 후 이용해 주세요"
            !uxAllowed -> "차량 조작 제한 · 공연 대기"
            else -> "P · 주차 확인됨"
        })
    }
    private fun applyUx(r: CarUxRestrictions?) {
        uxAllowed = r != null && !r.isRequiresDistractionOptimization && r.activeRestrictions == CarUxRestrictions.UX_RESTRICTIONS_BASELINE
        publish()
    }
    private fun showGear(g: VehicleGear?) {
        gear = when (g) {
            VehicleGear.GEAR_PARK -> "P"; VehicleGear.GEAR_REVERSE -> "R"; VehicleGear.GEAR_NEUTRAL -> "N"
            VehicleGear.GEAR_UNKNOWN, null -> null; else -> "D"
        }
        gearAt = SystemClock.elapsedRealtime(); publish()
    }

    fun start() {
        stop(); val token = generation
        publish()
        try {
            car = Car.createCar(context, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                post(token) {
                    if (!ready) { uxAllowed = false; ux = null; publish() }
                    else try {
                        ux = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                        ux?.registerListener { r -> post(token) { applyUx(r) } }
                        applyUx(ux?.currentCarUxRestrictions)
                    } catch (_: Exception) { applyUx(null) }
                }
            }
        } catch (_: Exception) { applyUx(null) } catch (_: LinkageError) { applyUx(null) }
        try {
            val v = Vehicle(context.applicationContext).also { it.initialize() }; vehicle = v
            val l = object : GearSelectionListener {
                override fun onSelected(gear: VehicleGear?) = post(token) { gearVersion++; showGear(gear) }
                override fun onFailed(e: Exception) = post(token) { gearVersion++; showGear(null) }
            }
            gearListener = l; v.getGear().registerGearSelection(l)
            startSeats(v, token)
        } catch (_: Exception) { showGear(null) } catch (_: LinkageError) { showGear(null) }

        handler.post(object : Runnable {
            override fun run() {
                if (token != generation) return
                val version = gearVersion
                try { vehicle?.getGear()?.getCurrentGearSelectionState({ g -> post(token) { if (version == gearVersion) showGear(g) } },
                    { post(token) { if (version == gearVersion) showGear(null) } }) } catch (_: Exception) { showGear(null) }
                try { applyUx(ux?.currentCarUxRestrictions) } catch (_: Exception) { applyUx(null) }
                publish(); handler.postDelayed(this, 4_000)
            }
        })
        handler.postDelayed({ if (token == generation) SeatId.entries.filter { it !in listeners }.forEach { seat(it, null, "점유 미수신 · 직접 확인") } }, 12_000)
    }

    private fun startSeats(v: Vehicle, token: Int) {
        val s = v.getSeat()
        s.checkSeatOccupancyCapabilities(onComplete = { config, areas -> post(token) {
            SeatId.entries.forEach { id ->
                if ((config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE) && area(id) in areas) {
                    var version = 0
                    val callback = object : SeatOccupancyListener {
                        override fun onSeatOccupancyUpdated(occupancy: Boolean?) = post(token) { version++; seat(id, occupancy, "Pleos Seat · 실제 수신") }
                        override fun onFailed(e: Exception) = post(token) { version++; seat(id, null, "점유 구독 실패 · 직접 확인") }
                    }
                    try {
                        s.registerSeatOccupancy(area(id), callback); listeners[id] = callback
                        val requestVersion = version
                        s.getCurrentSeatOccupancy(area(id), { value -> post(token) { if (version == requestVersion) seat(id, value, "Pleos Seat · 실제 수신") } },
                            { post(token) { if (version == requestVersion) seat(id, null, "점유 조회 실패 · 직접 확인") } })
                    } catch (_: Exception) { seat(id, null, "점유 미수신 · 직접 확인") }
                } else seat(id, null, "점유 미지원 · 직접 확인")
            }
        } }, onFailed = { post(token) { SeatId.entries.forEach { seat(it, null, "점유 지원 확인 실패 · 직접 확인") } } })
    }

    fun stop() {
        generation++; handler.removeCallbacksAndMessages(null)
        try { ux?.unregisterListener() } catch (_: Exception) { }
        try { car?.disconnect() } catch (_: Exception) { }
        vehicle?.let { v ->
            gearListener?.let { try { v.getGear().unregisterGearSelection(it) } catch (_: Exception) { } }
            listeners.forEach { (id, l) -> try { v.getSeat().unregisterSeatOccupancy(area(id), l) } catch (_: Exception) { } }
            try { v.release() } catch (_: Exception) { }
        }
        listeners.clear(); gearListener = null; vehicle = null; ux = null; car = null
        gear = null; gearAt = 0; uxAllowed = false; gearVersion = 0
    }
}
