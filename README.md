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

## Danh sách tools (36)

| Tool | Loại | Mô tả |
|---|---|---|
| `device_list` | query | Liệt kê máy đang kết nối |
| `device_info` | query | Model, Android version, root, accessibility |
| `shell_exec` | write | Chạy shell (`sh -c`, hoặc `su -c` nếu `asRoot`) |
| `app_list` | query | Danh sách app đã cài |
| `app_info` | query | Chi tiết 1 package |
| `app_install` | write | Cài APK (base64) — hiện dialog xác nhận |
| `app_uninstall` | write | Gỡ app — hiện dialog xác nhận |
| `app_start` | write | Mở app (package hoặc action/uri/extras) |
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
| `wait_for_text` | query | **Đợi text xuất hiện** (device poll, 1 call) |
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
