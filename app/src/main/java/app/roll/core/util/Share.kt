package app.roll.core.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build

/** Opens the system share sheet with plain text. */
fun Context.shareText(text: String, title: String? = null) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Copies to the clipboard. Returns true when the app should confirm it itself; Android 13+
 * shows its own confirmation.
 */
fun Context.copyText(label: String, text: String): Boolean {
    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
}

fun joinMessage(className: String, code: String): String =
    "Join $className on Roll: $code\nroll://join/$code"
