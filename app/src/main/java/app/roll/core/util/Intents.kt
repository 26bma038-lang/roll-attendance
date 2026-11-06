package app.roll.core.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens a web link in the browser. Adds https:// when the scheme is missing.
 * Returns false when nothing on the phone can open it.
 */
fun Context.openUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return false
    val withScheme = if ("://" in trimmed || trimmed.startsWith("mailto:") || trimmed.startsWith("tel:")) trimmed else "https://$trimmed"
    return try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(withScheme)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
