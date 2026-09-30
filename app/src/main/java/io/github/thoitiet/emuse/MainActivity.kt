package io.github.thoitiet.emuse

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.thoitiet.emuse.exec.ScreenCapture
import io.github.thoitiet.emuse.exec.ShellExecutor
import io.github.thoitiet.emuse.mcp.TOOL_BY_NAME
import io.github.thoitiet.emuse.ui.screen.HomeScreen
import io.github.thoitiet.emuse.ui.screen.HomeUiState
import io.github.thoitiet.emuse.ui.screen.PermRowState
import io.github.thoitiet.emuse.ui.screen.PermissionsScreen
import io.github.thoitiet.emuse.ui.screen.SettingsScreen
import io.github.thoitiet.emuse.ui.screen.SettingsUiState
import io.github.thoitiet.emuse.ui.screen.ToolsScreen
import io.github.thoitiet.emuse.ui.theme.EmuseTheme
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Settings

/**
 * Main activity (Compose + Miuix, like Camera2Magit): bottom nav with 4 tabs
 * (Trang chủ / Tools / Quyền / Cài đặt) via HorizontalPager + NavigationBar.
 * All business logic (Prefs, MuseService, FloatingOverlay, permissions) is
 * preserved from the old View-based activities.
 */
class MainActivity : ComponentActivity() {

    private val bgExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var rootCached: Boolean? = null

    // ---- UI state (Compose) ----
    private var homeState by mutableStateOf(HomeUiState())
    private var groupEnabled by mutableStateOf(mapOf<ToolGroup, Boolean>())
    private var appPerms by mutableStateOf(listOf<PermRowState>())
    private var sysPerms by mutableStateOf(listOf<PermRowState>())
    private var rootPermState by mutableStateOf(
        PermRowState("root", "Root", "su qua KernelSU/Magisk", "Đang kiểm tra…", false,
            androidx.compose.ui.graphics.Color(0xFF4CAF50), clickable = false)
    )
    private var settingsState by mutableStateOf(SettingsUiState())

    private val rowPermsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val denied = grants.filterValues { !it }.keys.toList()
        val granted = grants.size - denied.size
        val neverAsk = denied.filter {
            !ActivityCompat.shouldShowRequestPermissionRationale(this, it)
        }
        val msg = buildString {
            append("Đã cấp $granted/${grants.size} quyền")
            if (neverAsk.isNotEmpty()) {
                append(". ${neverAsk.size} quyền bị từ chối vĩnh viễn: " +
                    "mở Cài đặt > Ứng dụng > E-Muse > Quyền để cấp thủ công")
            }
        }
        toast(msg)
        refreshAll()
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> refreshAll() }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, MuseService::class.java)
                    .setAction(MuseService.ACTION_START_PROJECTION)
                    .putExtra(MuseService.EXTRA_MP_RESULT_CODE, result.resultCode)
                    .putExtra(MuseService.EXTRA_MP_DATA, result.data),
            )
            toast("Đã gửi quyền chụp màn hình cho service")
        } else {
            toast("Chưa cấp quyền chụp màn hình")
        }
        refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Text field states for Settings (survive recomposition).
        val apiKeyState = androidx.compose.foundation.text.input.TextFieldState()
        val tunnelTokenState = androidx.compose.foundation.text.input.TextFieldState()
        val tunnelHostState = androidx.compose.foundation.text.input.TextFieldState()

        setContent {
            EmuseTheme {
                MainScreen(
                    apiKeyState = apiKeyState,
                    tunnelTokenState = tunnelTokenState,
                    tunnelHostState = tunnelHostState,
                )
            }
        }

        // POST_NOTIFICATIONS for foreground service (Android 13+).
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // hasRoot() takes seconds: compute once in background.
        bgExecutor.execute {
            val r = ShellExecutor.hasRoot()
            rootCached = r
            runOnUiThread { if (!isFinishing && !isDestroyed) refreshAll() }
        }
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        // Persist settings inputs (like old SettingsActivity.onPause).
        // Note: TextFieldStates are read in MainScreen via callback.
        refreshAll()
    }

    override fun onDestroy() {
        bgExecutor.shutdownNow()
        super.onDestroy()
    }

    @Composable
    private fun MainScreen(
        apiKeyState: androidx.compose.foundation.text.input.TextFieldState,
        tunnelTokenState: androidx.compose.foundation.text.input.TextFieldState,
        tunnelHostState: androidx.compose.foundation.text.input.TextFieldState,
    ) {
        val pagerState = rememberPagerState(pageCount = { 4 })
        val scope = rememberCoroutineScope()
        var selectedTab by remember { mutableStateOf(0) }

        // Sync pager with tab selection.
        LaunchedEffect(pagerState.currentPage) {
            selectedTab = pagerState.currentPage
            // Refresh when switching tabs.
            refreshAll()
        }

        val tabs = listOf(
            TabItem("Trang chủ", MiuixIcons.Home),
            TabItem("Tools", MiuixIcons.GridView),
            TabItem("Quyền", MiuixIcons.Info),
            TabItem("Cài đặt", MiuixIcons.Settings),
        )

        Scaffold(
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = {
                                selectedTab = index
                                scope.launch { pagerState.animateScrollToPage(index) }
                            },
                            icon = tab.icon,
                            label = tab.label,
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { padding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> HomeScreen(
                        uiState = homeState,
                        onServiceToggle = ::onServiceToggle,
                        onOverlayToggle = ::onOverlayToggle,
                        onTunnelToggle = ::onTunnelToggle,
                        onTunnelUrlClick = ::copyTunnelUrl,
                        topPadding = padding.calculateTopPadding(),
                        bottomPadding = padding.calculateBottomPadding(),
                    )
                    1 -> ToolsScreen(
                        groupEnabled = groupEnabled,
                        onGroupToggle = ::onGroupToggle,
                        topPadding = padding.calculateTopPadding(),
                        bottomPadding = padding.calculateBottomPadding(),
                    )
                    2 -> PermissionsScreen(
                        appPerms = appPerms,
                        sysPerms = sysPerms,
                        rootState = rootPermState,
                        onRowClick = ::onPermRowClick,
                        topPadding = padding.calculateTopPadding(),
                        bottomPadding = padding.calculateBottomPadding(),
                    )
                    3 -> SettingsScreen(
                        uiState = settingsState,
                        apiKeyState = apiKeyState,
                        tunnelTokenState = tunnelTokenState,
                        tunnelHostState = tunnelHostState,
                        onTunnelToggle = ::onTunnelToggle,
                        onTunnelUrlClick = ::copyTunnelUrl,
                        topPadding = padding.calculateTopPadding(),
                        bottomPadding = padding.calculateBottomPadding(),
                    )
                }
            }
        }

        // Persist settings inputs when leaving the Settings tab.
        LaunchedEffect(selectedTab) {
            if (selectedTab != 3) {
                persistSettingsInputs(
                    apiKeyState.text.toString(),
                    tunnelTokenState.text.toString(),
                    tunnelHostState.text.toString(),
                )
            }
        }
    }

    private data class TabItem(val label: String, val icon: ImageVector)

    // ---- Home actions ----

    private fun onServiceToggle(checked: Boolean) {
        if (checked) {
            ContextCompat.startForegroundService(
                this, Intent(this, MuseService::class.java),
            )
        } else {
            startService(
                Intent(this, MuseService::class.java)
                    .setAction(MuseService.ACTION_STOP),
            )
        }
        refreshAll()
    }

    private fun onOverlayToggle(checked: Boolean) {
        val prefs = Prefs(this)
        if (checked && !Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            toast("Cấp quyền hiển thị rồi bật lại")
            refreshAll()
            return
        }
        prefs.overlayEnabled = checked
        if (checked) FloatingOverlay.show(this) else FloatingOverlay.hide()
        refreshAll()
    }

    private fun onTunnelToggle(checked: Boolean) {
        val prefs = Prefs(this)
        prefs.tunnelEnabled = checked
        ContextCompat.startForegroundService(
            this,
            Intent(this, MuseService::class.java)
                .setAction(MuseService.ACTION_SET_TUNNEL)
                .putExtra(MuseService.EXTRA_TUNNEL_ENABLED, checked),
        )
        toast(if (checked) "Đang bật tunnel…" else "Đã tắt tunnel")
        refreshAll()
        lifecycleScope.launch {
            delay(5000); refreshAll()
            delay(10000); refreshAll()
            delay(15000); refreshAll()
        }
    }

    private fun copyTunnelUrl() {
        val url = Prefs(this).tunnelUrl
        if (url.startsWith("http")) {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("tunnel", url))
            toast("Đã copy URL tunnel")
        }
    }

    // ---- Tools actions ----

    private fun onGroupToggle(group: ToolGroup, checked: Boolean) {
        Prefs(this).setGroupEnabled(group, checked)
        toast(if (checked) "Đã bật: ${group.title}" else "Đã tắt: ${group.title}")
        refreshAll()
    }

    // ---- Permissions actions ----

    private fun onPermRowClick(key: String) {
        when (key) {
            "contacts" -> requestPerms(arrayOf(Manifest.permission.READ_CONTACTS))
            "call_log" -> requestPerms(arrayOf(Manifest.permission.READ_CALL_LOG))
            "sms" -> requestPerms(arrayOf(Manifest.permission.READ_SMS))
            "calendar" -> requestPerms(arrayOf(Manifest.permission.READ_CALENDAR))
            "media" -> requestPerms(
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO))
            "audio" -> requestPerms(arrayOf(Manifest.permission.READ_MEDIA_AUDIO))
            "notifications" -> requestPerms(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            "location" -> requestPerms(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION))
            "accessibility" -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            "screen_capture" -> {
                val mgr = getSystemService(MediaProjectionManager::class.java)
                projectionLauncher.launch(mgr.createScreenCaptureIntent())
            }
            "install_apk" -> startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")))
            "usage_access" -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun requestPerms(perms: Array<String>) {
        rowPermsLauncher.launch(perms)
    }

    // ---- Settings actions ----

    private fun persistSettingsInputs(apiKey: String, token: String, host: String) {
        val prefs = Prefs(this)
        val newToken = token.trim()
        val newHost = host.trim()
        val changed = newToken != prefs.tunnelToken || newHost != prefs.tunnelHostname
        prefs.apiKey = apiKey.trim()
        prefs.tunnelToken = newToken
        prefs.tunnelHostname = newHost
        if (changed && prefs.tunnelEnabled) {
            val intent = Intent(this, MuseService::class.java)
                .setAction(MuseService.ACTION_RESTART_TUNNEL)
            if (MuseService.running) startService(intent)
            else ContextCompat.startForegroundService(this, intent)
            toast("Đã đổi cấu hình tunnel — đang restart…")
        }
    }

    // ---- Refresh ----

    private fun refreshAll() {
        bgExecutor.execute {
            val root = rootCached ?: false
            val prefs = Prefs(this@MainActivity)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                homeState = HomeUiState(
                    serviceRunning = MuseService.running,
                    mcpPort = prefs.mcpPort,
                    hasRoot = root,
                    overlayEnabled = prefs.overlayEnabled,
                    tunnelEnabled = prefs.tunnelEnabled,
                    tunnelUrl = prefs.tunnelUrl,
                )
                groupEnabled = ToolGroup.entries.associateWith { prefs.isGroupEnabled(it) }
                settingsState = SettingsUiState(
                    apiKey = prefs.apiKey,
                    tunnelEnabled = prefs.tunnelEnabled,
                    tunnelUrl = prefs.tunnelUrl,
                    tunnelToken = prefs.tunnelToken,
                    tunnelHostname = prefs.tunnelHostname,
                )
                refreshPermissions()
            }
        }
    }

    private fun refreshPermissions() {
        val root = rootCached
        appPerms = buildAppPerms()
        sysPerms = buildSysPerms()
        rootPermState = PermRowState(
            key = "root",
            title = "Root",
            subtitle = "su qua KernelSU/Magisk",
            statusText = when (root) {
                true -> "Có"
                false -> "Không"
                null -> "Đang kiểm tra…"
            },
            ok = root == true,
            iconColor = androidx.compose.ui.graphics.Color(0xFF4CAF50),
            clickable = false,
        )
    }

    private fun buildAppPerms(): List<PermRowState> {
        val list = mutableListOf(
            permRow("contacts", "Danh bạ", "Tìm danh bạ qua MCP",
                arrayOf(Manifest.permission.READ_CONTACTS)),
            permRow("call_log", "Nhật ký cuộc gọi", "Tìm lịch sử cuộc gọi qua MCP",
                arrayOf(Manifest.permission.READ_CALL_LOG)),
            permRow("sms", "Tin nhắn SMS", "Tìm SMS, đọc mã OTP qua MCP",
                arrayOf(Manifest.permission.READ_SMS)),
            permRow("calendar", "Lịch", "Tìm sự kiện lịch qua MCP",
                arrayOf(Manifest.permission.READ_CALENDAR)),
        )
        // minSdk 33 now, so always use media permissions.
        list += permRow("media", "Ảnh & video", "Tìm ảnh/video trong MediaStore",
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO))
        list += permRow("audio", "Nhạc & ghi âm", "Tìm nhạc, file ghi âm",
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO))
        list += permRow("notifications", "Thông báo", "Hiện thông báo foreground service",
            arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        list += permRow("location", "Vị trí", "Vị trí hiện tại của máy",
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION))
        return list
    }

    private fun permRow(
        key: String, title: String, subtitle: String, perms: Array<String>,
    ): PermRowState {
        val ok = perms.all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        return PermRowState(
            key = key,
            title = title,
            subtitle = subtitle,
            statusText = if (ok) "Đã bật" else "Cần chú ý",
            ok = ok,
            iconColor = androidx.compose.ui.graphics.Color(0xFF2196F3),
        )
    }

    private fun buildSysPerms(): List<PermRowState> {
        val orange = androidx.compose.ui.graphics.Color(0xFFFF9800)
        return listOf(
            PermRowState(
                key = "accessibility",
                title = "Accessibility",
                subtitle = "Cần cho thao tác chạm/vuốt/gõ phím",
                statusText = if (accessibilityOn()) "Đã bật" else "Cần chú ý",
                ok = accessibilityOn(),
                iconColor = orange,
            ),
            PermRowState(
                key = "screen_capture",
                title = "Chụp màn hình",
                subtitle = "MediaProjection, hoặc root screencap",
                statusText = if (ScreenCapture.hasProjection() || rootCached == true)
                    "Đã bật" else "Cần chú ý",
                ok = ScreenCapture.hasProjection() || rootCached == true,
                iconColor = orange,
            ),
            PermRowState(
                key = "install_apk",
                title = "Cài đặt APK",
                subtitle = "app_install cần quyền này (Install unknown apps)",
                statusText = if (packageManager.canRequestPackageInstalls())
                    "Đã cấp" else "Chưa cấp",
                ok = packageManager.canRequestPackageInstalls(),
                iconColor = orange,
            ),
            PermRowState(
                key = "usage_access",
                title = "Truy cập dùng app",
                subtitle = "Đọc app vừa mở, thời gian dùng foreground",
                statusText = if (usageAccessGranted()) "Đã cấp" else "Chưa cấp",
                ok = usageAccessGranted(),
                iconColor = orange,
            ),
        )
    }

    private fun usageAccessGranted(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun accessibilityOn(): Boolean {
        if (MuseAccessibilityService.instance != null) return true
        val flat = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val me = ComponentName(this, MuseAccessibilityService::class.java).flattenToString()
        return flat.split(":").any { it.equals(me, ignoreCase = true) }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
