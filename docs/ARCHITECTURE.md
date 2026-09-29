# Kiến trúc E-Muse

E-Muse cho phép điều khiển điện thoại Android từ xa qua **MCP**
(Model Context Protocol). Ba tầng:

```
┌─────────────┐   POST /mcp (JSON-RPC 2.0)   ┌──────────────────┐  WebSocket   ┌──────────────┐
│ Pi / client │ ────────────────────────────> │ Worker e-muse-mcp │ ──────────> │ App E-Muse   │
│ MCP bất kỳ  │   Bearer EMUSE_API_KEY        │ (Cloudflare)      │ {id,cmd}     │ (Android)    │
└─────────────┘                               └──────────────────┘              └──────────────┘
                                                        │                              │
                                              Durable Object                     executors:
                                              `DeviceLink`                       Shell / App /
                                              (1 instance "fleet")               File / Input /
                                                                                 Screen / UI dump
```

## 1. App Android (`app/`)

- Package `io.github.thoitiet.emuse`. `MuseService` (foreground service) mở
  WebSocket tới `https://<worker>/device/connect`, gửi `hello` (deviceId,
  deviceName, token) rồi giữ kết nối.
- `CommandDispatcher` nhận `{id, cmd, args}`, chạy executor tương ứng trong
  coroutine (timeout 120s), trả `{id, ok, result|error}`.
- Executors (`app/.../exec/`): `ShellExecutor` (sh/su), `AppExecutor`
  (list/info/install/uninstall/start/stop), `FileExecutor`, `InputExecutor`
  (tap/swipe/key/text qua accessibility hoặc root), `ScreenExecutor` +
  `ScreenCapture` (MediaProjection, root screencap fallback), `UiDumpExecutor`.
- `MuseAccessibilityService`: tap/vuốt/gõ phím/đọc UI không cần root.
- `ShellExecutor.reset()` được gọi trong `CommandDispatcher.init` để service
  restart trong cùng process không bị "executor closed".

## 2. Worker (`workers/mcp/`)

- `index.ts`: entry — `/health`, forward `/device/connect` và `/mcp` vào DO.
- `server.ts`: MCP handler viết tay (initialize / server/discover / ping /
  tools/list / tools/call). Không dùng `@modelcontextprotocol/sdk`.
- `device.ts`: Durable Object `DeviceLink` giữ các WebSocket của máy,
  xác thực `hello` bằng `EMUSE_API_KEY` (so sánh constant-time), route lệnh
  theo `deviceId` với correlation id + timeout (tối đa 120s).
- `tools.ts`: 20 tool definitions (19 điều khiển máy + `tool_flags`).
- `auth.ts`: kiểm tra Bearer API key.
- `protocol.ts`: types chung (`Cmd`, `DeviceGateway`, `EmuseEnv`...).

## 3. Tool flags (bật/tắt tool)

- Lưu trong DO storage (`disabledTools: string[]`), mặc định **tất cả bật**.
- `tools/list` chỉ trả tool đang bật (+ `tool_flags` luôn hiện).
- `tools/call` vào tool bị tắt → lỗi `-32000 "Tool ... is disabled"`.
- `tool_flags` (kind `query`, không bao giờ bị tắt):
  - `{}` → xem trạng thái tất cả tool
  - `{set: {shell_exec: false}}` → tắt/bật từng tool
  - `{reset: true}` → bật lại tất cả
- Kill-switch môi trường (`wrangler.jsonc` vars) vẫn có hiệu lực độc lập:
  `EMUSE_CHANNEL_DISABLED` (tắt cả kênh, 503), `EMUSE_READ_DISABLED`
  (tắt tools/list), `EMUSE_WRITE_DISABLED` (tắt mọi tool write — check ở
  call-time nên flag không bypass được).

## Bảo mật

- Mọi request `/mcp` và `hello` đều cần `EMUSE_API_KEY` (wrangler secret,
  không commit).
- Tool `write`/`DANGEROUS` (shell, cài/xóa app, ghi/xóa file...) nên tắt bớt
  bằng `tool_flags` khi chỉ cần dùng chế độ đọc.
