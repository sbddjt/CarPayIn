package com.example.carpayin.vehicle

import android.content.Context
import android.util.Log

/**
 * VHAL 차량 데이터 매니저 — 리플렉션 방식
 *
 * android.car.jar 없이도 컴파일됩니다.
 * 실제 AAOS 기기에서는 리플렉션으로 Car API를 호출하고,
 * 에뮬레이터 / 미지원 환경에서는 Mock 값으로 자동 fallback합니다.
 *
 * 읽기 항목:
 *  - INFO_VIN           : 차량 VIN
 *  - IGNITION_STATE     : 시동 상태
 *  - GEAR_SELECTION     : 기어 상태
 *  - PERF_VEHICLE_SPEED : 차량 속도 (m/s)
 *  - EV_BATTERY_LEVEL   : 배터리 잔량 (선택)
 *  - PERF_ODOMETER      : 주행거리 (선택)
 */
object VehicleDataManager {

    private const val TAG = "VehicleDataManager"

    // ── VHAL Property ID 상수 ─────────────────────────────────────────────────
    private const val PROP_VIN      = 0x11100100
    private const val PROP_IGNITION = 0x11400409
    private const val PROP_GEAR     = 0x11400400
    private const val PROP_SPEED    = 0x11600207
    private const val PROP_BATTERY  = 0x11600303
    private const val PROP_ODOMETER = 0x11600204

    // VehicleGear 상수 (android.car.VehicleGear 값과 동일)
    private const val GEAR_PARK    = 4
    private const val GEAR_REVERSE = 8
    private const val GEAR_NEUTRAL = 1
    private const val GEAR_DRIVE   = 16

    // IGNITION_STATE 값
    private const val IGNITION_ON  = 2

    // Car.PROPERTY_SERVICE 상수
    private const val PROPERTY_SERVICE = "property"

    // ── 내부 상태 ─────────────────────────────────────────────────────────────
    private var carObj: Any? = null               // android.car.Car 인스턴스
    private var carPropManager: Any? = null       // CarPropertyManager 인스턴스
    private var ignitionCallback: Any? = null     // CarPropertyEventCallback 인스턴스

    /** IGNITION_STATE 변경 콜백 — 시동 ON/OFF 시 호출됩니다. */
    var onIgnitionChanged: ((ignitionOn: Boolean) -> Unit)? = null

    // ── 차량 상태 데이터 클래스 ───────────────────────────────────────────────

    data class VehicleState(
        val vin: String = "",
        val speedKph: Float = 0f,
        val gear: String = "—",
        val batteryPct: Float = -1f,
        val isParked: Boolean = false,
        val odometer: Float = 0f,
        val ignitionOn: Boolean = false
    )

    // ── 초기화 / 해제 ─────────────────────────────────────────────────────────

    fun init(context: Context) {
        try {
            val carClass = Class.forName("android.car.Car")
            val createCar = carClass.getMethod("createCar", Context::class.java)
            carObj = createCar.invoke(null, context)

            val getCarManager = carClass.getMethod("getCarManager", String::class.java)
            carPropManager = getCarManager.invoke(carObj, PROPERTY_SERVICE)

            registerIgnitionCallback()
            Log.d(TAG, "CarPropertyManager 초기화 성공 (리플렉션)")
        } catch (e: Exception) {
            Log.w(TAG, "Car API 초기화 실패 (에뮬레이터 fallback): ${e.message}")
            carObj = null
            carPropManager = null
        }
    }

    fun release() {
        try {
            if (ignitionCallback != null && carPropManager != null) {
                val unregister = carPropManager!!.javaClass
                    .getMethod("unregisterCallback", Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback"))
                unregister.invoke(carPropManager, ignitionCallback)
            }
        } catch (e: Exception) { /* 무시 */ }
        try {
            carObj?.javaClass?.getMethod("disconnect")?.invoke(carObj)
        } catch (e: Exception) { /* 무시 */ }
        carObj = null
        carPropManager = null
        ignitionCallback = null
    }

    // ── VIN 읽기 ─────────────────────────────────────────────────────────────

    fun readVin(context: Context): String {
        val pm = carPropManager
        if (pm != null) {
            try {
                val vin = getStringProperty(pm, PROP_VIN)
                if (!vin.isNullOrBlank()) {
                    Log.d(TAG, "VHAL VIN 읽기 성공: ${vin.take(8)}…")
                    return vin
                }
            } catch (e: Exception) {
                Log.w(TAG, "VHAL VIN 읽기 실패: ${e.message}")
            }
        }
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
        val pm = carPropManager ?: return simulatedState(context)
        return try {
            val speedMs  = getFloatProperty(pm, PROP_SPEED) ?: 0f
            val speedKph = speedMs * 3.6f

            val gearRaw = getIntProperty(pm, PROP_GEAR) ?: GEAR_PARK
            val gearStr = when (gearRaw) {
                GEAR_PARK    -> "P"
                GEAR_REVERSE -> "R"
                GEAR_NEUTRAL -> "N"
                GEAR_DRIVE   -> "D"
                else         -> "D"
            }

            val ignRaw     = getIntProperty(pm, PROP_IGNITION) ?: 0
            val ignitionOn = ignRaw >= IGNITION_ON

            val battery  = getFloatProperty(pm, PROP_BATTERY) ?: -1f
            val odometer = getFloatProperty(pm, PROP_ODOMETER) ?: 0f

            VehicleState(
                vin        = readVin(context),
                speedKph   = speedKph,
                gear       = gearStr,
                batteryPct = battery,
                isParked   = gearStr == "P" && speedKph < 1f,
                odometer   = odometer,
                ignitionOn = ignitionOn
            )
        } catch (e: Exception) {
            Log.e(TAG, "차량 데이터 읽기 실패: ${e.message}")
            simulatedState(context)
        }
    }

    fun isGearParked(context: Context): Boolean {
        val pm = carPropManager
        if (pm != null) {
            try {
                return (getIntProperty(pm, PROP_GEAR) ?: GEAR_PARK) == GEAR_PARK
            } catch (e: Exception) {
                Log.w(TAG, "기어 읽기 실패: ${e.message}")
            }
        }
        return true
    }

    fun getSpeedKph(): Float {
        val pm = carPropManager ?: return 0f
        return try {
            (getFloatProperty(pm, PROP_SPEED) ?: 0f) * 3.6f
        } catch (e: Exception) { 0f }
    }

    // ── IGNITION 콜백 등록 ────────────────────────────────────────────────────

    private fun registerIgnitionCallback() {
        val pm = carPropManager ?: return
        try {
            val callbackClass = Class.forName(
                "android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback"
            )
            // 동적 프록시로 CarPropertyEventCallback 구현
            ignitionCallback = java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { _, method, args ->
                when (method.name) {
                    "onChangeEvent" -> {
                        val propValue = args?.get(0) ?: return@newProxyInstance null
                        try {
                            val valueMethod = propValue.javaClass.getMethod("getValue")
                            val ignState = valueMethod.invoke(propValue) as? Int ?: return@newProxyInstance null
                            val isOn = ignState >= IGNITION_ON
                            Log.d(TAG, "IGNITION_STATE 변경: $ignState → ignitionOn=$isOn")
                            onIgnitionChanged?.invoke(isOn)
                        } catch (e: Exception) {
                            Log.w(TAG, "IGNITION 콜백 파싱 실패: ${e.message}")
                        }
                    }
                    "onErrorEvent" -> {
                        Log.e(TAG, "IGNITION 콜백 오류: propId=${args?.get(0)}")
                    }
                }
                null
            }

            // SENSOR_RATE_ONCHANGE = 0
            val registerMethod = pm.javaClass.getMethod(
      