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
import com.signalbot.app.data.SetupResult
import com.signalbot.app.data.SetupTest
import com.signalbot.app.data.SetupView
import com.signalbot.app.scan.SetupScanner
import com.signalbot.app.ui.CandleIcon
import com.signalbot.app.ui.ChartContent
import com.signalbot.app.ui.JournalContent
import com.signalbot.app.ui.SetupSettingsState
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
import com.signalbot.engine.model.Timeframe
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

    // ---- Her break-and-retest setup
    var setup by mutableStateOf<SetupResult?>(store.lastSetup())
        private set
    var chartAsset by mutableStateOf(Assets.DEFAULT.first().id)
        private set
    var chartTf by mutableStateOf(settings.setupTimeframe)
        private set
    var chartView by mutableStateOf<SetupView?>(null)
        private set
    var chartLoading by mutableStateOf(false)
        private set
    var setupTest by mutableStateOf<SetupTest?>(store.lastSetupTest())
        private set
    var testing by mutableStateOf(false)
        private set
    var testProgress by mutableStateOf<String?>(null)
        private set
    private val chartCache = HashMap<String, Pair<Long, SetupView>>()
    private var chartRequest = 0

    /** Shows [assetId] on [tf]: instantly from the last scan or cache, then fresh candles in the background. */
    fun openChart(assetId: String = chartAsset, tf: String = chartTf, force: Boolean = false) {
        chartAsset = assetId
        chartTf = tf
        val key = "$assetId|$tf"
        val now = System.currentTimeMillis()
        val cached = chartCache[key]
        val fromScan = setup?.takeIf { it.timeframe == tf }?.assets?.firstOrNull { it.assetId == assetId && it.chart != null }
        chartView = cached?.second ?: fromScan ?: chartView?.takeIf { it.assetId == assetId && it.timeframe == tf }
        if (!force && cached != null && now - cached.first < 10 * 60_000L) return
        val request = ++chartRequest
        chartLoading = true
        viewModelScope.launch {
            val v = withContext(Dispatchers.IO) {
                val asset = Assets.byId(assetId) ?: Assets.BTC
                SetupScanner(getApplication<Application>()).analyze(asset, Timeframe.parse(tf))
            }
            if (v.error == null) chartCache[key] = System.currentTimeMillis() to v
            if (request == chartRequest) {
                chartView = if (v.error != null && chartView != null) chartView else v
                chartLoading = false
            }
        }
    }

    fun runSetupTest() {
        if (testing) return
        testing = true
        testProgress = "Starting"
        viewModelScope.launch {
            try {
                setupTest = withContext(Dispatchers.IO) {
                    SetupScanner(getApplication<Application>()).backtest { p -> viewModelScope.launch { testProgress = p } }
                }
                testProgress = null
            } catch (e: Exception) {
                testProgress = "Test stopped: ${e.message}"
            } finally {
                testing = false
            }
        }
    }

    fun setupSettingsState() = SetupSettingsState(settings.setupTimeframe, settings.setupTarget, settings.setupTrend,
        trim(settings.setupRiskPct), settings.setupStepAlerts)

    fun saveSetup(s: SetupSettingsState): SetupSettingsState {
        val tfChanged = s.timeframe != settings.setupTimeframe
        settings.setupTimeframe = s.timeframe
        settings.setupTarget = s.target
        settings.setupTrend = s.trend
        settings.setupStepAlerts = s.steps
        s.risk.toDoubleOrNull()?.takeIf { it > 0 && it <= 5 }?.let { settings.setupRiskPct = it }
        chartCache.clear()
        if (tfChanged) {
            chartTf = s.timeframe
            ScanScheduler.ensureScheduled(getApplication<Application>())
        }
        return setupSettingsState()
    }

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
                setup = withContext(Dispatchers.IO) {
                    runCatching { SetupScanner(app).run().also { Notifier.notifySetup(app, it) } }.getOrNull()
                } ?: setup
                chartCache.clear()
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
    // null icon = the drawn candles icon for the chart.
    val tabs = listOf("Today" to Icons.Filled.Home, "Chart" to null, "Record" to Icons.Filled.Star,
        "Journal" to Icons.Filled.DateRange, "Settings" to Icons.Filled.Settings)
    Scaffold(
        containerColor = Palette.Night,
        bottomBar = {
            NavigationBar(containerColor = Palette.Card) {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { if (icon == null) CandleIcon() else Icon(icon, contentDescription = null) },
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
                0 -> TodayContent(vm.scan, vm.scanning, vm.message, onCheck = { vm.scanNow() }, setup = vm.setup,
                    onOpenChart = { id -> vm.openChart(id, vm.settings.setupTimeframe); tab = 1 })
                1 -> {
                    LaunchedEffect(Unit) { if (vm.chartView == null) vm.openChart() }
                    ChartContent(
                        assets = Assets.DEFAULT.filter { vm.settings.isEnabled(it.id) }.ifEmpty { Assets.DEFAULT },
                        assetId = vm.chartAsset, timeframe = vm.chartTf, setupTimeframe = vm.settings.setupTimeframe,
                        view = vm.chartView, loading = vm.chartLoading,
                        onAsset = { vm.openChart(assetId = it) }, onTimeframe = { vm.openChart(tf = it) },
                        onRefresh = { vm.openChart(force = true) },
                    )
                }
                2 -> RecordContent(vm.scan, vm.setupTest, vm.testing, vm.testProgress, onRunTest = { vm.runSetupTest() })
                3 -> JournalContent(vm.journal)
                else -> SettingsContent(
                    initial = vm.settingsState(),
                    assets = Assets.DEFAULT,
                    onSave = { vm.save(it) },
                    onToggle = { id, on -> vm.toggle(id, on) },
                    onBatterySettings = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                    setupInitial = vm.setupSettingsState(),
                    onSetupChange = { vm.saveSetup(it) },
                )
            }
        }
    }
}
