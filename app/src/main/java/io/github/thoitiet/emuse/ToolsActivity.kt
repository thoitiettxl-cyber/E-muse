package io.github.thoitiet.emuse

import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.thoitiet.emuse.mcp.TOOL_BY_NAME

/**
 * Eta-style Tools screen: one section per [ToolGroup] (plus "Hệ thống" for
 * the meta tool tool_flags), each with a header row (title + group on/off
 * switch) and a 2-column grid of tool cards. Cards show a colored circle
 * icon per group, the tool name, and a short Vietnamese description.
 */
class ToolsActivity : AppCompatActivity() {

    private val groupRows = mutableMapOf<ToolGroup, UiKit.SwitchRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        UiKit.addBottomNav(this, 1)
    }

    override fun onResume() {
        super.onResume()
        val prefs = Prefs(this)
        for ((g, row) in groupRows) row.setCheckedNoEvent(prefs.isGroupEnabled(g))
    }

    private fun buildUi() {
        val prefs = Prefs(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiKit.bg(this@ToolsActivity))
            setPadding(UiKit.dp(this@ToolsActivity, 16), UiKit.dp(this@ToolsActivity, 8),
                UiKit.dp(this@ToolsActivity, 16), UiKit.dp(this@ToolsActivity, 32))
        }
        UiKit.header(this, root, "Tools", "MCP tools trên thiết bị")

        for (g in ToolGroup.entries) {
            root.addView(UiKit.sectionTitle(this, g.title))
            val headerCard = UiKit.card(this)
            val row = UiKit.switchRow(this, headerCard, "Bật nhóm", g.summary) { checked ->
                prefs.setGroupEnabled(g, checked)
                UiKit.toast(this, if (checked) "Đã bật: ${g.title}" else "Đã tắt: ${g.title}")
            }
            row.setCheckedNoEvent(prefs.isGroupEnabled(g))
            groupRows[g] = row
            root.addView(headerCard)
            root.addView(toolGrid(TOOL_GROUP_BY_NAME.filterValues { it == g }.keys.sorted(),
                groupColor(g)))
        }

        // Meta tool tool_flags: always available, shown in its own section.
        root.addView(UiKit.sectionTitle(this, "Hệ thống"))
        root.addView(toolGrid(listOf("tool_flags"), UiKit.gray))
        root.addView(UiKit.hintText(this,
            "Tool bị chặn khi nhóm của nó tắt — kể cả khi tool_flags đang bật."))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(UiKit.bg(this@ToolsActivity))
            addView(root)
        })
    }

    private fun toolGrid(toolNames: List<String>, color: Int): LinearLayout {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, UiKit.dp(this@ToolsActivity, 8), 0, 0)
        }
        for (chunk in toolNames.chunked(2)) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            for (name in chunk) rowView.addView(toolCard(name, color))
            // Pad odd rows so cards keep equal width.
            if (chunk.size == 1) {
                rowView.addView(LinearLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { leftMargin = UiKit.dp(this@ToolsActivity, 6) }
                })
            }
            grid.addView(rowView)
        }
        return grid
    }

    private fun toolCard(name: String, color: Int): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply {
                    leftMargin = UiKit.dp(this@ToolsActivity, 6)
                    rightMargin = UiKit.dp(this@ToolsActivity, 6)
                    bottomMargin = UiKit.dp(this@ToolsActivity, 12)
                }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = UiKit.dp(this@ToolsActivity, 20).toFloat()
                setColor(UiKit.cardBg(this@ToolsActivity))
            }
            setPadding(UiKit.dp(this@ToolsActivity, 14), UiKit.dp(this@ToolsActivity, 14),
                UiKit.dp(this@ToolsActivity, 14), UiKit.dp(this@ToolsActivity, 14))
        }
        card.addView(UiKit.circleIcon(this, name.first().uppercase(), color, 40))
        card.addView(TextView(this).apply {
            text = name
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(UiKit.fgColor(this@ToolsActivity))
            setPadding(0, UiKit.dp(this@ToolsActivity, 8), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = viDesc(name)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(UiKit.secondary(this@ToolsActivity))
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, UiKit.dp(this@ToolsActivity, 4), 0, 0)
        })
        return card
    }

    private fun viDesc(name: String): String =
        TOOL_VI_DESC[name]
            ?: TOOL_BY_NAME[name]?.description?.take(120)?.let { "$it…" }
            ?: name

    companion object {
        fun groupColor(g: ToolGroup): Int = when (g) {
            ToolGroup.TERMINAL_FILE -> 0xFF2196F3.toInt()   // blue
            ToolGroup.DEVICE_DIRECT -> 0xFF4CAF50.toInt()   // green
            ToolGroup.SENSITIVE_READ -> 0xFFFF9800.toInt()  // orange
            ToolGroup.SENSITIVE_ACTION -> 0xFFF44336.toInt() // red
        }

        /** Short Vietnamese description per tool (fallback: original English). */
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
    }
}
