package com.signalbot.app

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.signalbot.app.data.ActionView
import com.signalbot.app.data.AppSettings
import com.signalbot.app.data.ScanResult
import com.signalbot.app.data.Store
import com.signalbot.app.scan.Scanner
import com.signalbot.app.ui.JournalContent
import com.signalbot.app.ui.RecordContent
import com.signalbot.app.ui.SettingsContent
import com.signalbot.app.ui.SettingsState
import com.signalbot.app.ui.TodayContent
import com.signalbot.app.ui.theme.AppTheme
import com.signalbot.app.ui.theme.Palette
import com.signalbot.app.ui.theme.Type
import com.signalbot.app.work.Notifier
import com.signalbot.app.work.ScanScheduler
import com.signalbot.engine.model.Assets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
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
                if (failed > 0) message = "$failed of ${result.assets.size} assets didn't load. Check your connection and try again."
            } catch (e: Exception) {
                message = "The check stopped: ${e.message}. Try again."
            } finally {
                scanning = false
            }
        }
    }

    fun settingsState() = SettingsState(
        trim(settings.capital), trim(settings.riskPerLegPct), trim(settings.leverage),
        Assets.DEFAULT.associate { it.id to settings.isEnabled(it.id) },
    )

    /** Saves valid values, keeps the old value for anything invalid, and returns what was stored. */
    fun save(s: SettingsState): SettingsState {
        s.capital.toDoubleOrNull()?.takeIf { it > 0 }?.let { settings.capital = it }
        s.riskPerLeg.toDoubleOrNull()?.takeIf { it > 0 && it <= 5 }?.let { settings.riskPerLegPct = it }
        s.leverage.toDoubleOrNull()?.takeIf { it >= 1 && it <= 20 }?.let { settings.leverage = it }
        return settingsState()
    }

    fun toggle(id: String, on: Boolean) = settings.setEnabled(id, on)

    fun ensureBackground() = ScanScheduler.ensureScheduled(getApplication())

    private fun trim(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
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
    val tabs = listOf("Today" to Icons.Filled.Home, "Record" to Icons.Filled.Star, "Journal" to Icons.Filled.DateRange, "Settings" to Icons.Filled.Settings)
    Scaffold(
        containerColor = Palette.Night,
        bottomBar = {
            NavigationBar(containerColor = Palette.Card) {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(label, style = Type.Tiny) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Palette.Saffron, selectedTextColor = Palette.Saffron, indicatorColor = Palette.Raised,
                            unselectedIconColor = Palette.Muted, unselectedTextColor = Palette.Muted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> TodayContent(vm.scan, vm.scanning, vm.message, onCheck = { vm.scanNow() })
                1 -> RecordContent(vm.scan)
                2 -> JournalContent(vm.journal)
                else -> SettingsContent(
                    initial = vm.settingsState(),
                    assets = Assets.DEFAULT,
                    onSave = { vm.save(it) },
                    onToggle = { id, on -> vm.toggle(id, on) },
                    onBatterySettings = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                )
            }
        }
    }
}
