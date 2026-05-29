package org.screenlite.webkiosk.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.screenlite.webkiosk.data.KioskSettingsFactory

class ConfigReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SET_CONFIG = "org.screenlite.webkiosk.SET_CONFIG"
        const val EXTRA_START_URL = "start_url"
        const val EXTRA_SCREEN_NAME = "screen_name"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_CONFIG) return

        val startUrl = intent.getStringExtra(EXTRA_START_URL)
        val screenName = intent.getStringExtra(EXTRA_SCREEN_NAME)

        Log.i("ConfigReceiver", "Received config — startUrl=$startUrl, screenName=$screenName")

        val settings = KioskSettingsFactory.get(context)

        CoroutineScope(Dispatchers.IO).launch {
            startUrl?.let { settings.setStartUrl(it) }
            screenName?.let { settings.setScreenName(it) }
        }
    }
}