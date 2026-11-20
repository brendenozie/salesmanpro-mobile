package site.salesmanpro.android.webbridge

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import site.salesmanpro.android.data.local.OfflineDatabaseHelper
import site.salesmanpro.android.data.models.NormalizedCustomer
import site.salesmanpro.android.data.models.NormalizedDocument
import site.salesmanpro.android.data.models.NormalizedPaymentInfo
import site.salesmanpro.android.data.models.NormalizedPrintItem
import site.salesmanpro.android.data.models.NormalizedPrintPayload
import site.salesmanpro.android.data.models.NormalizedPrintTotals
import site.salesmanpro.android.hardware.UnifiedPrinterManager
import java.util.UUID

class WebAppInterface(
    private val printerManager: UnifiedPrinterManager,
    private val offlineDb: OfflineDatabaseHelper? = null,
    var webViewGetter: (() -> android.webkit.WebView?)? = null,
    var onLogout: (() -> Unit)? = null,
    var onTriggerBiometric: ((title: String, subtitle: String) -> Unit)? = null,
    var onStartNfc: (() -> Unit)? = null,
    var onStopNfc: (() -> Unit)? = null,
    var onOpenMicrophoneSettings: (() -> Unit)? = null
) {
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.Main)

    @JavascriptInterface
    fun postMessage(json: String) {
        try {
            Log.d("WebAppInterface", "Received postMessage: $json")
            val root = gson.fromJson(json, Map::class.java)
            val msgType = root["type"] as? String

            if (msgType == "APP_LOGOUT" || msgType == "LOGOUT") {
                scope.launch {
                    onLogout?.invoke()
                }
                return
            }

            if (msgType == "DISCOVER_PRINTERS" || msgType == "GET_PAIRED_DEVICES") {
                scope.launch {
                    val printers = printerManager.getAvailablePrinters()
                    val printersJson = gson.toJson(printers)
                    val js = "window.dispatchEvent(new CustomEvent('SalesmanProNativePrintersDiscovered', { detail: { printers: $printersJson } }));"
                    webViewGetter?.invoke()?.evaluateJavascript(js, null)
                }
            } else if (msgType == "CONNECT_DEVICE") {
                val address = root["address"] as? String ?: ""
                val connectionType = root["connectionType"] as? String ?: "BLUETOOTH"
                scope.launch {
                    if (address.isNotEmpty()) {
                        printerManager.saveDefaultPrinter(address, connectionType)
                    }
                    val js = "window.dispatchEvent(new CustomEvent('SalesmanProDeviceConnected', { detail: { success: ${address.isNotEmpty()}, address: '$address', connectionType: '$connectionType' } }));"
                    webViewGetter?.invoke()?.evaluateJavascript(js, null)
                }
            } else if (msgType == "STOP_DISCOVERY") {
                Log.d("WebAppInterface", "Bluetooth discovery stop requested.")
            } else if (msgType == "AUTHENTICATE_BIOMETRIC" || msgType == "STEP_UP_AUTH") {
                val title = root["title"] as? String ?: "Biometric Authorization"
                val subtitle = root["subtitle"] as? String ?: "Verify your identity"
                scope.launch {
                    onTriggerBiometric?.invoke(title, subtitle)
                }
            } else if (msgType == "START_NFC_SCAN" || msgType == "READ_NFC_CARD") {
                scope.launch {
                    onStartNfc?.invoke()
                }
            } else if (msgType == "STOP_NFC_SCAN") {
                scope.launch {
                    onStopNfc?.invoke()
                }
            } else if (msgType == "TEST_PRINT") {
                val address = root["address"] as? String ?: ""
                val connectionType = root["connectionType"] as? String ?: "BLUETOOTH"
                val paperWidth = root["paperWidth"] as? String ?: "58mm"
                scope.launch {
                    val success = printerManager.printTestSlip(address, connectionType, paperWidth)
                    val js = "window.dispatchEvent(new CustomEvent('SalesmanProTestPrintResult', { detail: { success: $success, address: '$address' } }));"
                    webViewGetter?.invoke()?.evaluateJavascript(js, null)
                }
            } else if (msgType == "PRINT_PAYLOAD" || msgType == "PRINT_ESC_POS" || msgType == "PRINT_HTML_RECEIPT") {
                val payloadObj = root["payload"]
                val payloadJson = gson.toJson(payloadObj)

                val payload: NormalizedPrintPayload? = if (payloadJson.contains("\"protocolVersion\":1")) {
                    gson.fromJson(payloadJson, NormalizedPrintPayload::class.java)
                } else {
                    // Adapt legacy receipt structure to NormalizedPrintPayload
                    val legacy = gson.fromJson(payloadJson, ReceiptDetails::class.java)
                    convertLegacyReceipt(legacy)
                }

                if (payload != null) {
                    scope.launch {
                        val result = printerManager.processPrintPayload(payload)
                        val statusJs = "window.dispatchEvent(new CustomEvent('SalesmanProPrintStatus', { detail: { jobId: '${payload.jobId}', success: ${result.success}, isDuplicate: ${result.isDuplicate}, error: ${gson.toJson(result.errorMessage)} } }));"
                        webViewGetter?.invoke()?.evaluateJavascript(statusJs, null)
                        if (!result.success && !result.isDuplicate) {
                            Log.e("WebAppInterface", "Print execution failed: ${result.errorMessage}")
                        }
                    }
                }
            } else if (msgType == "RETRY_PRINT_JOB") {
                val jobId = root["jobId"] as? String ?: ""
                if (jobId.isNotEmpty()) {
                    scope.launch {
                        val result = printerManager.retryPrintJob(jobId)
                        val statusJs = "window.dispatchEvent(new CustomEvent('SalesmanProPrintStatus', { detail: { jobId: '$jobId', success: ${result.success}, isDuplicate: ${result.isDuplicate}, error: ${gson.toJson(result.errorMessage)} } }));"
                        webViewGetter?.invoke()?.evaluateJavascript(statusJs, null)
                    }
                }
            } else if (msgType == "SAVE_OFFLINE_OUTBOX") {
                val payloadMap = root["payload"] as? Map<*, *>
                if (payloadMap != null) {
                    val id = payloadMap["id"] as? String ?: UUID.randomUUID().toString()
                    val companyId = payloadMap["companyId"] as? String ?: ""
                    val entityType = payloadMap["entityType"] as? String ?: "ORDER"
                    val status = payloadMap["status"] as? String ?: "PENDING"
                    val payloadJson = gson.toJson(payloadMap)
                    offlineDb?.upsertOutboxRecord(id, companyId, entityType, status, payloadJson)
                }
            } else if (msgType == "UPDATE_OFFLINE_OUTBOX") {
                val payloadMap = root["payload"] as? Map<*, *>
                if (payloadMap != null) {
                    val id = payloadMap["id"] as? String ?: ""
                    val status = payloadMap["status"] as? String ?: "SYNCED"
                    if (id.isNotEmpty()) {
                        offlineDb?.updateOutboxStatus(id, status, gson.toJson(payloadMap))
                    }
                }
            } else if (msgType == "GET_OFFLINE_OUTBOX") {
                val companyId = root["companyId"] as? String
                scope.launch {
                    val records = offlineDb?.getPendingOutboxRecords(companyId) ?: emptyList()
                    val joinedJson = records.joinToString(",", "[", "]")
                    val js = "window.dispatchEvent(new CustomEvent('SalesmanProOfflineOutboxLoaded', { detail: { records: $joinedJson } }));"
                    webViewGetter?.invoke()?.evaluateJavascript(js, null)
                }
            } else if (msgType == "CASH_DRAWER_KICK") {
                // ESC/POS cash drawer open pulse — sent on every confirmed cash payment
                val printerId = root["printerId"] as? String
                val connectionType = root["connectionType"] as? String ?: "BLUETOOTH"
                scope.launch {
                    val success = printerManager.kickCashDrawer(printerId, connectionType)
                    val js = "window.dispatchEvent(new CustomEvent('SalesmanProCashDrawerKick', { detail: { success: $success } }));"
                    webViewGetter?.invoke()?.evaluateJavascript(js, null)
                    Log.d("WebAppInterface", "Cash drawer kick dispatched: success=$success")
                }
            } else if (msgType == "SHOW_NOTIFICATION" || msgType == "NOTIFICATION_RECEIVED") {
                val payloadObj = root["payload"] as? Map<*, *>
                val title = payloadObj?.get("title") as? String ?: "SalesmanPro Alert"
                val message = payloadObj?.get("message") as? String ?: ""
                Log.d("WebAppInterface", "Displaying native Android notification: $title - $message")
            } else if (msgType == "VOICE_STATE_CHANGED") {
                val voiceState = root["state"] as? String ?: "OFF"
                val micActive = root["micActive"] as? Boolean ?: false
                Log.d("WebAppInterface", "Selly voice state changed: state=$voiceState micActive=$micActive")
            }
        } catch (e: Exception) {
            Log.e("WebAppInterface", "Error parsing postMessage: ${e.message}", e)
        }
    }

    @JavascriptInterface
    fun logout() {
        scope.launch {
            onLogout?.invoke()
        }
    }

    @JavascriptInterface
    fun getPushToken(): String {
        return ""
    }

    private fun convertLegacyReceipt(legacy: ReceiptDetails): NormalizedPrintPayload {
        val doc = NormalizedDocument(
            number = legacy.InvoiceId.ifEmpty { "INV-${UUID.randomUUID().toString().substring(0, 8)}" },
            date = legacy.Date,
            cashierName = legacy.StaffName,
            orderType = legacy.BusinessName,
            items = legacy.Items.map {
                NormalizedPrintItem(
                    name = it.Name,
                    quantity = it.Quantity.toDouble(),
                    price = it.Price,
                    discount = it.Discount,
                    total = it.Total
                )
            },
            totals = NormalizedPrintTotals(
                subtotal = legacy.Subtotal,
                tax = legacy.TaxAmount,
                taxRate = legacy.TaxRate,
                total = legacy.TotalAmount,
                paid = legacy.TotalAmount,
                change = legacy.ChangeGiven,
                currency = legacy.Currency.ifEmpty { "KES" }
            ),
            payments = listOf(
                NormalizedPaymentInfo(
                    method = legacy.PaymentMethod,
                    amount = legacy.TotalAmount
                )
            )
        )

        return NormalizedPrintPayload(
            jobId = "legacy_${legacy.InvoiceId.ifEmpty { UUID.randomUUID().toString() }}",
            documentType = "RECEIPT",
            documentId = legacy.InvoiceId,
            document = doc
        )
    }

    fun dispatchNfcScanned(cardUid: String) {
        scope.launch {
            val js = "window.dispatchEvent(new CustomEvent('SalesmanProNfcCardScanned', { detail: { cardUid: '$cardUid' } }));"
            webViewGetter?.invoke()?.evaluateJavascript(js, null)
        }
    }

    fun dispatchBiometricResult(success: Boolean, error: String? = null) {
        scope.launch {
            val errVal = if (error != null) "'$error'" else "null"
            val js = "window.dispatchEvent(new CustomEvent('SalesmanProBiometricResult', { detail: { success: $success, error: $errVal } }));"
            webViewGetter?.invoke()?.evaluateJavascript(js, null)
        }
    }

    fun dispatchAndroidLifecycle(event: String) {
        scope.launch {
            val safeEvent = event.replace("'", "")
            val js = "window.dispatchEvent(new CustomEvent('SalesmanProAndroidLifecycle', { detail: { event: '$safeEvent' } }));"
            webViewGetter?.invoke()?.evaluateJavascript(js, null)
        }
    }

    @JavascriptInterface
    fun getVoiceCapabilities(): String {
        return """{"platform":"ANDROID","targetSdk":36,"supportsBackgroundMic":false,"supportsForegroundLocalStandby":true}"""
    }

    @JavascriptInterface
    fun openMicrophoneSettings() {
        scope.launch {
            onOpenMicrophoneSettings?.invoke()
        }
    }

    @JavascriptInterface
    fun saveBase64File(dataUrl: String, fileName: String, mimeType: String) {
        val ctx = webViewGetter?.invoke()?.context ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val base64Part = if (dataUrl.contains(",")) dataUrl.substringAfter(",") else dataUrl
                val bytes = Base64.decode(base64Part, Base64.DEFAULT)
                val safeName = fileName.ifBlank { "document_${System.currentTimeMillis()}.pdf" }
                    .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val resolvedMime = mimeType.ifBlank { "application/pdf" }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(MediaStore.Downloads.MIME_TYPE, resolvedMime)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = ctx.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { it.write(bytes) }
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()
                    val outFile = File(downloadsDir, safeName)
                    FileOutputStream(outFile).use { it.write(bytes) }
                }

                scope.launch {
                    Toast.makeText(ctx, "Saved $safeName to Downloads", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("WebAppInterface", "Failed to save downloaded file", e)
                scope.launch {
                    Toast.makeText(ctx, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

data class ReceiptPayload(
    val type: String,
    val payload: ReceiptDetails
)

data class ReceiptDetails(
    val BusinessName: String = "",
    val BusinessAddress: String = "",
    val TaxId: String = "",
    val PhoneNumber: String = "",
    val InvoiceId: String = "",
    val StaffName: String = "",
    val Date: String = "",
    val Items: List<ReceiptItem> = emptyList(),
    val Currency: String = "KES",
    val TaxRate: Double = 0.0,
    val TaxAmount: Double = 0.0,
    val Subtotal: Double = 0.0,
    val TotalAmount: Double = 0.0,
    val ChangeGiven: Double = 0.0,
    val PaymentMethod: String = "CASH"
)

data class ReceiptItem(
    val Name: String = "",
    val Quantity: Int = 1,
    val Price: Double = 0.0,
    val Discount: Double = 0.0,
    val Total: Double = 0.0
)
