package com.example.carpayin

import android.car.Car
import android.car.VehicleGear
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log

/**
 * VHAL 차량 데이터 매니저
 *
 * 읽기 항목:
 *  - INFO_VIN          : 차량 VIN (최초 등록 시 mTLS 인증에 사용)
 *  - IGNITION_STATE    : 시동 상태 (백그라운드 서비스 트리거)
 *  - GEAR_SELECTION    : 기어 상태 (카드 등록 진입 조건: P 여부)
 *  - PERF_VEHICLE_SPEED: 차량 속도 (지오펜스 동적 반경 계산)
 *  - EV_BATTERY_LEVEL  : 배터리 잔량 (선택)
 *  - PERF_ODOMETER     : 주행거리 (선택)
 */
object VehicleDataManager {

    private const val TAG = "VehicleDataManager"

    // ── VHAL Property ID 상수 (android.car.VehiclePropertyIds 기준) ──────────
    private const val PROP_VIN      = 0x11100100   // INFO_VIN           (String)
    private const val PROP_IGNITION = 0x11400409   // IGNITION_STATE     (Int)
    private const val PROP_GEAR     = 0x11400400   // GEAR_SELECTION     (Int)
    private const val PROP_SPEED    = 0x11600207   // PERF_VEHICLE_SPEED (Float, m/s)
    private const val PROP_BATTERY  = 0x11600303   // EV_BATTERY_LEVEL   (Float, %)
    private const val PROP_ODOMETER = 0x11600204   // PERF_ODOMETER      (Float, km)

    // IGNITION_STATE 값 (VehicleIgnitionState)
    private const val IGNITION_OFF   = 0
    private const val IGNITION_ACC   = 1
    private const val IGNITION_ON    = 2
    private const val IGNITION_START = 3

    // ── 상태 데이터 클래스 ────────────────────────────────────────────────────

    data class VehicleState(
        val vin: String = "",             // VIN (17자리)
        val speedKph: Float = 0f,         // 속도 (km/h)
        val gear: String = "—",           // P / R / N / D
        val batteryPct: Float = -1f,      // -1 = 비전기차
        val isParked: Boolean = false,    // gear == P && speed < 1
        val odometer: Float = 0f,         // 주행거리 (km)
        val ignitionOn: Boolean = false   // 시동 켜짐 여부
    )

    private var carPropertyManager: CarPropertyManager? = null
    private var car: Car? = null

    /** IGNITION_STATE 변경 콜백 — 시동 ON/OFF 시 호출됩니다. */
    var onIgnitionChanged: ((ignitionOn: Boolean) -> Unit)? = null

    // ── 초기화 / 해제 ─────────────────────────────────────────────────────────

    fun init(context: Context) {
        try {
            car = Car.createCar(context)
            carPropertyManager = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            registerIgnitionCallback()
            Log.d(TAG, "CarPropertyManager 초기화 성공")
        } catch (e: Exception) {
            Log.w(TAG, "CarPropertyManager 초기화 실패 (에뮬레이터): ${e.message}")
        }
    }

    fun release() {
        try {
            carPropertyManager?.unregisterCallback(ignitionCallback)
        } catch (e: Exception) {}
        try { car?.disconnect() } catch (e: Exception) {}
        car = null
        carPropertyManager = null
    }

    // ── VIN 읽기 ─────────────────────────────────────────────────────────────

    /**
     * VHAL의 INFO_VIN 속성에서 VIN을 읽어옵니다.
     * 에뮬레이터에서 접근 불가 시 SharedPreferences 저장 값(또는 Mock)을 반환합니다.
     */
    fun readVin(context: Context): String {
        val pm = carPropertyManager
        if (pm != null) {
            try {
                val value: CarPropertyValue<*>? = pm.getProperty<Any>(PROP_VIN, 0)
                val vin = value?.value as? String
                if (!vin.isNullOrBlank()) {
                    Log.d(TAG, "VHAL VIN 읽기 성공: ${vin.take(8)}…")
                    return vin
                }
            } catch (e: Exception) {
                Log.w(TAG, "VHAL VIN 읽기 실패: ${e.message}")
            }
        }
        // Fallback: SharedPreferences 저장 VIN (또는 Mock 생성)
        return readOrCreateVinFromPrefs(context)
    }

    private fun readOrCreateVinFromPrefs(context: Context): String {
        val prefs = context.getSharedPreferences("carpayin", Context.MODE_PRIVATE)
        return prefs.getString("vin", null) ?: run {
            val random = (100000000000L..999999999999L).random()
            val newVin = "KMHXX$random"
            prefs.edit().putString("vin", newVin).apply()
            Log.d(TAG, "VIN 신규 생성 (Mock): $newVin")
            newVin
        }
    }

    // ── 전체 차량 상태 읽기 ───────────────────────────────────────────────────

    fun getState(context: Context): VehicleState {
        val pm = carPropertyManager ?: return simulatedState(context)

        return try {
            val speedMs  = safeGet<Float>(pm, PROP_SPEED) ?: 0f
            val speedKph = speedMs * 3.6f

            val gearRaw = safeGet<Int>(pm, PROP_GEAR) ?: VehicleGear.GEAR_PARK
            val gearStr = when (gearRaw) {
                VehicleGear.GEAR_PARK    -> "P"
                VehicleGear.GEAR_REVERSE -> "R"
                VehicleGear.GEAR_NEUTRAL -> "N"
                VehicleGear.GEAR_DRIVE   -> "D"
                else                     -> "D"
            }

            val ignRaw    = safeGet<Int>(pm, PROP_IGNITION) ?: IGNITION_OFF
            val ignitionOn = ignRaw >= IGNITION_ON

            val battery   = safeGet<Float>(pm, PROP_BATTERY) ?: -1f
            val odo       = safeGet<Float>(pm, PROP_ODOMETER) ?: 0f

            VehicleState(
                vin        = readVin(context),
                speedKph   = speedKph,
                gear       = gearStr,
                batteryPct = battery,
                isParked   = gearStr == "P" && speedKph < 1f,
                odometer   = odo,
                ignitionOn = ignitionOn
            )
        } catch (e: Exception) {
            Log.e(TAG, "차량 데이터 읽기 실패: ${e.message}")
            simulatedState(context)
        }
    }

    /** 기어가 P인지 확인 (카드 등록 진입 조건) */
    fun isGearParked(context: Context): Boolean {
        val pm = carPropertyManager
        if (pm != null) {
            try {
                val gearRaw = safeGet<Int>(pm, PROP_GEAR)
                return gearRaw == VehicleGear.GEAR_PARK
            } catch (e: Exception) {
                Log.w(TAG, "기어 읽기 실패: ${e.message}")
            }
        }
        return true  // 에뮬레이터: 항상 P로 간주
    }

    /** 현재 속도(km/h) 반환 — 지오펜스 동적 반경 계산에 사용 */
    fun getSpeedKph(): Float {
        val pm = carPropertyManager ?: return 0f
        return try {
            (safeGet<Float>(pm, PROP_SPEED) ?: 0f) * 3.6f
        } catch (e: Exception) { 0f }
    }

    // ── IGNITION_STATE 변경 콜백 등록 ────────────────────────────────────────

    private val ignitionCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val ignState = value.value as? Int ?: return
            val isOn = ignState >= IGNITION_ON
            Log.d(TAG, "IGNITION_STATE 변경: $ignState → ignitionOn=$isOn")
            onIgnitionChanged?.invoke(isOn)
        }

        override fun onErrorEvent(propId: Int, zone: Int) {
            Log.e(TAG, "IGNITION_STATE 콜백 오류: propId=$propId")
        }
    }

    private fun registerIgnitionCallback() {
        try {
            carPropertyManager?.registerCallback(
                ignitionCallback,
                PROP_IGNITION,
                CarPropertyManager.SENSOR_RATE_ONCHANGE
            )
            Log.d(TAG, "IGNITION_STATE 콜백 등록 완료")
        } catch (e: Exception) {
            Log.w(TAG, "IGNITION_STATE 콜백 등록 실패: ${e.message}")
        }
    }

    // ── 내부 유틸 ─────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun <T> safeGet(pm: CarPropertyManager, propId: Int): T? {
        return try {
            pm.getProperty<Any>(propId, 0)?.value as? T
        } catch (e: Exception) { null }
    }

    /**
     * 에뮬레이터 / VHAL 미지원 환경 시뮬레이션 값
     */
    private fun simulatedState(context: Context): VehicleState {
        return VehicleState(
            vin        = readOrCreateVinFromPrefs(context),
            speedKph   = 0f,
            gear       = "P",
            batteryPct = 78f,
            isParked   = true,
            odometer   = 12450f,
            ignitionOn = true
        )
    }
}
