package com.example.carpayin

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * BLE Manager — 주차장 비콘 스캔 (명세서 §4.2)
 *
 * 주차장 진입 시 비콘 UUID: "0000FFF0-0000-1000-8000-00805F9B34FB"
 * RSSI 임계값: -75 dBm (약 3~5m 이내)
 * 스캔 간격: LOW_LATENCY (입차 감지용)
 */
object BleManager {
    private const val TAG = "BleManager"

    // 주차장 비콘 서비스 UUID (실제 운영 시 오토에버와 협의한 UUID 사용)
    private val PARKING_BEACON_UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
    private const val RSSI_THRESHOLD = -75  // dBm

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var isScanning = false

    // 주차장 감지 콜백
    var onParkingLotDetected: ((lotId: String, rssi: Int) -> Unit)? = null

    fun initialize(context: Context) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        Log.d(TAG, "BLE 초기화: ${if (bluetoothAdapter != null) "성공" else "실패 (BT 없음)"}")
    }

    fun startScan(context: Context) {
        if (isScanning) return
        val adapter = bluetoothAdapter ?: run {
            Log.w(TAG, "블루투스 어댑터 없음 — 시뮬레이션 모드로 전환")
            startSimulatedScan()
            return
        }

        if (!adapter.isEnabled) {
            Log.w(TAG, "블루투스 비활성화 — 시뮬레이션 모드로 전환")
            startSimulatedScan()
            return
        }

        val scanner = adapter.bluetoothLeScanner ?: run {
            Log.w(TAG, "BLE 스캐너 없음")
            return
        }

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(PARKING_BEACON_UUID))
                .build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(filters, settings, scanCallback)
            isScanning = true
            Log.d(TAG, "BLE 스캔 시작")
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE 권한 없음: ${e.message}")
            startSimulatedScan()
        }
    }

    fun stopScan(context: Context) {
        if (!isScanning) return
        try {
            val adapter = bluetoothAdapter ?: return
            adapter.bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
            Log.d(TAG, "BLE 스캔 중지")
        } catch (e: SecurityException) {
            Log.e(TAG, "스캔 중지 권한 없음")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val rssi = result.rssi
            if (rssi >= RSSI_THRESHOLD) {
                // 비콘 광고 데이터에서 lot_id 추출
                val serviceData = result.scanRecord?.getServiceData(ParcelUuid(PARKING_BEACON_UUID))
                val lotId = if (serviceData != null && serviceData.size >= 4) {
                    "LOT-" + serviceData.take(4).joinToString("") { "%02X".format(it) }
                } else {
                    "LOT-${result.device.address.takeLast(5).replace(":", "")}"
                }
                Log.d(TAG, "주차장 비콘 감지: $lotId (RSSI: $rssi dBm)")
                onParkingLotDetected?.invoke(lotId, rssi)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE 스캔 실패: $errorCode")
            isScanning = false
        }
    }

    /**
     * 에뮬레이터/BLE 미지원 환경에서 주차장 입출차 시뮬레이션
     * Webots 시뮬레이터와 연동 시 MQTT로 대체됨
     */
    private fun startSimulatedScan() {
        isScanning = true
        Log.d(TAG, "시뮬레이션 모드: BLE 비콘 스캔 대신 MQTT 트리거 사용")
        // 실제로는 MQTT의 notification/{uuid} 토픽이 입차 트리거 역할을 함
        // Webots → Edge Server → MQTT Broker → 앱
    }

    fun isScanning() = isScanning
}
