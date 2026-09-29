# E-Muse

E-Muse biến điện thoại Android thành một **MCP server** (Model Context Protocol)
chạy ngay trên máy, để AI agent (Muse, Claude, ...) điều khiển điện thoại từ xa:
chạy shell, quản lý app, đọc/ghi file, chụp màn hình, chạm/vuốt/gõ phím, đọc cây UI.

Kiến trúc direct — không qua server trung gian:

```
MCP client ──HTTPS POST /mcp──> Cloudflare Tunnel ──> App E-Muse ──> Android
(Bearer EMUSE_API_KEY)          (Quick hoặc named)    127.0.0.1:18789
```

App mở một HTTP server MCP ngay trên máy (`127.0.0.1:18789`) và dùng
**Cloudflare Tunnel** (`cloudflared`, chạy ngay trong app, không cần cài thêm)
để public endpoint này ra internet qua HTTPS.

## Cài đặt

### Cách 1 — Tải APK từ CI (khuyến nghị)

Mỗi lần push lên `main`, GitHub Actions build sẵn APK:

1. Vào tab **Actions** của repo → chọn run mới nhất → tải artifact
   `E-Muse-debug.apk` (hoặc bản release).
2. Cài APK lên điện thoại (cho phép "cài app không rõ nguồn" một lần).

### Cách 2 — Build tay

Cần Android SDK (compileSdk 34, build-tools 34.0.0) + JDK 17:

```bash
gradle :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Thiết lập trên điện thoại

Mở app E-Muse, màn hình cài đặt phong cách HyperOS:

1. **Truy cập → API key**: tự đặt một chuỗi bí mật dài (vd 32 ký tự ngẫu nhiên).
   Mọi MCP client phải gửi đúng key này trong header `EMUSE_API_KEY`.
2. **Cloudflare Tunnel → bật công tắc**:
   - Để trống *Token* và *Hostname* → **Quick Tunnel**: chạy ngay, URL ngẫu nhiên
     (đổi mỗi lần bật). Hợp cho dùng nhanh / test.
   - Điền *Token* + *Hostname* → **Named Tunnel** (cố định, xem bên dưới).
   - URL hiện ra trong app — bấm để copy.
3. **Dịch vụ → bật "Chạy nền"** (foreground service).
4. **Thiết bị**:
   - Bật **Accessibility** cho E-Muse → dùng tap/vuốt/gõ phím/đọc UI không cần root.
   - Bấm **Cấp quyền** ở mục Chụp màn hình → cho phép screenshot qua MediaProjection.
   - Máy đã root: mở khóa thêm shell root, force-stop app, screenshot qua root.
5. (Tùy chọn) **Dịch vụ → Bóng nổi**: nút nổi hiển thị log lệnh đang chạy.

### Named Tunnel (URL cố định)

Cần tài khoản Cloudflare (miễn phí) + một domain đã add vào Cloudflare:

1. Trên [Cloudflare dashboard](https://dash.cloudflare.com) → Zero Trust →
   Networks → Tunnels → tạo tunnel, copy **token** (chuỗi dài bắt đầu bằng `eyJ...`).
2. Trong tunnel vừa tạo: Public Hostname → add hostname
   (vd `mcp.example.com`) → Service `http://127.0.0.1:18789`.
   Cloudflare tự tạo DNS record cho hostname.
3. Trong app E-Muse: dán token vào *Token*, hostname vào *Hostname*, bật tunnel.
   URL hiển thị sẽ là `https://mcp.example.com` — không đổi giữa các lần bật/tắt.

> Token là secret: chỉ dán trong app, **không commit vào repo, không chia sẻ**.

## Kết nối MCP client

Trỏ MCP client tới URL tunnel (Quick hoặc named):

```json
{
  "mcpServers": {
    "E-muse-direct": {
      "url": "https://<tunnel-url-của-bạn>/mcp",
      "protocolVersion": "2026-07-28",
      "headers": { "EMUSE_API_KEY": "${EMUSE_API_KEY}" }
    }
  }
}
```

Đặt `EMUSE_API_KEY=<key-đã-đặt-trong-app>` trong file env riêng của MCP client
(vd `~/.config/mcp/mcp.env`, mode 600). Kiểm tra nhanh:

```bash
curl -s https://<tunnel-url-của-bạn>/health
# {"ok":true,"mode":"direct"}
```

## Danh sách tools (70)

| Tool | Loại | Mô tả |
|---|---|---|
| `device_list` | query | Liệt kê máy đang kết nối |
| `device_info` | query | Model, Android version, root, accessibility |
| `device_status` | query | Pin, RAM, bộ nhớ, version, uptime |
| `network_info` | query | Trạng thái mạng, transports, Wi-Fi (SSID/RSSI) |
| `get_volume` | query | Mức âm lượng mọi stream |
| `set_volume` | write | Đặt âm lượng stream theo % |
| `media_control` | write | Phím media (play/pause/next/previous/stop) |
| `set_alarm` | write | Đặt báo thức hệ thống |
| `set_timer` | write | Đặt timer hệ thống |
| `list_alarms` | query | Báo thức hệ thống kế tiếp |
| `top_memory_apps` | query | Process tốn RAM nhất |
| `top_storage_apps` | query | App tốn bộ nhớ nhất (cần root) |
| `get_current_context` | query | Giờ, timezone, weekday, locale, last-known location |
| `shell_exec` | write | Chạy shell (`sh -c`, hoặc `su -c` nếu `asRoot`) |
| `app_list` | query | Danh sách app đã cài (`query` fuzzy-search, `limit` 1–20) |
| `app_info` | query | Chi tiết 1 package |
| `app_install` | write | Cài APK (base64) — hiện dialog xác nhận |
| `app_uninstall` | write | Gỡ app — hiện dialog xác nhận |
| `app_start` | write | Mở app (package / app_name fuzzy / action+uri+extras) |
| `open_uri` | write | Mở URI bằng ACTION_VIEW |
| `app_stop` | write | Force-stop (cần root) |
| `file_list` | query | Liệt kê thư mục |
| `file_pull` | query | Đọc file → base64 (tối đa 10MB) |
| `file_push` | write | Ghi file base64 lên máy |
| `file_delete` | write | Xóa file/thư mục |
| `screen_capture` | query | Screenshot PNG (trả image block) |
| `input_tap` | write | Chạm (x, y) hoặc theo `elementId` từ `ui_snapshot` (+ `observationId` chống tap nhầm) |
| `tap_and_observe` | write | **Tap + snapshot mới trong 1 call** — thay pattern 3-turn |
| `input_swipe` | write | Vuốt (x1,y1 → x2,y2) |
| `tap_area` | write | Chạm tâm hình chữ nhật |
| `long_press` | write | Nhấn giữ tại (x, y), 300–3000ms |
| `long_press_element` | write | Nhấn giữ element từ `ui_snapshot` |
| `scroll` | write | Cuộn màn hình (up/down/left/right) |
| `scroll_element` | write | Cuộn element scrollable từ `ui_snapshot` |
| `input_key` | write | Gửi key code hoặc nút tên (BACK/HOME/...) |
| `input_text` | write | Gõ text vào ô đang focus |
| `replace_text` | write | Thay text field/element (tối đa 4000 ký tự) |
| `clear_text` | write | Xóa text field/element |
| `set_clipboard` | write | Ghi text vào clipboard |
| `get_clipboard` | query | Đọc text clipboard |
| `paste_text` | write | Dán text (cần focus thật) |
| `wait` | query | Chờ N ms (100–30000) |
| `wait_for_package` | query | Đợi package lên foreground (device poll, 1 call) |
| `open_system_panel` | write | Mở notification shade / quick settings |
| `ui_dump` | query | Cây UI hiện tại (JSON) |
| `ui_snapshot` | query | Danh sách element gọn nhẹ (id `e0…`, text, bounds) cho automation |
| `observe_screen` | query | Composite 1 call: UI tree + screenshot optional (image block) |
| `wait_for_text` | query | **Đợi text xuất hiện** (device poll, 1 call) |
| `search_contacts` | query | Tìm danh bạ theo tên (cần READ_CONTACTS) |
| `search_call_history` | query | Tìm lịch sử cuộc gọi theo số/tên (cần READ_CALL_LOG) |
| `search_messages` | query | Tìm SMS theo người gửi/nội dung (cần READ_SMS) |
| `search_calendar_events` | query | Tìm sự kiện lịch (cần READ_CALENDAR) |
| `search_media` | query | Tìm ảnh/video trong MediaStore (cần READ_MEDIA_*) |
| `search_audio` | query | Tìm file audio theo tiêu đề/nghệ sĩ (cần READ_MEDIA_AUDIO) |
| `search_recordings` | query | Tìm bản ghi âm/ghi cuộc gọi (cần READ_MEDIA_AUDIO) |
| `search_files` | query | Tìm tài liệu/file trong MediaStore (cần READ_MEDIA_*) |
| `search_downloads` | query | Tìm file trong Downloads (cần READ_MEDIA_*) |
| `get_current_location` | query | Vị trí hiện tại/last-known (cần ACCESS_FINE_LOCATION) |
| `recent_app_activity` | query | App mới dùng gần đây (cần Usage access trong Settings) |
| `app_usage_summary` | query | Tổng hợp thời gian dùng app (cần Usage access) |
| `recent_notifications` | query | Notification đang active (cần root) |
| `wifi_credentials` | query | Wi-Fi đã lưu: ssid + password (cần root) |
| `read_sms_code` | query | Mã OTP từ SMS gần đây (cần READ_SMS) |
| `get_logcat` | query | Đọc logcat (cần root) |
| `get_setting` | query | Đọc setting system/secure/global |
| `get_device_environment` | query | Màn hình/khóa, ringer, DND, audio output, display |
| `set_setting` | write | Đổi setting system/secure/global (cần root) |
| `set_device_state` | write | Bật/tắt Wi-Fi hoặc Bluetooth (cần root) |
| `app_state_control` | write | Force-stop / freeze / unfreeze app (cần root) |
| `tool_flags` | query | Bật/tắt từng tool (không bao giờ bị tắt) |

Mọi tool (trừ `device_list`) nhận `deviceId` tùy chọn — bỏ trống khi chỉ có
1 máy kết nối.

## Tắt bớt tool nguy hiểm

`tool_flags` cho phép tắt tool theo tên mà không cần rebuild:

```json
{ "set": { "shell_exec": false } }   // tắt shell
{ "reset": true }                    // bật lại tất cả
{ }                                  // xem trạng thái hiện tại
```

Trạng thái lưu trong SharedPreferences của app.

## Nhóm quyền tool

Mỗi tool thuộc 1 trong 4 nhóm (chi tiết + mapping đầy đủ trong `docs/TOOLS.md`):
`terminal_file` (mặc định bật), `device_direct` (mặc định bật),
`sensitive_read` (mặc định tắt), `sensitive_action` (mặc định tắt).
Bật/tắt trong app (mục "Quyền tool"). Tool bị chặn khi nhóm của nó tắt —
kể cả khi `tool_flags` đang bật. Không có nhóm browser
(`browser_use` đã loại khỏi scope).

## Bảo mật

- `EMUSE_API_KEY` là secret duy nhất: tự đặt trong app, lưu trong file env của
  MCP client. **Không commit key/token vào repo.**
- `shell_exec` với `asRoot` cho quyền kiểm soát toàn bộ máy — chỉ bật khi thật
  sự cần, và cân nhắc tắt bớt tool write bằng `tool_flags`.
- Tunnel mã hóa TLS end-to-end qua Cloudflare; Quick Tunnel URL ngẫu nhiên
  nhưng vẫn cần đúng API key mới gọi được.

## Tài liệu chi tiết

- `docs/ARCHITECTURE.md` — kiến trúc app, MCP server, tunnel
- `docs/PROTOCOL.md` — HTTP/MCP surface trên máy
- `docs/TOOLS.md` — chi tiết từng tool
- `docs/WORKFLOW.md` — quy trình dev/build/release
