package io.github.thoitiet.emuse.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thoitiet.emuse.ui.theme.StatusColors
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** State for the Home screen, refreshed by MainActivity. */
data class HomeUiState(
    val serviceRunning: Boolean = false,
    val mcpPort: Int = 18789,
    val hasRoot: Boolean = false,
    val overlayEnabled: Boolean = false,
    val tunnelEnabled: Boolean = false,
    val tunnelUrl: String = "",
)

/**
 * Home screen: status card (Service / MCP local / Root) + service toggles
 * (Chạy nền / Bóng nổi / Tunnel) in Miuix style.
 */
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onServiceToggle: (Boolean) -> Unit,
    onOverlayToggle: (Boolean) -> Unit,
    onTunnelToggle: (Boolean) -> Unit,
    onTunnelUrlClick: () -> Unit,
    bottomPadding: Dp = 0.dp,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding),
    ) {
        // ---- status card ----
        item(key = "status") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp),
                cornerRadius = CardDefaults.CornerRadius,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusRow(
                        label = "Service",
                        value = if (uiState.serviceRunning) "đang chạy" else "đã dừng",
                        ok = uiState.serviceRunning,
                    )
                    StatusRow(
                        label = "MCP local",
                        value = "127.0.0.1:${uiState.mcpPort}",
                        ok = true,
                    )
                    StatusRow(
                        label = "Root",
                        value = if (uiState.hasRoot) "có" else "không",
                        ok = uiState.hasRoot,
                    )
                }
            }
        }

        // ---- section: Dịch vụ ----
        item(key = "svc-title") {
            SmallTitle(text = "Dịch vụ")
        }
        item(key = "svc-card") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                cornerRadius = CardDefaults.CornerRadius,
            ) {
                Column {
                    SwitchPreference(
                        title = "Chạy nền",
                        summary = "Foreground service: MCP local + tunnel",
                        checked = uiState.serviceRunning,
                        onCheckedChange = onServiceToggle,
                    )
                    SwitchPreference(
                        title = "Bóng nổi",
                        summary = "Nút nổi hiển thị log lệnh",
                        checked = uiState.overlayEnabled,
                        onCheckedChange = onOverlayToggle,
                    )
                    SwitchPreference(
                        title = "Tunnel",
                        summary = when {
                            uiState.tunnelUrl.isNotEmpty() -> uiState.tunnelUrl
                            uiState.tunnelEnabled -> "Đang tạo tunnel…"
                            else -> "Đã tắt"
                        },
                        checked = uiState.tunnelEnabled,
                        onCheckedChange = onTunnelToggle,
                    )
                    if (uiState.tunnelUrl.isNotEmpty()) {
                        Text(
                            text = "URL: ${uiState.tunnelUrl}\n(bấm để copy)",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onTunnelUrlClick)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        item(key = "spacer") {
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            color = MiuixTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            color = if (ok) StatusColors.healthy else StatusColors.danger,
        )
    }
}
