package com.houxinzhe.pib

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
        handleShare(intent)
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
                            Page.ABOUT -> AboutPage(vm)
                            null -> {}
                        }
                    }
                    AnimatedVisibility(vm.sidebar, enter = slideInHorizontally { -it } + fadeIn(), exit = slideOutHorizontally { -it } + fadeOut()) {
                        Sidebar(vm)
                    }
                    vm.confirm?.let { ConfirmDialog(it, vm::answerConfirm) }
                    if (!vm.privacyAccepted) PrivacyConsentSheet { vm.acceptPrivacy() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { shared ->
                vm.page = null
                vm.tab = Tab.HOME
                vm.sendHome("帮我处理这段内容：\n$shared")
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
}
