package org.screenlite.webkiosk.components

import android.app.Activity
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.screenlite.webkiosk.data.KioskSettingsFactory

@Composable
fun MainScreen(activity: Activity, modifier: Modifier, intentUrl: String? = null) {
    val context = LocalContext.current
    // Start immediately with the intent URL if available, avoiding any DataStore round-trip
    var url by remember { mutableStateOf(intentUrl ?: "about:blank") }
    val kioskSettings = remember { KioskSettingsFactory.get(context) }

    LaunchedEffect(Unit) {
        var isFirstEmission = true
        kioskSettings.getStartUrl().collect { newUrl ->
            if (isFirstEmission) {
                isFirstEmission = false
                if (intentUrl.isNullOrBlank() && newUrl.isNotBlank()) {
                    // No intent URL — use whatever is stored (normal restart without intent)
                    url = newUrl
                }
                // If we have an intentUrl, skip this first emission: it may be the stale
                // default that hasn't been overwritten by the async DataStore write yet.
            } else {
                // All subsequent emissions are fresh (ConfigReceiver, settings changes, etc.)
                if (newUrl.isNotBlank()) url = newUrl
            }
        }
    }

    key(url) {
        WebViewComponent(url = url, activity = activity, modifier)
    }
}