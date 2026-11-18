package com.aura.launcher

import android.content.Intent
import android.content.IntentSender
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.aura.launcher.ui.AuraRoot
import com.aura.launcher.ui.theme.AuraTheme
import kotlinx.coroutines.CompletableDeferred

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingPermission?.complete(granted)
            pendingPermission = null
        }
    private var pendingIntentSender: CompletableDeferred<Intent?>? = null
    private val intentSenderLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            pendingIntentSender?.complete(if (result.resultCode == RESULT_OK) result.data else null)
            pendingIntentSender = null
        }
    private val multiPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            vm.onResumed()
            vm.refreshWeather()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        ActivityBridge.attach(this)
        com.aura.launcher.ui.NetImages.installDiskCache(this)
        setContent {
            AuraTheme {
                AuraRoot(vm = vm, requestPermissions = { multiPermissionLauncher.launch(it.toTypedArray()) })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) vm.onHomeIntent()
    }

    override fun onResume() {
        super.onResume()
        ActivityBridge.attach(this)
        vm.onResumed()
    }

    override fun onStop() {
        super.onStop()
        vm.onStopped()
    }

    override fun onDestroy() {
        ActivityBridge.detach(this)
        super.onDestroy()
    }

    /** Runs a Google Play services resolution (account picker / consent) and returns its result data. */
    suspend fun resolveIntentSender(sender: IntentSender): Intent? {
        pendingIntentSender?.complete(null)
        val deferred = CompletableDeferred<Intent?>()
        pendingIntentSender = deferred
        intentSenderLauncher.launch(IntentSenderRequest.Builder(sender).build())
        return deferred.await()
    }

    suspend fun requestPermissionAsync(permission: String): Boolean {
        pendingPermission?.complete(false)
        val deferred = CompletableDeferred<Boolean>()
        pendingPermission = deferred
        permissionLauncher.launch(permission)
        return deferred.await()
    }
}
