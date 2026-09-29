package io.github.thoitiet.emuse.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** State for the Settings screen. */
data class SettingsUiState(
    val apiKey: String = "",
    val tunnelEnabled: Boolean = false,
    val tunnelUrl: String = "",
    val tunnelToken: String = "",
    val tunnelHostname: String = "",
)

/**
 * Settings screen: API key + Cloudflare Tunnel config. Miuix style.
 * Text fields use [TextFieldState] so the Activity can read values on pause.
 */
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    apiKeyState: TextFieldState,
    tunnelTokenState: TextFieldState,
    tunnelHostState: TextFieldState,
    onTunnelToggle: (Boolean) -> Unit,
    onTunnelUrlClick: () -> Unit,
    bottomPadding: Dp = 0.dp,
) {
    // Miuix TextField uses value/onValueChange (not TextFieldState).
    // Keep local String state synced with the Activity's TextFieldState.
    var apiKeyText by remember { mutableStateOf(uiState.apiKey) }
    var tunnelTokenText by remember { mutableStateOf(uiState.tunnelToken) }
    var tunnelHostText by remember { mutableStateOf(uiState.tunnelHostname) }

    // Sync initial values from uiState (only on first composition).
    LaunchedEffect(Unit) {
        if (apiKeyState.text.isEmpty() && uiState.apiKey.isNotEmpty()) {
            apiKeyState.edit { append(uiState.apiKey) }
        }
        if (tunnelTokenState.text.isEmpty() && uiState.tunnelToken.isNotEmpty()) {
            tunnelTokenState.edit { append(uiState.tunnelToken) }
        }
        if (tunnelHostState.text.isEmpty() && uiState.tunnelHostname.isNotEmpty()) {
            tunnelHostState.edit { append(uiState.tunnelHostname) }
        }
        // Initialize local states from TextFieldState (in case Activity pre-filled).
        apiKeyText = apiKeyState.text.toString()
        tunnelTokenText = tunnelTokenState.text.toString()
        tunnelHostText = tunnelHostState.text.toString()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding),
    ) {
        // ---- section: Truy cập ----
        item(key = "title-access") {
            SmallTitle(text = "Truy cập")
        }
        item(key = "card-access") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                cornerRadius = CardDefaults.CornerRadius,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text(
                        text = "API key",
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    TextField(
                        value = apiKeyText,
                        onValueChange = {
                            apiKeyText = it
                            apiKeyState.edit { replace(0, length, it) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Text(
                        text = "EMUSE_API_KEY — guard cho MCP endpoint",
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        item(key = "hint-access") {
            Text(
                text = "Client gọi MCP phải gửi header EMUSE_API_KEY.",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }

        // ---- section: Cloudflare Tunnel ----
        item(key = "title-tunnel") {
            SmallTitle(text = "Cloudflare Tunnel")
        }
        item(key = "card-tunnel") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                cornerRadius = CardDefaults.CornerRadius,
            ) {
                Column {
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Text(
                            text = "Token",
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        TextField(
                            value = tunnelTokenText,
                            onValueChange = {
                                tunnelTokenText = it
                                tunnelTokenState.edit { replace(0, length, it) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "Named tunnel token (trống = Quick Tunnel)",
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                        )
                        Text(
                            text = "Hostname",
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        TextField(
                            value = tunnelHostText,
                            onValueChange = {
                                tunnelHostText = it
                                tunnelHostState.edit { replace(0, length, it) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "vd mcp.example.com",
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
        item(key = "hint-tunnel") {
            Text(
                text = "Có token + hostname cố định → URL không đổi sau mỗi lần bật.",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
        item(key = "spacer") {
            Spacer(Modifier.height(24.dp))
        }
    }
}
