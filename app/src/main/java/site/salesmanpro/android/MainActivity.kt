package site.salesmanpro.android

import MainWebViewScreen
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
// --- Added missing UI imports ---
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
// --------------------------------
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
// ------------------------------------
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalContext

// Import your service (adjust if the folder path is different)
import site.salesmanpro.android.hardware.bluetooth.BluetoothPrinterService
import site.salesmanpro.android.ui.screens.BluetoothScannerScreen
import site.salesmanpro.android.ui.viewmodels.BluetoothViewModel
import site.salesmanpro.android.webbridge.WebAppInterface


class MainActivity : ComponentActivity() {
    // Use a state to track the deep link
    private var deepLinkUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Initialize the Printer Service (This was the missing reference)
        val printerService = BluetoothPrinterService(this)

        val viewModel = BluetoothViewModel()
        val prefs = getSharedPreferences("SalesmanPrefs", MODE_PRIVATE)


        setContent {
            var showScanner by remember { mutableStateOf(false) }

            val context = LocalContext.current

            // 1. Change to RequestMultiplePermissions
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions ->
                // Check if BLUETOOTH_CONNECT was granted (SCAN is helpful but technically secondary if you already have the MAC)
                val isConnectGranted = permissions[Manifest.permission.BLUETOOTH_CONNECT] == true

                if (isConnectGranted) {
                    viewModel.fetchPairedDevices(context)
                    showScanner = true
                } else {
                    Toast.makeText(context, "Bluetooth permission denied", Toast.LENGTH_SHORT).show()
                }
            }


            Box(modifier = Modifier.fillMaxSize()) { // Ensure the container is also full screen

                // Background: The Web App
                MainWebViewScreen(
//                    url = "https://salesmanpro.site",
                    webInterface = WebAppInterface(printerService) {
                        prefs.getString("printer_mac", "") ?: ""
                    },
//                            webInterface = WebAppInterface(this),
//                    deepLinkData = deepLinkUri
                )

                // Floating Action Button to open settings
                FloatingActionButton(
                    onClick = {
                        // For Android 12 (API 31) and above, check for BLUETOOTH_CONNECT
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            // Ask for BOTH modern permissions
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.BLUETOOTH_CONNECT,
                                    Manifest.permission.BLUETOOTH_SCAN
                                )
                            )
                        } else {
                            viewModel.fetchPairedDevices(context)
                            showScanner = true
                        }
//                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//                            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
//                        } else {
//                            // Older versions don't need runtime connect permission
//                            viewModel.fetchPairedDevices(context)
//                            showScanner = true
//                        }

                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp).padding(bottom = 56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Printer Settings"
                    )
                }

                if (showScanner) {
                    BluetoothScannerScreen(
                        devices = viewModel.pairedDevices,
                        onDeviceSelected = { device ->
                            prefs.edit().putString("printer_mac", device.address).apply()
                            showScanner = false
                            Toast.makeText(
                                this@MainActivity,
                                "Linked to ${device.name}",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onDismiss = { showScanner = false }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            deepLinkUri = intent.data
        }
    }
}