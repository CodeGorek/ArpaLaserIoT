package com.arpalaser.iot.bluetooth

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Gestión centralizada de permisos Bluetooth según la versión de Android.
 *  - Android 12+ (API 31): BLUETOOTH_CONNECT y BLUETOOTH_SCAN para conectar; BLUETOOTH_ADVERTISE para hacerse visible en tiempo de ejecución.
 *  - Android 8–11: BLUETOOTH / BLUETOOTH_ADMIN se otorgan al instalar.
 */
object BtPermissions {

    const val REQUEST_CODE = 4210

    fun required(asServer: Boolean): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (asServer) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            else arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else emptyArray()

    fun hasAll(context: Context, asServer: Boolean): Boolean =
        required(asServer).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    fun request(activity: Activity, asServer: Boolean) {
        ActivityCompat.requestPermissions(activity, required(asServer), REQUEST_CODE)
    }
}
