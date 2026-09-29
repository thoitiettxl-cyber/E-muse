# MCP Tools E-Muse (39)

Mọi tool (trừ `device_list`, `tool_flags`) đều có `deviceId` optional —
bắt buộc khi có nhiều máy cùng kết nối.

| Tool | Loại | Nguy hiểm | Mô tả |
|---|---|---|---|
| `device_list` | query | - | Liệt kê máy đang kết nối (serve local, 1 máy) |
| `device_info` | query | - | Model, Android/SDK, root, accessibility, screen-capture |
| `get_current_context` | query | - | Giờ, timezone, weekday, locale, last-known location (nếu đã có quyền) |
| `shell_exec` | write | **yes** | Shell `sh -c`; `asRoot` → `su -c` (cần root) |
| `app_list` | query | - | App đã cài (`system: true` để gồm system app; `query` để fuzzy-search theo tên, `limit` 1–20) |
| `app_info` | query | - | Chi tiết 1 package |
| `app_install` | write | **yes** | Cài APK base64 — hiện dialog xác nhận của hệ thống |
| `app_uninstall` | write | **yes** | Gỡ app — hiện dialog xác nhận |
| `app_start` | write | - | Mở app (package / app_name fuzzy / action+uri+extras) |
| `open_uri` | write | - | Mở URI bằng ACTION_VIEW (check scheme + app xử lý) |
| `app_stop` | write | **yes** | Force-stop (cần root) |
| `file_list` | query | - | Liệt kê thư mục |
| `file_pull` | query | - | Đọc file → base64 (tối đa 10MB) |
| `file_push` | write | **yes** | Ghi file base64 (root fallback ngoài sandbox) |
| `file_delete` | write | **yes** | Xóa file/thư mục |
| `screen_capture` | query | - | Screenshot PNG (image block + width/height); cần root hoặc MediaProjection đã cấp quyền |
| `input_tap` | write | - | Chạm element (`elementId` từ `ui_snapshot`, ưu tiên) hoặc (x, y) pixel; `observationId` optional để chống tap nhầm màn hình đã cũ |
| `tap_and_observe` | write | - | **Tap + snapshot mới trong 1 call** (thay pattern 3-turn snapshot→tap→snapshot); trả `{tap, snapshot}` |
| `input_swipe` | write | - | Vuốt (x1,y1 → x2,y2, durationMs) |
| `tap_area` | write | - | Chạm tâm hình chữ nhật (x1,y1 → x2,y2) |
| `long_press` | write | - | Nhấn giữ tại (x, y), 300–3000ms (mặc định 800) |
| `long_press_element` | write | - | Nhấn giữ element từ `ui_snapshot` (+ `observationId`) |
| `scroll` | write | - | Cuộn màn hình theo hướng nội dung (up/down/left/right) |
| `scroll_element` | write | - | Cuộn element scrollable từ `ui_snapshot` (+ `observationId`) |
| `input_key` | write | - | Key code Android (3=HOME, 4=BACK, 66=ENTER...) hoặc nút tên (BACK/HOME/ENTER/RECENTS/PASTE/NOTIFICATIONS/QUICK_SETTINGS) |
| `input_text` | write | - | Gõ text vào field đang focus |
| `replace_text` | write | - | Thay text field đang focus hoặc element (`elementId` + `observationId`), tối đa 4000 ký tự |
| `clear_text` | write | - | Xóa text field đang focus hoặc element |
| `set_clipboard` | write | - | Ghi text vào clipboard (tối đa 20000 ký tự) |
| `get_clipboard` | query | - | Đọc text clipboard (Android 10+ có thể trả rỗng khi nền) |
| `paste_text` | write | - | Dán text qua clipboard — cần focus thật, không focus thì không động vào clipboard |
| `wait` | query | - | Chờ N ms (100–30000) cho animation/network |
| `wait_for_package` | query | - | **Đợi package lên foreground** (poll 350ms phía device, 1 call) |
| `open_system_panel` | write | - | Mở notification shade / quick settings |
| `ui_dump` | query | - | Cây UI dạng JSON (cần accessibility hoặc root) |
| `ui_snapshot` | query | - | **List element gọn kiểu Eta/E-Jev** (id, text, desc, bounds) — cần accessibility; dùng id với `input_tap` thay vì đoán tọa độ |
| `observe_screen` | query | - | **Composite 1 call**: UI tree (`observation_id` + elements, `max_nodes` 1–120) + screenshot optional (image block) |
| `wait_for_text` | query | - | **Đợi text xuất hiện** (poll 350ms phía device, 1 call) — thay N turn poll thủ công |
| `tool_flags` | query | - | **Xem/bật/tắt tool** — luôn khả dụng, không tắt được chính nó |

Mỗi `ui_snapshot` trả `observation_id` (tăng đơn điệu: `o1`, `o2`, ...).
Truyền lại vào `input_tap`/`tap_and_observe` qua `observationId`: nếu màn
hình đã đổi từ lần snapshot đó, tap bị từ chối với lỗi `STALE_OBSERVATION`
thay vì chạm nhầm element. Bỏ trống `observationId` = không kiểm tra
(tương thích client cũ).

Gesture timeout: nếu accessibility gesture timeout hoặc bị hủy sau khi đã
dispatch, kết quả là `ACTION_OUTCOME_UNKNOWN` và app **không** fallback sang
root (tránh double-tap) — hãy `ui_snapshot` lại trước khi thử tiếp.

## tool_flags

```jsonc
// Xem trạng thái
{}
// Tắt tool nguy hiểm khi chỉ cần đọc
{"set": {"shell_exec": false, "file_push": false, "file_delete": false}}
// Bật lại tất cả
{"reset": true}
```

- Tool bị tắt: ẩn khỏi `tools/list`, gọi vào bị từ chối
  (`-32000 "Tool ... is disabled"`).
- Trạng thái lưu trong SharedPreferences của app.

## Nhóm quyền (permission groups)

Ngoài `tool_flags` (bật/tắt từng tool), mỗi tool thuộc đúng 1 nhóm quyền.
Tool bị chặn khi nhóm của nó tắt — kể cả khi `tool_flags` đang bật
(enforcement 2 lớp: `McpHandler` rồi `CommandDispatcher`).
Hai nhóm nhạy cảm mặc định TẮT; bật trong app (mục "Quyền tool").
`tool_flags` luôn khả dụng và không thuộc nhóm nào. Trạng thái nhóm cũng
được trả về trong kết quả của `tool_flags` (mảng `groups`).

| Nhóm | Mặc định | Tools |
|---|---|---|
| `terminal_file` | Bật | `shell_exec`, `file_list`, `file_pull`, `file_push`, `file_delete` |
| `device_direct` | Bật | `device_list`, `device_info`, `app_list`, `app_info`, `wait`, `wait_for_text`, `wait_for_package` |
| `sensitive_read` | Tắt | `get_current_context`, `set_clipboard`, `get_clipboard`, `screen_capture`, `ui_dump`, `ui_snapshot`, `observe_screen` |
| `sensitive_action` | Tắt | `app_install`, `app_uninstall`, `app_start`, `app_stop`, `open_uri`, `input_tap`, `tap_and_observe`, `input_swipe`, `tap_area`, `long_press`, `long_press_element`, `scroll`, `scroll_element`, `input_key`, `input_text`, `replace_text`, `clear_text`, `paste_text`, `open_system_panel` |

Tool chưa gán nhóm (không nên xảy ra) bị chặn mặc định (fail-closed).

## Gợi ý preset

- **Chỉ đọc + quan sát**: tắt hết write trừ `app_start` nếu cần —
  giữ `device_*`, `app_list`, `app_info`, `file_list`, `file_pull`,
  `screen_capture`, `ui_dump`, `tool_flags`.
- **Điều khiển full**: `{"reset": true}`.
