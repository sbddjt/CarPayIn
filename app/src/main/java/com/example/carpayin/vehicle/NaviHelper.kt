package com.example.carpayin.vehicle

import android.content.Context
import android.util.Log

/**
 * Pleos NaviHelper SDK 래퍼
 *
 * Pleos Connect 에뮬레이터의 NaviHelper SDK를 통해
 * AAOS 내비게이션에 목적지를 설정하고 경로 안내를 시작합니다.
 *
 * ─ SDK 초기화 흐름 ─────────────────────────────────────────────────────────
 *   1. NaviHelper.init(context)            — Activity.onCreate 또는 Service.onCreate
 *   2. NaviHelper.setDestination(...)      — 주차장 탭 시 호출
 *   3. NaviHelper.setOnRouteStartedListener — 경로 안내 시작 콜백 수신
 *   4. NaviHelper.release()                — Activity/Service 종료 시
 *
 * ─ Pleos SDK 실제 클래스 경로 ─────────────────────────────────────────────
 *   ai.pleos.playground.navi.NaviHelper
 *   (build.gradle: implementation("ai.pleos.playground:Vehicle:2.0.3"))
 *
 * ─ SDK 미연동 시 동작 ────────────────────────────────────────────────────
 *   에뮬레이터에 NaviHelper SDK가 없거나 초기화 실패 시
 *   onNavigationStarted 콜백은 호출되지 않고 로그만 출력합니다.
 */
object NaviHelper {

    private const val TAG = "NaviHelper"

    // Pleos NaviHelper SDK 실제 클래스 경로
    private const val NAVI_CLASS = "ai.pleos.playground.navi.NaviHelper"

    private var naviInstance: Any? = null
    private var isInitialized = false

    /** 목적지 설정 완료 → 내비게이션 시작 콜백 */
    var onNavigationStarted: ((lotName: String, lat: Double, lng: Double) -> Unit)? = null

    /** 경로 안내 종료 콜백 (목적지 도착 또는 안내 취소) */
    var onNavigationEnded: (() -> Unit)? = null

    // ── 초기화 ───────────────────────────────────────────────────────────────

    /**
     * NaviHelper SDK 초기화.
     * Pleos Connect 에뮬레이터 환경에서만 실제 SDK가 동작합니다.
     */
    fun init(context: Context) {
        if (isInitialized) return
        try {
            val clazz    = Class.forName(NAVI_CLASS)
            val getInstance = clazz.getMethod("getInstance", Context::class.java)
            naviInstance = getInstance.invoke(null, context)
            registerRouteCallback()
            isInitialized = true
            Log.d(TAG, "NaviHelper SDK 초기화 성공")
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "NaviHelper SDK 없음 — Pleos Connect 에뮬레이터에서만 동작합니다")
        } catch (e: Exception) {
            Log.w(TAG, "NaviHelper 초기화 실패: ${e.message}")
        }
    }

    // ── 목적지 설정 & 내비게이션 시작 ────────────────────────────────────────

    /**
     * 제휴 주차장을 내비게이션 목적지로 설정합니다.
     *
     * @param lat      목적지 위도
     * @param lng      목적지 경도
     * @param lotName  주차장 이름 (내비 화면에 표시될 POI 이름)
     * @param lotId    주차장 ID (사전 알림 연동용)
     *
     * Pleos NaviHelper SDK 내부 동작:
     *   1. setDestination() → Pleos 내비게이션 엔진에 목적지 전달
     *   2. startNavigation() → 경로 계산 + TBT(Turn-By-Turn) 안내 시작
     *   3. 인포테인먼트 화면에 지도 + 경로가 자동으로 표시됨
     */
    fun setDestination(
        context: Context,
        lat: Double,
        lng: Double,
        lotName: String,
        lotId: String
    ) {
        Log.d(TAG, "목적지 설정: $lotName ($lat, $lng)")

        val started = tryPleosSdk(lat, lng, lotName)
        if (!started) {
            Log.w(TAG, "Pleos SDK 미동작 — 내비게이션 시작 불가 (에뮬레이터 확인 필요)")
        }

        // UI 콜백은 SDK 성공 여부와 무관하게 호출 (화면 강조 표시용)
        onNavigationStarted?.invoke(lotName, lat, lng)

        Log.d(TAG, "목적지 설정 완료: $lotName")
    }

    /**
     * 내비게이션을 취소합니다.
     * 출차 완료 또는 사용자 수동 취소 시 호출.
     */
    fun cancelNavigation() {
        try {
            naviInstance?.let { navi ->
                val clazz = navi.javaClass
                val stopMethod = clazz.getMethod("stopNavigation")
                stopMethod.invoke(navi)
                Log.d(TAG, "내비게이션 취소됨")
            }
        } catch (e: Exception) {
            Log.w(TAG, "내비게이션 취소 실패: ${e.message}")
        }
        onNavigationEnded?.invoke()
    }

    // ── Pleos SDK 내부 호출 ───────────────────────────────────────────────────

    /**
     * 리플렉션으로 Pleos NaviHelper SDK를 호출합니다.
     * SDK가 없으면 false를 반환하며 앱이 크래시되지 않습니다.
     *
     * Pleos NaviHelper 예상 API:
     *   naviHelper.setDestination(double lat, double lng, String poiName)
     *   naviHelper.startNavigation()
     *
     * 실제 메서드명이 다를 경우 아래 methodNames 배열에 후보를 추가하세요.
     */
    private fun tryPleosSdk(lat: Double, lng: Double, poiName: String): Boolean {
        val navi = naviInstance ?: return false
        val clazz = navi.javaClass

        // setDestination 메서드 후보 (Pleos SDK 버전별 대응)
        val setDestCandidates = listOf(
            Triple("setDestination",   arrayOf(Double::class.java, Double::class.java, String::class.java), arrayOf<Any>(lat, lng, poiName)),
            Triple("setDestination",   arrayOf(Float::class.java,  Float::class.java,  String::class.java), arrayOf<Any>(lat.toFloat(), lng.toFloat(), poiName)),
            Triple("navigate",         arrayOf(Double::class.java, Double::class.java, String::class.java), arrayOf<Any>(lat, lng, poiName)),
            Triple("startNavigation",  arrayOf(Double::class.java, Double::class.java, String::class.java), arrayOf<Any>(lat, lng, poiName))
        )

        var setDestOk = false
        for ((name, types, args) in setDestCandidates) {
            try {
                val method = clazz.getMethod(name, *types)
                method.invoke(navi, *args)
                Log.d(TAG, "SDK 호출 성공: $name($lat, $lng, $poiName)")
                setDestOk = true
                break
            } catch (e: NoSuchMethodException) {
                continue
            } catch (e: Exception) {
                Log.w(TAG, "$name 호출 실패: ${e.message}")
            }
        }

        if (!setDestOk) return false

        // startNavigation() 별도 호출이 필요한 경우
        try {
            val startMethod = clazz.getMethod("startNavigation")
            startMethod.invoke(navi)
            Log.d(TAG, "startNavigation() 호출 완료")
        } catch (e: NoSuchMethodException) {
            // setDestination 하나로 자동 시작되는 SDK 버전 — 정상
        } catch (e: Exception) {
            Log.w(TAG, "startNavigation 호출 실패: ${e.message}")
        }

        return true
    }

    /** 경로 시작 콜백 등록 (SDK 내부 리스너) */
    private fun registerRouteCallback() {
        val navi = naviInstance ?: return
        try {
            // 인터페이스 동적 프록시로 SDK 콜백 연결
            val listenerClass = Class.forName("$NAVI_CLASS\$OnRouteStartedListener")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass)
            ) { _, method, args ->
                when (method.name) {
                    "onRouteStarted" -> {
                        Log.d(TAG, "경로 안내 시작 (SDK 콜백)")
                    }
                    "onNavigationEnded", "onArrived" -> {
                        Log.d(TAG, "목적지 도착 또는 안내 종료")
                        onNavigationEnded?.invoke()
                    }
                }
                null
            }
            val setListener = navi.javaClass.getMethod(
                "setOnRouteStartedListener", listenerClass
            )
            setListener.invoke(navi, proxy)
        } catch (e: Exception) {
            // 리스너 등록 실패는 무시 (SDK 버전 차이)
            Log.d(TAG, "경로 콜백 등록 스킵: ${e.message}")
        }
    }

    // ── 해제 ─────────────────────────────────────────────────────────────────

    fun release() {
        try {
            naviInstance?.let { navi ->
                navi.javaClass.getMethod("release").invoke(navi)
            }
        } catch (e: Exception) { /* 무시 */ }
        naviInstance     = null
        isInitialized    = false
        onNavigationStarted = null
        onNavigationEnded   = null
        Log.d(TAG, "NaviHelper 해제")
    }
}
