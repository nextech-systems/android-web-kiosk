package org.screenlite.webkiosk

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import org.screenlite.webkiosk.app.FullScreenHelper
import org.screenlite.webkiosk.app.IdleBrightnessController
import org.screenlite.webkiosk.app.NotificationPermissionHelper
import org.screenlite.webkiosk.app.StayOnTopServiceStarter
import org.screenlite.webkiosk.app.TapUnlockHandler
import org.screenlite.webkiosk.components.MainScreen
import org.screenlite.webkiosk.components.TouchKioskInputOverlay
import org.screenlite.webkiosk.components.TvKioskInputOverlay
import org.screenlite.webkiosk.data.KioskSettingsFactory
import org.screenlite.webkiosk.ui.theme.ScreenliteWebKioskTheme
import org.screenlite.webkiosk.ui.theme.isTvDevice

class MainActivity : ComponentActivity() {
    private lateinit var unlockHandler: TapUnlockHandler
    lateinit var idleController: IdleBrightnessController

    companion object {
        // Process-level cache so activity restarts due to config changes
        // (e.g. keyboard appearing) can recover the URL without a new intent
        var cachedIntentUrl: String? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prefer the URL from the incoming intent; fall back to the process-level
        // cache so config-change restarts don't lose the URL.
        val intentUrl = intent.getStringExtra("start_url")?.takeIf { it.isNotBlank() }
            ?: cachedIntentUrl

        if (intentUrl != null) {
            cachedIntentUrl = intentUrl
            Log.i("MainActivity", "Applying start_url from intent: $intentUrl")
            lifecycleScope.launch {
                KioskSettingsFactory.get(this@MainActivity).setStartUrl(intentUrl)
            }
        }

        FullScreenHelper.enableImmersiveMode(this.window)
        StayOnTopServiceStarter.ensureRunning(this)

        unlockHandler = TapUnlockHandler {
            openSettings()
        }

        val settings = KioskSettingsFactory.get(this)
        idleController = IdleBrightnessController(this, settings)

        setContent {
            ScreenliteWebKioskTheme {
                AppContent(unlockHandler, this, intentUrl)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val url = intent.getStringExtra("start_url")?.takeIf { it.isNotBlank() } ?: return
        cachedIntentUrl = url
        Log.i("MainActivity", "Applying start_url from onNewIntent: $url")
        lifecycleScope.launch {
            KioskSettingsFactory.get(this@MainActivity).setStartUrl(url)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        idleController.onUserInteraction()
        return super.dispatchTouchEvent(ev)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Re-apply immersive mode whenever the window regains focus.
            // System bars can reappear temporarily (e.g. after StayOnTopService brings
            // the app back, or after a dialog/notification). This ensures they always
            // get re-hidden without requiring a full activity restart.
            FullScreenHelper.enableImmersiveMode(window)
        }
    }

    override fun onPause() {
        super.onPause()
        idleController.stop()
    }

    override fun onResume() {
        super.onResume()
        idleController.start()
        FullScreenHelper.enableImmersiveMode(window)
    }
}

@Composable
fun AppContent(unlockHandler: TapUnlockHandler, activity: Activity, intentUrl: String? = null) {
    val context = LocalContext.current
    val idleController = remember { (activity as MainActivity).idleController }
    val isIdleMode by idleController.isIdleMode.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d("MainActivity", "Notification permission granted: $isGranted")
    }

    LaunchedEffect(isIdleMode) {
        Log.d("IdleDebug", "Compose: isIdleMode state changed to: $isIdleMode")
    }

    LaunchedEffect(Unit) {
        if (!NotificationPermissionHelper.hasPermission(context)) {
            NotificationPermissionHelper.requestPermission(permissionLauncher)
        }
    }

    val isTv = isTvDevice()

    Box(Modifier.fillMaxSize().background(Color.White)) {
        MainScreen(activity = activity, modifier = Modifier.fillMaxSize(), intentUrl = intentUrl)

        if(isTv) {
            TvKioskInputOverlay(onTap = {
                idleController.onUserInteraction()
                unlockHandler.registerTap()
            })
        } else {
            TouchKioskInputOverlay(
                onTap = { unlockHandler.registerTap() },
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }

        val idleFocusRequester = remember { FocusRequester() }

        if (isIdleMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable {
                        idleController.onUserInteraction()
                    }
                    .focusable()
                    .focusRequester(idleFocusRequester)
                    .onKeyEvent {
                        if (it.key == Key.DirectionCenter &&
                            it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                            idleController.onUserInteraction()
                            true
                        } else {
                            false
                        }
                    },
                contentAlignment = Alignment.Center
            ) {}
        }
    }
}