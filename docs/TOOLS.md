# MCP Tools E-Muse (70)

Mọi tool (trừ `device_list`, `tool_flags`) đều có `deviceId` optional —
bắt buộc khi có nhiều máy cùng kết nối.

| Tool | Loại | Nguy hiểm | Mô tả |
|---|---|---|---|
| `device_list` | query | - | Liệt kê máy đang kết nối (serve local, 1 máy) |
| `device_info` | query | - | Model, Android/SDK, root, accessibility, screen-capture |
| `device_status` | query | - | Pin (%/sạc), RAM (còn/tổng), bộ nhớ (còn/tổng), version, security patch, uptime |
| `network_info` | query | - | Kết nối (connected/validated/metered), transports, Wi-Fi (SSID/RSSI khi thấy) |
| `get_volume` | query | - | Mức âm lượng mọi stream (media/alarm/ring/notification) |
| `set_volume` | write | - | Đặt âm lượng stream theo % (0 = tắt tiếng) |
| `media_control` | write | - | Phím media: play/pause/play_pause/next/previous/stop |
| `set_alarm` | write | - | Đặt báo thức hệ thống (giờ/phút, label, vibrate, repeat_days) |
| `set_timer` | write | - | Đặt timer hệ thống (1–86400 giây) |
| `list_alarms` | query | - | Báo thức hệ thống kế tiếp (full listing cần DB clock app — P7) |
| `top_memory_apps` | query | - | Process tốn RAM nhất (root `ps`, fallback ActivityManager) |
| `top_storage_apps` | query | - | App tốn bộ nhớ nhất (cần root `dumpsys diskstats`) |
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
| `search_contacts` | query | - | Tìm danh bạ theo tên (display name, lookup key, phone flag). Cần READ_CONTACTS |
| `search_call_history` | query | - | Tìm lịch sử cuộc gọi theo số/tên (date, duration, type). Cần READ_CALL_LOG |
| `search_messages` | query | - | Tìm SMS theo người gửi/nội dung (body cắt 1000 ký tự). Cần READ_SMS |
| `search_calendar_events` | query | - | Tìm sự kiện lịch theo tiêu đề/mô tả/địa điểm. Cần READ_CALENDAR |
| `search_media` | query | - | Tìm ảnh/video trong MediaStore theo tên file/path. Cần ít nhất một trong READ_MEDIA_IMAGES/READ_MEDIA_VIDEO (API 33+) hoặc READ_EXTERNAL_STORAGE (≤ API 32); loại media nào thấy được phụ thuộc quyền đã cấp |
| `search_audio` | query | - | Tìm audio theo tiêu đề/nghệ sĩ/path (album, duration). Cần READ_MEDIA_AUDIO (API 33+) hoặc READ_EXTERNAL_STORAGE (≤ API 32) |
| `search_recordings` | query | - | Tìm bản ghi âm/ghi cuộc gọi (lọc path `*Record*`). Cần READ_MEDIA_AUDIO (API 33+) hoặc READ_EXTERNAL_STORAGE (≤ API 32) |
| `search_files` | query | - | Tìm tài liệu/file chung trong MediaStore. Cần một quyền READ_MEDIA_* (API 33+) hoặc READ_EXTERNAL_STORAGE (≤ API 32) |
| `search_downloads` | query | - | Tìm file trong Downloads (API 29+). Cần một quyền READ_MEDIA_* (API 33+) hoặc READ_EXTERNAL_STORAGE (≤ API 32) |
| `get_current_location` | query | - | Vị trí last-known (lat/lon làm tròn 5 chữ số thập phân, accuracy_m, age_s). Cần ACCESS_FINE_LOCATION (hoặc COARSE) |
| `recent_app_activity` | query | - | App mới foreground gần đây (package, app name, activity, resumed_at). Cần Usage access (Settings → Special app access → Usage access) |
| `app_usage_summary` | query | - | Tổng hợp foreground_ms/last_used_at theo app, giảm dần. Cần Usage access |
| `recent_notifications` | query | - | Notification đang active (package, title, text) qua root `cmd notification`. Không cần manifest permission |
| `wifi_credentials` | query | - | Wi-Fi đã lưu (ssid, password) từ WifiConfigStore.xml qua root. Không cần manifest permission |
| `read_sms_code` | query | - | Mã OTP 4–8 chữ số từ SMS gần đây khớp từ khóa verification/OTP (trả code + sender, không trả body). Cần READ_SMS |
| `get_logcat` | query | - | Logcat `-d -v threadtime` qua root (max_lines 20–500). Không cần manifest permission |
| `get_setting` | query | - | Đọc 1 setting (namespace system/secure/global), fallback root `settings get` khi public API trả null |
| `get_device_environment` | query | - | Môi trường máy: interactive/locked, ringer mode, DND filter, audio outputs, số display. Không cần permission |
| `set_setting` | write | Đổi 1 setting (namespace system/secure/global) qua root `settings put` — root-only, không cần manifest permission |
| `set_device_state` | write | Bật/tắt Wi-Fi hoặc Bluetooth qua root (`cmd wifi`/`cmd bluetooth_manager`) — root-only |
| `app_state_control` | write | Force-stop / freeze (`pm disable-user`) / unfreeze (`pm enable`) app theo package name — root-only |
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
| `device_direct` | Bật | `device_list`, `device_info`, `device_status`, `network_info`, `get_volume`, `set_volume`, `media_control`, `set_alarm`, `set_timer`, `list_alarms`, `top_memory_apps`, `top_storage_apps`, `app_list`, `app_info`, `wait`, `wait_for_text`, `wait_for_package` |
| `sensitive_read` | Tắt | `get_current_context`, `set_clipboard`, `get_clipboard`, `screen_capture`, `ui_dump`, `ui_snapshot`, `observe_screen`, `search_contacts`, `search_call_history`, `search_messages`, `search_calendar_events`, `search_media`, `search_audio`, `search_recordings`, `search_files`, `search_downloads`, `get_current_location`, `recent_app_activity`, `app_usage_summary`, `recent_notifications`, `wifi_credentials`, `read_sms_code`, `get_logcat`, `get_setting`, `get_device_environment` |
| `sensitive_action` | Tắt | `app_install`, `app_uninstall`, `app_start`, `app_stop`, `open_uri`, `input_tap`, `tap_and_observe`, `input_swipe`, `tap_area`, `long_press`, `long_press_element`, `scroll`, `scroll_element`, `input_key`, `input_text`, `replace_text`, `clear_text`, `paste_text`, `open_system_panel`, `set_setting`, `set_device_state`, `app_state_control` |

Tool chưa gán nhóm (không nên xảy ra) bị chặn mặc định (fail-closed).

## Bảng permission Android (P5 audit)

Mọi permission dùng bởi tool đã khai báo trong `AndroidManifest.xml`.
Tool thiếu runtime permission **không tự xin giữa MCP call** — trả lỗi
`PERMISSION_REQUIRED` kèm tên permission và hướng dẫn cấp trong Settings.

| Tool | Permission | Loại |
|---|---|---|
| `search_contacts` | `READ_CONTACTS` | dangerous (runtime) |
| `search_call_history` | `READ_CALL_LOG` | dangerous (runtime) |
| `search_messages`, `read_sms_code` | `READ_SMS` | dangerous (runtime) |
| `search_calendar_events` | `READ_CALENDAR` | dangerous (runtime) |
| `search_media` | `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` (API 33+); `READ_EXTERNAL_STORAGE` (`maxSdkVersion=32`) | dangerous (runtime) |
| `search_audio`, `search_recordings` | `READ_MEDIA_AUDIO` (API 33+); `READ_EXTERNAL_STORAGE` (`maxSdkVersion=32`) | dangerous (runtime) |
| `search_files`, `search_downloads` | một trong `READ_MEDIA_*` (API 33+); `READ_EXTERNAL_STORAGE` (`maxSdkVersion=32`) | dangerous (runtime) |
| `get_current_location` | `ACCESS_FINE_LOCATION` (chấp nhận `ACCESS_COARSE_LOCATION`) | dangerous (runtime) |
| `recent_app_activity`, `app_usage_summary` | `PACKAGE_USAGE_STATS` | special app-op — cấp trong Settings → Special app access → Usage access (lỗi `USAGE_ACCESS_REQUIRED` khi chưa cấp) |
| `recent_notifications` | (không) — root `cmd notification` | root-only |
| `wifi_credentials` | (không) — root đọc `WifiConfigStore.xml` | root-only |
| `get_logcat` | (không) — root `logcat -d` (`READ_LOGS` là signature permission, không dùng) | root-only |
| `get_setting` | (không) — public Settings API + root `settings get` fallback | không cần permission đọc mới |
| `get_device_environment` | (không) — `AudioManager`/`PowerManager`/`KeyguardManager`/`NotificationManager`/`DisplayManager` API thông thường | không cần permission mới |
| `set_setting` | (không) — root `settings put` | root-only |
| `set_device_state` | (không) — root `cmd wifi` / `cmd bluetooth_manager` | root-only |
| `app_state_control` | (không) — root `am`/`pm` + `getApplicationInfo` (`QUERY_ALL_PACKAGES` đã có) | root-only |

Defer sang P7: các tool đọc private provider của ColorOS (notes, recordings,
memories) và private health database — cần OEM provider, không port bằng
cách đoán.

## Gợi ý preset

- **Chỉ đọc + quan sát**: tắt hết write trừ `app_start` nếu cần —
  giữ `device_*`, `app_list`, `app_info`, `file_list`, `file_pull`,
  `screen_capture`, `ui_dump`, `tool_flags`.
- **Điều khiển full**: `{"reset": true}`.
