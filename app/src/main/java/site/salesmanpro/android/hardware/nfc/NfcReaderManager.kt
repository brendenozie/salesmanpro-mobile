package site.salesmanpro.android.hardware.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Log

class NfcReaderManager(private val activity: Activity) : NfcAdapter.ReaderCallback {

    private val nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    var onTagScannedListener: ((cardUidHex: String) -> Unit)? = null

    val isNfcSupported: Boolean
        get() = nfcAdapter != null

    val isNfcEnabled: Boolean
        get() = nfcAdapter?.isEnabled == true

    fun startListening() {
        if (nfcAdapter == null || !nfcAdapter.isEnabled) {
            Log.w("NfcReaderManager", "NFC is not supported or disabled on this device.")
            return
        }

        val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

        val options = Bundle()
        options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 100)

        nfcAdapter.enableReaderMode(activity, this, flags, options)
        Log.d("NfcReaderManager", "NFC Reader Mode enabled.")
    }

    fun stopListening() {
        try {
            nfcAdapter?.disableReaderMode(activity)
            Log.d("NfcReaderManager", "NFC Reader Mode disabled.")
        } catch (e: Exception) {
            Log.e("NfcReaderManager", "Error disabling NFC reader mode", e)
        }
    }

    override fun onTagDiscovered(tag: Tag?) {
        val tagId = tag?.id ?: return
        val hexUid = bytesToHex(tagId)
        Log.d("NfcReaderManager", "Discovered NFC Tag with UID: $hexUid")
        activity.runOnUiThread {
            onTagScannedListener?.invoke(hexUid)
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02X", b))
        }
        return sb.toString()
    }
}

