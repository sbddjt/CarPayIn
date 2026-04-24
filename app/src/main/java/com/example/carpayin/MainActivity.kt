package com.example.carpayin

import android.app.Activity
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView

class MainActivity : Activity() {

    private var car: Car? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        readVin()
    }

    private fun readVin() {
        try {
            car = Car.createCar(this)
            val carPropertyManager =
                car!!.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager

            val vin = carPropertyManager
                .getProperty<String>(VehiclePropertyIds.INFO_VIN, 0)?.value

            Log.d("CarPayIn", "VIN: $vin")
            findViewById<TextView>(R.id.tvVin).text = "VIN: $vin"

        } catch (e: SecurityException) {
            Log.e("CarPayIn", "권한 없음: ${e.message}")
            findViewById<TextView>(R.id.tvVin).text = "VIN 권한 필요"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        car?.disconnect()
    }
}