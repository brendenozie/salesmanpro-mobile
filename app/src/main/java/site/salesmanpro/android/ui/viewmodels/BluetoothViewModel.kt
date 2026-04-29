package site.salesmanpro.android.ui.viewmodels

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import site.salesmanpro.android.data.models.BluetoothDeviceItem
import android.Manifest
import android.os.Build

class BluetoothViewModel : ViewModel() {
    val pairedDevices = mutableStateListOf<BluetoothDeviceItem>()

    @SuppressLint("MissingPermission")
    fun fetchPairedDevices(context: Context) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = bluetoothManager.adapter

        // 1. Check if Bluetooth is actually ON
        if (adapter?.isEnabled != true) {
            // Optional: Notify user to turn on Bluetooth
            return
        }

        // 2. Perform the safety check for Android 12+
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (hasPermission) {
            pairedDevices.clear()
            adapter.bondedDevices?.forEach { device ->
                pairedDevices.add(BluetoothDeviceItem(device.name ?: "Unknown Device", device.address))
            }
        }
    }

//    @SuppressLint("MissingPermission")
//    fun fetchPairedDevices(context: Context) {
//        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
//        val adapter: BluetoothAdapter? = bluetoothManager.adapter
//
//        pairedDevices.clear()
//        adapter?.bondedDevices?.forEach { device ->
//            pairedDevices.add(BluetoothDeviceItem(device.name ?: "Unknown Device", device.address))
//        }
//    }
}