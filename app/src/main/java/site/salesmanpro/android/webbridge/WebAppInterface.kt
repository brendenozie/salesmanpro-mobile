package site.salesmanpro.android.webbridge

import android.webkit.JavascriptInterface
import com.google.gson.Gson // Ensure you have implementation "com.google.code.gson:gson:2.10.1"
import site.salesmanpro.android.hardware.bluetooth.BluetoothPrinterService

class WebAppInterface(
    private val printerService: BluetoothPrinterService,
    private val getMacAddress: () -> String
) {
    private val gson = Gson()

    @JavascriptInterface
    fun postMessage(json: String) {
        try {
            // 1. Parse the incoming JSON
            val data = gson.fromJson(json, ReceiptPayload::class.java)

            // 2. Trigger the Print
            if (data.type == "PRINT_ESC_POS") {
                val macAddress = getMacAddress()
                if (macAddress.isNotEmpty()) {
                    printerService.printReceipt(macAddress, data.payload)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

// Root wrapper to match the incoming JSON structure
data class ReceiptPayload(
    val type: String,
    val payload: ReceiptDetails
)

data class ReceiptDetails(
    val BusinessName: String,
    val BusinessAddress: String,
    val TaxId: String,          // 👈 Added
    val PhoneNumber: String,    // 👈 Added
    val InvoiceId: String,
    val StaffName: String,      // 👈 Added
    val Date: String,           // 👈 Added (Format: "yyyy-MM-dd HH:mm")
    val Items: List<ReceiptItem>,
    val Currency: String,
    val TaxRate: Double,        // e.g., 15.0 for 15%
    val TaxAmount: Double,      // 👈 Added for direct printing
    val Subtotal: Double,       // 👈 Added
    val TotalAmount: Double,    // 👈 Added (Desktop calls this 'Total')
    val ChangeGiven: Double,
    val PaymentMethod: String
)

data class ReceiptItem(
    val Name: String,
    val Quantity: Int,
    val Price: Double,          // Unit price
    val Discount: Double,
    val Total: Double           // 👈 Added (Quantity * Price - Discount)
)
//
//// Data models to match your TypeScript 'desktopPayload'
//data class ReceiptPayload(val type: String, val payload: ReceiptDetails)
//
//data class ReceiptDetails(
//    val BusinessName: String,
//    val BusinessAddress: String,
//    val InvoiceId: String,
//    val Items: List<ReceiptItem>,
//    val Currency: String,
//    val TaxRate: Double,
//    val ChangeGiven: Double,
//    val PaymentMethod: String
//)
//
//data class ReceiptItem(
//    val Name: String,
//    val Quantity: Int,
//    val Price: Double,
//    val Discount: Double
//)