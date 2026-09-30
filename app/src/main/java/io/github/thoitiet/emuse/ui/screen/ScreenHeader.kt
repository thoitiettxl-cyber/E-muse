package io.github.thoitiet.emuse.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Eta-style large title + subtitle header for each tab.
 * Placed as the first item of the screen's LazyColumn.
 */
@Composable
fun ScreenHeader(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 4.dp),
    ) {
        Text(
            text = title,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = subtitle,
            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
