package com.houxinzhe.pib

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.houxinzhe.pib.ui.AboutPage
import com.houxinzhe.pib.ui.AllowlistPage
import com.houxinzhe.pib.ui.AssistantScreen
import com.houxinzhe.pib.ui.BridgePage
import com.houxinzhe.pib.ui.BridgePairConfirmDialog
import com.houxinzhe.pib.ui.ButlerPage
import com.houxinzhe.pib.ui.CompanionScreen
import com.houxinzhe.pib.ui.ConfirmDialog
import com.houxinzhe.pib.ui.LocalPalette
import com.houxinzhe.pib.ui.MoreScreen
import com.houxinzhe.pib.ui.Page
import com.houxinzhe.pib.ui.PibTheme
import com.houxinzhe.pib.ui.PibViewModel
import com.houxinzhe.pib.ui.PrivacyConsentSheet
import com.houxinzhe.pib.ui.SettingsPage
import com.houxinzhe.pib.ui.Sidebar
import com.houxinzhe.pib.ui.Tab
import com.houxinzhe.pib.ui.TabBar
import com.houxinzhe.pib.ui.TasksPage
import com.houxinzhe.pib.ui.ToolsPage

class MainActivity : ComponentActivity() {
    private val vm: PibViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        vm.answerPermission(satisfied(result.keys.toList()) { p -> result[p] == true || granted(p) })
    }

    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleInboundIntent(intent)
        }
        setContent {
            PibTheme {
                val req = vm.permission
                LaunchedEffect(req) {
                    if (req != null) {
                        if (satisfied(req.permissions, ::granted)) vm.answerPermission(true)
                        else permissionLauncher.launch(req.permissions.toTypedArray())
                    }
                }
                BackHandler(enabled = vm.page != null) {
                    vm.page = if (vm.page == Page.ALLOWLIST) Page.TOOLS else null
                }
                BackHandler(enabled = vm.page == null && !vm.sidebar && vm.tab != Tab.HOME) { vm.tab = Tab.HOME }
                Box(Modifier.fillMaxSize().background(LocalPalette.current.background)) {
                    Column(Modifier.fillMaxSize().imePadding()) {
                        Box(Modifier.weight(1f)) {
                            when (vm.tab) {
                                Tab.HOME -> AssistantScreen(vm)
                                Tab.COMPANION -> CompanionScreen(vm)
                                Tab.MORE -> MoreScreen(vm)
                            }
                        }
                        if (!WindowInsets.isImeVisible) TabBar(vm.tab) { vm.tab = it }
                    }
                    AnimatedVisibility(vm.page != null, enter = slideInHorizontally { it }, exit = slideOutHorizontally { it }) {
                        when (vm.page) {
                            Page.SETTINGS -> SettingsPage(vm)
                            Page.TASKS -> TasksPage(vm)
                            Page.TOOLS -> ToolsPage(vm)
                            Page.ALLOWLIST -> AllowlistPage(vm)
                            Page.BUTLER -> ButlerPage(vm)
                            Page.BRIDGE -> BridgePage(vm)
                            Page.ABOUT -> AboutPage(vm)
                            null -> {}
                        }
                    }
                    AnimatedVisibility(vm.sidebar, enter = slideInHorizontally { -it } + fadeIn(), exit = slideOutHorizontally { -it } + fadeOut()) {
                        Sidebar(vm)
                    }
                    vm.confirm?.let { ConfirmDialog(it, vm::answerConfirm) }
                    if (vm.privacyAccepted) {
                        vm.pendingBridgePair?.let { BridgePairConfirmDialog(it, vm::answerBridgePairing) }
                    }
                    if (!vm.privacyAccepted) PrivacyConsentSheet { vm.acceptPrivacy() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInboundIntent(intent)
    }

    private fun handleInboundIntent(intent: Intent?) {
        if (!shouldConsumeInboundIntent(intent)) return
        intent?.putExtra(EXTRA_CONSUMED, true)
        if (intent?.action == Intent.ACTION_VIEW) {
            val dataStr = intent.dataString.orEmpty()
            if (!vm.requestBridgePairing(dataStr)) {
                Toast.makeText(this, "配对链接无效，或公网地址未使用 https://", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { shared ->
                vm.stageSharedText(shared)
            }
        }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** Coarse location alone is enough for route/weather lookups. */
    private fun satisfied(perms: List<String>, ok: (String) -> Boolean): Boolean {
        val location = setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val others = perms.filter { it !in location }
        val locs = perms.filter { it in location }
        return others.all(ok) && (locs.isEmpty() || locs.any(ok))
    }

    companion object {
        const val EXTRA_CONSUMED = "com.houxinzhe.pib.INTENT_CONSUMED"

        internal fun shouldConsumeInboundAction(
            action: String?,
            alreadyConsumed: Boolean,
            isRestoredState: Boolean,
        ): Boolean {
            if (isRestoredState || alreadyConsumed || action == null) return false
            return action == Intent.ACTION_VIEW || action == Intent.ACTION_SEND
        }

        internal fun shouldConsumeInboundIntent(intent: Intent?, isRestoredState: Boolean = false): Boolean =
            shouldConsumeInboundAction(
                action = intent?.action,
                alreadyConsumed = intent?.getBooleanExtra(EXTRA_CONSUMED, false) == true,
                isRestoredState = isRestoredState,
            )
    }
}
