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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thoitiet.emuse.ui.theme.StatusColors
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One permission row state. */
data class PermRowState(
    val key: String,
    val title: String,
    val subtitle: String,
    val statusText: String,
    val ok: Boolean,
    val iconColor: Color,
    val clickable: Boolean = true,
)

/**
 * Permissions screen: app runtime permissions + special system permissions
 * + device root status. Miuix style, following Camera2Magit's grouped cards.
 */
@Composable
fun PermissionsScreen(
    appPerms: List<PermRowState>,
    sysPerms: List<PermRowState>,
    rootState: PermRowState,
    onRowClick: (key: String) -> Unit,
    topPadding: Dp = 0.dp,
    bottomPadding: Dp = 0.dp,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
    ) {
        item(key = "title-app") {
            SmallTitle(text = "Quyền ứng dụng")
        }
        item(key = "card-app") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                cornerRadius = CardDefaults.CornerRadius,
            ) {
                Column {
                    appPerms.forEachIndexed { i, row ->
                        PermRow(
                            row = row,
                            onClick = { onRowClick(row.key) },
                        )
                        if (i != appPerms.lastIndex) {
                            top.yukonga.miuix.kmp.basic.HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                }
            }
        }

        item(key = "title-sys") {
            SmallTitle(text = "Quyền hệ thống đặc biệt")
        }
        item(key = "card-sys") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                cornerRadius = CardDefaults.CornerRadius,
            ) {
                Column {
                    sysPerms.forEachIndexed { i, row ->
                        PermRow(
                            row = row,
                            onClick = { onRowClick(row.key) },
                        )
                        if (i != sysPerms.lastIndex) {
                            top.yukonga.miuix.kmp.basic.HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                }
            }
        }

        item(key = "title-dev") {
            SmallTitle(text = "Thiết bị")
        }
        item(key = "card-dev") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                cornerRadius = CardDefaults.CornerRadius,
            ) {
                PermRow(row = rootState, onClick = null)
            }
        }

        item(key = "hint") {
            Text(
                text = "Bấm vào từng dòng để cấp quyền hoặc mở trang Cài đặt tương ứng.",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
        item(key = "spacer") {
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PermRow(row: PermRowState, onClick: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Colored circle with first letter.
        Text(
            text = row.title.first().uppercase(),
            color = row.iconColor,
            modifier = Modifier.padding(end = 12.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(text = row.title)
            Text(
                text = row.subtitle,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
        Text(
            text = row.statusText,
            color = if (row.ok) StatusColors.healthy else StatusColors.warning,
            modifier = Modifier.padding(end = if (onClick != null) 6.dp else 0.dp),
        )
        if (onClick != null) {
            Text(
                text = "›",
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
    }
}
