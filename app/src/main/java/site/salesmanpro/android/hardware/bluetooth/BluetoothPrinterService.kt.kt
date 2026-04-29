package site.salesmanpro.android.hardware.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.RelativeLayout.ALIGN_RIGHT
import com.google.android.gms.common.util.CollectionUtils.listOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.salesmanpro.android.webbridge.ReceiptDetails
import java.io.OutputStream
import java.util.UUID

class BluetoothPrinterService(private val context: Context) {

    // Standard UUID for Serial Port Profile (SPP) used by Bluetooth printers
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val scope = CoroutineScope(Dispatchers.IO)

    // ESC/POS Commands
    private val ESC_INIT = byteArrayOf(0x1B, 0x40)
    private val ALIGN_LEFT = byteArrayOf(0x1B, 0x61, 0x00)
    private val ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01)
    private val BOLD_ON = byteArrayOf(0x1B, 0x45, 0x01)
    private val BOLD_OFF = byteArrayOf(0x1B, 0x45, 0x00)
    private val NEWLINE = byteArrayOf(0x0A)

    fun printReceipt(macAddress: String, receipt: ReceiptDetails) {
        if (macAddress.isEmpty()) {
            Log.e("PrinterService", "No MAC address provided")
            return
        }

        // Run Bluetooth connection on a background thread
        scope.launch {
            connectAndPrint(macAddress, receipt)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectAndPrint(macAddress: String, receipt: ReceiptDetails) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = bluetoothManager.adapter

        if (adapter == null || !adapter.isEnabled) {
            Log.e("PrinterService", "Bluetooth is disabled or not supported")
            return
        }

        // Safety check for Android 12+ permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e("PrinterService", "Missing BLUETOOTH_CONNECT permission")
                return
            }
        }

        var socket: BluetoothSocket? = null
        var outputStream: OutputStream? = null

        try {
            val device = adapter.getRemoteDevice(macAddress)
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)

            // Safely cancel discovery based on Android version and permissions
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        adapter.cancelDiscovery()
                    } else {
                        Log.w("PrinterService", "Skipped cancelDiscovery() - missing BLUETOOTH_SCAN permission")
                    }
                } else {
                    adapter.cancelDiscovery()
                }
            } catch (e: SecurityException) {
                Log.w("PrinterService", "Security exception on cancelDiscovery: ${e.message}")
            }
            // Discovery consumes a lot of resources, always cancel before connecting
//            adapter.cancelDiscovery()

            socket.connect()
            outputStream = socket.outputStream

            // Start sending bytes to the printer
            buildAndSendReceipt(outputStream, receipt)

            Log.d("PrinterService", "Print successful!")

        } catch (e: Exception) {
            Log.e("PrinterService", "Failed to print: ${e.message}")
        } finally {
            // Always clean up the socket
            try {
                outputStream?.close()
                socket?.close()
            } catch (e: Exception) {
                Log.e("PrinterService", "Error closing socket: ${e.message}")
            }
        }
    }

    private suspend fun buildAndSendReceipt(out: OutputStream, receipt: ReceiptDetails) = withContext(Dispatchers.IO) {
        val SEP = "--------------------------------\n"

        // Command Constants
        val TEXT_SIZE_NORMAL = byteArrayOf(0x1D, 0x21, 0x00)
        val TEXT_SIZE_DOUBLE_HEIGHT = byteArrayOf(0x1D, 0x21, 0x01)

        // 1. Initialize Printer
        out.write(ESC_INIT)

        // 2. Header (Centered, Double Height for Business Name)
        out.write(ALIGN_CENTER)
        out.write(TEXT_SIZE_DOUBLE_HEIGHT)
        out.write(BOLD_ON)
        out.write("${receipt.BusinessName.uppercase()}\n".toByteArray())
        out.write(TEXT_SIZE_NORMAL) // Back to normal
        out.write(BOLD_OFF)

        out.write("${receipt.BusinessAddress}\n".toByteArray())
        out.write("TAX ID: ${receipt.TaxId}\n".toByteArray())
        out.write("TEL: ${receipt.PhoneNumber}\n".toByteArray())
        out.write(SEP.toByteArray())

        // 3. Transaction Details (Left Aligned)
        out.write(ALIGN_LEFT)
        out.write("DATE: ${receipt.Date}\n".toByteArray())
        out.write("INV#: ${receipt.InvoiceId}\n".toByteArray())
        out.write("CASHIER: ${receipt.StaffName}\n".toByteArray())
        out.write(SEP.toByteArray())

        // 4. Item Table (3 Columns: QTY, DESCRIPTION, TOTAL)
        // Layout: QTY(5) + DESC(17) + TOTAL(10) = 32 chars
        out.write("QTY  DESCRIPTION          TOTAL\n".toByteArray())
        receipt.Items.forEach { item ->
            val qty = item.Quantity.toString().padEnd(5)
            val name = if (item.Name.length > 16) item.Name.substring(0, 15) + "." else item.Name.padEnd(17)
            val price = String.format("%.2f", item.Price * item.Quantity).padStart(10)

            out.write("$qty$name$price\n".toByteArray())

            if (item.Discount > 0) {
                out.write("     *DISCOUNT: -${String.format("%.2f", item.Discount)}\n".toByteArray())
            }
        }
        out.write(SEP.toByteArray())

        // 5. Financials (Right Aligned)
        out.write(ALIGN_RIGHT)
        val subtotal = receipt.Items.sumOf { it.Price * it.Quantity }
        out.write("SUBTOTAL: ${String.format("%.2f", subtotal)}\n".toByteArray())
        out.write("TAX (${receipt.TaxRate}%): ${String.format("%.2f", subtotal * (receipt.TaxRate/100))}\n".toByteArray())

        out.write(TEXT_SIZE_DOUBLE_HEIGHT)
        out.write(BOLD_ON)
        out.write("TOTAL: ${receipt.Currency} ${String.format("%.2f", receipt.TotalAmount)}\n".toByteArray())
        out.write(BOLD_OFF)
        out.write(TEXT_SIZE_NORMAL)
        out.write(SEP.toByteArray())

        // 6. Payment & Footer
        out.write(ALIGN_LEFT)
        out.write("PAYMENT: ${receipt.PaymentMethod}\n".toByteArray())
        out.write("CHANGE:  ${String.format("%.2f", receipt.ChangeGiven)}\n".toByteArray())

        out.write("\n".toByteArray()) // Feed 1
        out.write(ALIGN_CENTER)
        out.write("Thank you for your business!\n".toByteArray())

        // Note: QR Code generation requires a specific library or specific ESC/POS sequence
        // which varies by printer model. If your printer supports it:
        // printQRCode("https://verify.tax.gov/check?id=${receipt.InvoiceId}")
        out.write(ALIGN_CENTER)
        val qrUrl = "https://verify.tax.gov/check?id=${receipt.InvoiceId}"
        out.write(getQrCodeBytes(qrUrl))

        // 7. Feed and Flush
        out.write("\n\n\n\n".toByteArray())
        out.flush()
    }
    /**
     * Helper function to align text left and right on a 32-character line
     * Example: "Item Name                 $10.00"
     */
    private fun formatLine(left: String, right: String): String {
        val maxLineLength = 32
        var safeLeft = left
        var safeRight = right

        // Truncate if somehow incredibly long
        if (safeLeft.length + safeRight.length > maxLineLength - 1) {
            safeLeft = safeLeft.substring(0, maxLineLength - safeRight.length - 2) + "."
        }

        val spaces = maxLineLength - safeLeft.length - safeRight.length
        val spaceStr = " ".repeat(Math.max(0, spaces))

        return "$safeLeft$spaceStr$safeRight"
    }

    private fun getQrCodeBytes(content: String): ByteArray {
        val bytes = mutableListOf<Byte>()

        // 1. Set QR Model (Function 165) - Model 2 is standard
        bytes.addAll(listOf(0x1D, 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00).map { it.toByte() })

        // 2. Set QR Size (Function 167) - 0x03 to 0x08 usually works well
        val size = 0x06.toByte()
        bytes.addAll(listOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, size).map { it.toByte() })

        // 3. Set Error Correction Level (Function 169) - '0' (48) is ~7%, '3' (51) is ~30%
        bytes.addAll(listOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, 0x31).map { it.toByte() })

        // 4. Store Data in Symbol Storage Area (Function 180)
        val dataLen = content.length + 3
        val pL = (dataLen % 256).toByte()
        val pH = (dataLen / 256).toByte()

        bytes.addAll(listOf(0x1D, 0x28, 0x6B, pL, pH, 0x31, 0x50, 0x30).map { it.toByte() })
        bytes.addAll(content.toByteArray().toList())

        // 5. Print the QR code (Function 181)
        bytes.addAll(listOf(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30).map { it.toByte() })

        return bytes.toByteArray()
    }
}