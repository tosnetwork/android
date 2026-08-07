package network.tos.wallet.app

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import network.tos.wallet.app.core.DevSettings

val Context.batteryLevel: Int
    get() {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

val Context.isLowDevice: Boolean
    get() {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.isLowRamDevice
    }

val Context.isBlurDisabled: Boolean
    get() = !DevSettings.blurEnabled || (isLowDevice && 20 >= batteryLevel)