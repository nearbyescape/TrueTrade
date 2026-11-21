package com.signalbot.app

import android.Manifest
import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.signalbot.app.data.ActionView
import com.signalbot.app.data.AppSettings
import com.signalbot.app.data.ScanResult
import com.signalbot.app.data.Store
import com.signalbot.app.scan.Scanner
import com.signalbot.app.ui.JournalScreen
import com.signalbot.app.ui.ResultsScreen
import com.signalbot.app.ui.SettingsScreen
import com.signalbot.app.ui.TodayScreen
import com.signalbot.app.work.Notifier
import com.signalbot.app.work.ScanScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { AppRoot() } }
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    val settings = AppSettings(app)

    var scan by mutableStateOf<ScanResult?>(store.lastScan())
        private set
    var journal by mutableStateOf<List<ActionView>>(store.journal())
        private set
    var scanning by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun scanNow() {
        if (scanning) return
        scanning = true
        message = null
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val result = withContext(Dispatchers.IO) {
                    Scanner(app).run().also { Notifier.notifyNew(app, it) }
                }
                scan = result
                journal = store.journal()
                val failed = result.assets.count { it.error != null }
                if (failed > 0) message = "$failed asset(s) couldn't load. Check your internet and try again."
            } catch (e: Exception) {
                message = "Scan failed: ${e.message}"
            } finally {
                scanning = false
            }
        }
    }

    fun ensureBackground() = ScanScheduler.ensureScheduled(getApplication())
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(primary = Color(0xFF7FD1AE), secondary = Color(0xFFF2B36B))
    } else {
        lightColorScheme(primary = Color(0xFF1E7A57), secondary = Color(0xFFB8641A))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        vm.ensureBackground()
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(context)) {
            askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (vm.scan == null) vm.scanNow()
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        "Today" to Icons.Filled.Home,
        "Results" to Icons.Filled.Star,
        "Journal" to Icons.Filled.DateRange,
        "Settings" to Icons.Filled.Settings,
    )
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> TodayScreen(vm)
                1 -> ResultsScreen(vm)
                2 -> JournalScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
}
