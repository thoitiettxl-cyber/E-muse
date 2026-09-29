package io.github.thoitiet.emuse.ui.screen

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thoitiet.emuse.TOOL_GROUP_BY_NAME
import io.github.thoitiet.emuse.ToolGroup
import io.github.thoitiet.emuse.mcp.TOOL_BY_NAME
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Tools screen: one section per [ToolGroup] with a group on/off switch,
 * then a 2-column grid of tool cards. Miuix style.
 */
@Composable
fun ToolsScreen(
    groupEnabled: Map<ToolGroup, Boolean>,
    onGroupToggle: (ToolGroup, Boolean) -> Unit,
    bottomPadding: Dp = 0.dp,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding),
    ) {
        for (g in ToolGroup.entries) {
            item(key = "title-${g.name}") {
                SmallTitle(text = g.title)
            }
            item(key = "switch-${g.name}") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    cornerRadius = CardDefaults.CornerRadius,
                ) {
                    SwitchPreference(
                        title = "Bật nhóm",
                        summary = g.summary,
                        checked = groupEnabled[g] ?: true,
                        onCheckedChange = { onGroupToggle(g, it) },
                    )
                }
            }
            val tools = TOOL_GROUP_BY_NAME.filterValues { it == g }.keys.sorted()
            items(
                items = tools.chunked(2),
                key = { chunk -> "grid-${g.name}-${chunk.first()}" },
            ) { chunk ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (name in chunk) {
                        ToolCard(
                            name = name,
                            color = groupColor(g),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Pad odd rows so cards keep equal width.
                    if (chunk.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        // Meta tool tool_flags: always available, shown in its own section.
        item(key = "title-system") {
            SmallTitle(text = "Hệ thống")
        }
        item(key = "grid-system") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ToolCard(
                    name = "tool_flags",
                    color = Color(0xFF9E9E9E),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.weight(1f))
            }
        }
        item(key = "hint") {
            Text(
                text = "Tool bị chặn khi nhóm của nó tắt — kể cả khi tool_flags đang bật.",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariant,
            )
        }
        item(key = "spacer") {
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ToolCard(name: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        cornerRadius = CardDefaults.CornerRadius,
        // Miuix Card has its own padding; we add inner content padding.
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            // Colored circle with first letter, like the old UiKit.circleIcon.
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .padding(bottom = 8.dp),
            ) {
                Text(
                    text = name.first().uppercase(),
                    color = color,
                )
            }
            Text(
                text = name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = viDesc(name),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MiuixTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun groupColor(g: ToolGroup): Color = when (g) {
    ToolGroup.TERMINAL_FILE -> Color(0xFF2196F3)   // blue
    ToolGroup.DEVICE_DIRECT -> Color(0xFF4CAF50)   // green
    ToolGroup.SENSITIVE_READ -> Color(0xFFFF9800)  // orange
    ToolGroup.SENSITIVE_ACTION -> Color(0xFFF44336) // red
}

/** Short Vietnamese description per tool (fallback: original English). */
private fun viDesc(name: String): String =
    TOOL_VI_DESC[name]
        ?: TOOL_BY_NAME[name]?.description?.take(120)?.let { "$it…" }
        ?: name

// Moved from ToolsActivity.Companion to be shared.
val TOOL_VI_DESC: Map<String, String> = mapOf(
    // ---- terminal_file ----
    "shell_exec" to "Chạy lệnh shell trên máy (tuỳ chọn root).",
    "file_list" to "Liệt kê thư mục trên máy.",
    "file_pull" to "Đọc file từ máy (base64, tối đa 10MB).",
    "file_push" to "Ghi file lên máy (base64).",
    "file_delete" to "Xoá file/thư mục trên máy.",
    // ---- device_direct ----
    "device_list" to "Liệt kê thiết bị kết nối qua E-Muse.",
    "device_info" to "Model máy, Android, SDK, root, accessibility.",
    "device_status" to "Pin, RAM, bộ nhớ, bản vá bảo mật, uptime.",
    "network_info" to "Trạng thái mạng: Wi-Fi/di động, SSID, tín hiệu.",
    "get_volume" to "Mức âm lượng các kênh: media, báo thức, chuông.",
    "set_volume" to "Chỉnh âm lượng một kênh theo phần trăm.",
    "media_control" to "Phím media: play, pause, next, previous, stop.",
    "set_alarm" to "Tạo báo thức hệ thống theo giờ địa phương.",
    "set_timer" to "Tạo hẹn giờ đếm ngược hệ thống.",
    "top_memory_apps" to "Các app đang ngốn RAM nhiều nhất.",
    "top_storage_apps" to "Các app chiếm dung lượng lưu trữ nhiều nhất.",
    "app_list" to "Liệt kê app đã cài, tìm mờ theo tên.",
    "app_info" to "Chi tiết một app: version, system hay không.",
    "wait" to "Chờ một khoảng thời gian cho animation/load xong.",
    "wait_for_text" to "Chờ đến khi một đoạn chữ xuất hiện trên màn hình.",
    "wait_for_package" to "Chờ đến khi một app lên foreground.",
    // ---- sensitive_read ----
    "get_current_context" to "Giờ, múi giờ, thứ, locale và vị trí hiện tại.",
    "set_clipboard" to "Ghi chữ vào clipboard hệ thống.",
    "get_clipboard" to "Đọc chữ trong clipboard hệ thống.",
    "screen_capture" to "Chụp màn hình, trả về ảnh PNG.",
    "ui_dump" to "Dump cây UI hiện tại dạng JSON.",
    "ui_snapshot" to "Snapshot UI gọn kiểu Eta: element có id ổn định.",
    "observe_screen" to "Quan sát màn hình: cây UI + tuỳ chọn ảnh chụp.",
    "list_alarms" to "Liệt kê báo thức từ app Đồng hồ ColorOS.",
    "list_active_timers" to "Liệt kê timer đang chạy (ColorOS, cần root).",
    "search_contacts" to "Tìm danh bạ theo tên.",
    "search_call_history" to "Tìm lịch sử cuộc gọi theo số/tên.",
    "search_messages" to "Tìm tin nhắn SMS theo số/nội dung.",
    "search_calendar_events" to "Tìm sự kiện lịch theo tiêu đề/địa điểm.",
    "search_media" to "Tìm ảnh/video trong MediaStore.",
    "search_audio" to "Tìm file nhạc theo tên/ca sĩ/đường dẫn.",
    "search_recordings" to "Tìm file ghi âm cuộc gọi/giọng nói.",
    "search_files" to "Tìm tài liệu/file trong MediaStore.",
    "search_downloads" to "Tìm file trong thư mục Downloads.",
    "get_current_location" to "Vị trí hiện tại (lat, lng, độ chính xác).",
    "recent_app_activity" to "Các app vừa mở gần đây, mới nhất trước.",
    "app_usage_summary" to "Tổng hợp thời gian dùng app theo giờ.",
    "recent_notifications" to "Thông báo đang hiển thị (cần root).",
    "wifi_credentials" to "Mật khẩu Wi-Fi đã lưu (cần root).",
    "read_sms_code" to "Đọc mã OTP từ SMS mới nhận.",
    "get_logcat" to "Đọc log hệ thống logcat (cần root).",
    "get_setting" to "Đọc một setting hệ thống Android.",
    "get_device_environment" to "Màn hình, chế độ chuông, DND, loa.",
    "search_coloros_notes" to "Tìm ghi chú ColorOS theo tiêu đề/nội dung.",
    "search_coloros_recordings" to "Tìm file ghi âm trong app Ghi âm ColorOS.",
    "search_recording_summaries" to "Tìm bản tóm tắt phiên âm các file ghi âm.",
    "search_coloros_memories" to "Tìm 'ký ức hệ thống' ColorOS đã thu thập.",
    "search_personal_orders" to "Tìm đơn hàng (đồ ăn, mua sắm, vé) đã nhận diện.",
    "search_saved_places" to "Tìm địa điểm đã lưu/nhận diện trong ký ức.",
    // ---- sensitive_action ----
    "app_install" to "Cài APK (hiện hộp thoại xác nhận hệ thống).",
    "app_uninstall" to "Gỡ app (hiện hộp thoại xác nhận hệ thống).",
    "app_start" to "Mở app theo package, tên hoặc intent/URI.",
    "app_stop" to "Buộc dừng app (cần root).",
    "open_uri" to "Mở URI bằng app hệ thống (web, bản đồ...).",
    "input_tap" to "Chạm vào element (id từ ui_snapshot) hoặc toạ độ.",
    "tap_and_observe" to "Chạm rồi trả snapshot UI mới trong một lần gọi.",
    "input_swipe" to "Vuốt từ điểm này sang điểm khác.",
    "tap_area" to "Chạm vào giữa một vùng chữ nhật.",
    "long_press" to "Nhấn giữ một điểm trên màn hình.",
    "long_press_element" to "Nhấn giữ một element từ ui_snapshot.",
    "scroll" to "Cuộn màn hình theo hướng.",
    "scroll_element" to "Cuộn một element cuộn được từ ui_snapshot.",
    "input_key" to "Gửi phím: mã key hoặc nút (BACK/HOME/ENTER...).",
    "input_text" to "Gõ chữ vào ô đang focus.",
    "replace_text" to "Thay toàn bộ chữ trong ô đang focus/element.",
    "clear_text" to "Xoá chữ trong ô đang focus/element.",
    "paste_text" to "Dán chữ qua clipboard vào ô đang focus.",
    "open_system_panel" to "Mở thanh thông báo hoặc quick settings.",
    "set_setting" to "Đổi một setting hệ thống (root).",
    "set_device_state" to "Bật/tắt Wi-Fi, Bluetooth trực tiếp (root).",
    "app_state_control" to "Force-stop, đóng băng/mở băng app (root).",
    // ---- system ----
    "tool_flags" to "Bật/tắt từng tool riêng lẻ qua MCP.",
)
