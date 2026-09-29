# E-Muse

E-Muse cho phép điều khiển điện thoại Android từ xa qua **MCP** (Model Context Protocol).
Gồm 2 phần trong cùng repo:

- **App Android** (`app/`, package `io.github.thoitiet.emuse`): chạy foreground service,
  giữ WebSocket tới Worker, thực thi lệnh (shell, app, file, screenshot, input, UI dump)
  rồi trả kết quả về.
- **Cloudflare Worker** (`workers/mcp/`): MCP server chạy JSON-RPC 2.0 trên endpoint
  `/mcp` (tự cài tay, không dùng MCP SDK — theo mẫu của KSHT), xác thực bằng API key,
  điều phối lệnh tới từng máy qua Durable Object `DeviceLink`.

Luồng hoạt động:

```
Pi (MCP client) ──POST /mcp──> Worker e-muse-mcp ──WebSocket──> App E-Muse ──> Android
   (Bearer/EMUSE_API_KEY)        (Durable Object)      {id, cmd, args}      shell / API
```

## Deploy Worker

```bash
cd E-muse
npm install
wrangler login
wrangler secret put EMUSE_API_KEY   # key bí mật, KHÔNG commit vào repo
wrangler deploy --config wrangler.jsonc
```

Kiểm tra: `GET https://e-muse-mcp.ngthanhhuy951.workers.dev/health` → `{"ok":true}`.

Các biến môi trường (vars trong `wrangler.jsonc`) để tắt nhanh khi cần:

| Var | Tác dụng |
|---|---|
| `EMUSE_CHANNEL_DISABLED=1` | Tắt toàn bộ kênh MCP (503) |
| `EMUSE_READ_DISABLED=1` | Tắt tools/list |
| `EMUSE_WRITE_DISABLED=1` | Tắt mọi tool ghi (write) |

## Build app Android

Mở thư mục này bằng **Android Studio** (hoặc chạy Gradle có cài Android SDK,
compileSdk 34, JDK 17+):

```bash
gradle :app:assembleDebug
```

Cài APK lên máy, mở app:

1. Nhập **Worker URL**: `https://e-muse-mcp.ngthanhhuy951.workers.dev/device/connect`
2. Nhập **API key** (khớp với `EMUSE_API_KEY` đã put secret)
3. Save → Start service
4. (Khuyến nghị) bật Accessibility cho E-Muse để dùng tap/vuốt/gõ phím/đọc UI
   không cần root; máy root thì mở khóa thêm shell root, screenshot, force-stop.

## Pi kết nối MCP

Thêm vào MCP client (file `.mcp.json` mẫu có sẵn ở repo root):

```json
{
  "mcpServers": {
    "E-muse": {
      "url": "https://e-muse-mcp.ngthanhhuy951.workers.dev/mcp",
      "protocolVersion": "2026-07-28",
      "headers": { "EMUSE_API_KEY": "${EMUSE_API_KEY}" }
    }
  }
}
```

Thay `<account>` bằng workers subdomain thật, đặt `EMUSE_API_KEY=...` trong
`~/.config/mcp/mcp.env` (file mode 600, không commit).

## Protocol WebSocket (app ↔ Worker)

App mở WebSocket tới `/device/connect`, gửi đầu tiên:

```json
{ "type": "hello", "deviceId": "<ANDROID_ID>", "deviceName": "<Build.MODEL>", "token": "<EMUSE_API_KEY>" }
```

Token sai → socket bị đóng (4401). Sau đó Worker gửi lệnh:

```json
{ "id": "<uuid>", "cmd": "shell.exec", "args": { "command": "ls /sdcard" } }
```

App thực thi rồi trả:

```json
{ "id": "<uuid>", "ok": true, "result": { ... } }
// hoặc
{ "id": "<uuid>", "ok": false, "error": "..." }
```

Tên `cmd` khớp với union `Cmd` trong `workers/mcp/protocol.ts`
(đồng bộ với `Cmds` trong `app/.../Protocol.kt`).

## Danh sách tools (19)

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
| `input_tap` | write | Chạm (x, y) |
| `input_swipe` | write | Vuốt (x1,y1 → x2,y2) |
| `input_key` | write | Gửi key code |
| `input_text` | write | Gõ text vào ô đang focus |
| `ui_dump` | query | Cây UI hiện tại (JSON) |

Mọi tool (trừ `device_list`) nhận `deviceId` tùy chọn — bỏ trống khi chỉ có
1 máy kết nối; nhiều máy thì bắt buộc chỉ định.

## Bảo mật

- `EMUSE_API_KEY` là secret: chỉ đặt qua `wrangler secret put`, trong app nhập
  tay, trong MCP client để ở `mcp.env`. **Không commit key vào repo.**
- Tool `shell_exec` với `asRoot` cho quyền kiểm soát toàn bộ máy — chỉ bật
  `EMUSE_WRITE_DISABLED=0` khi thật sự cần.
- Kênh `/device/connect` cũng xác thực bằng cùng API key ở message `hello`.
