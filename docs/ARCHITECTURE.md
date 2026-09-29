# Kiến trúc E-Muse

E-Muse cho phép điều khiển điện thoại Android từ xa qua **MCP**
(Model Context Protocol). Kiến trúc direct — app tự serve MCP, không qua
server trung gian:

```
┌─────────────┐   POST /mcp (JSON-RPC 2.0)   ┌──────────────────┐  localhost   ┌──────────────┐
│ MCP client  │ ────────────────────────────> │ Cloudflare       │ ──────────> │ App E-Muse   │
│ (Pi, Claude)│   Header EMUSE_API_KEY        │ Tunnel           │ 127.0.0.1   │ (Android)    │
└─────────────┘                               │ (cloudflared)    │ :18789      │              │
                                              └──────────────────┘             └──────┬───────┘
                                                                                     │ executors:
                                                                              Shell / App / File /
                                                                              Input / Screen /
                                                                              UI dump+snapshot
```

## 1. App Android (`app/`)

- Package `io.github.thoitiet.emuse`. `MuseService` (foreground service) chạy:
  - `LocalHttpServer` — HTTP server trên `127.0.0.1:<mcpPort>` (mặc định 18789),
    serve `/health`, `/mcp` (JSON-RPC 2.0). Mọi path khác trả 404.
  - `TunnelManager` — tải `cloudflared` (ARM64, kèm Mozilla CA bundle vì binary
    Go trên Android không đọc được system CA store → export `SSL_CERT_FILE`),
    chạy Quick Tunnel (`quick --url`) hoặc Named Tunnel
    (`tunnel run --token`) khi user bật.
  - `McpHandler` — MCP JSON-RPC: `initialize`, `tools/list`, `tools/call`,
    xác thực header `EMUSE_API_KEY` (so với `Prefs.apiKey`).
- `CommandDispatcher.executeCommand(cmd)` chạy executor tương ứng trong
  coroutine (timeout 120s), trả `DeviceResult`.
- Executors (`app/.../exec/`): `ShellExecutor` (sh/su), `AppExecutor`
  (list/info/install/uninstall/start/stop), `FileExecutor`, `InputExecutor`
  (tap/swipe/key/text qua accessibility hoặc root), `ScreenExecutor` +
  `ScreenCapture` (MediaProjection, root screencap fallback), `UiDumpExecutor`,
  `UiSnapshotter` (flatten/filter element list cho automation).
- `MuseAccessibilityService`: tap/vuốt/gõ phím/đọc UI không cần root.
- `ShellExecutor.reset()` được gọi trong `CommandDispatcher.init` để service
  restart trong cùng process không bị "executor closed".

## 2. Tunnel (Quick / Named)

- **Quick**: `cloudflared tunnel --no-autoupdate --protocol http2 quick --url
  http://127.0.0.1:<port>` — URL ngẫu nhiên `https://<id>.trycloudflare.com`,
  parse từ stdout `https://...trycloudflare.com`.
- **Named**: `cloudflared tunnel --no-autoupdate run --token <token>`
  (+ `--protocol http2` fallback) — chờ log `Registered tunnel connection`,
  URL hiển thị = hostname user nhập. Cần user tự tạo tunnel + token trên
  Cloudflare dashboard (repo không chứa token của ai).
- `TunnelManager.State`: `Running(url)` / `Failed(reason)` / `Downloading`.

## 3. Tool flags (bật/tắt tool)

- Lưu trong SharedPreferences (`tool_flags` JSON), mặc định **tất cả bật**.
- `tools/list` chỉ trả tool đang bật (+ `tool_flags` luôn hiện).
- `tools/call` vào tool bị tắt → lỗi `-32000 "Tool ... is disabled"`.
- `tool_flags` (kind `query`, không bao giờ bị tắt):
  - `{}` → xem trạng thái tất cả tool
  - `{set: {shell_exec: false}}` → tắt/bật từng tool
  - `{reset: true}` → bật lại tất cả

## Bảo mật

- Mọi request `/mcp` đều cần header `EMUSE_API_KEY` khớp key trong app
  (do user tự đặt, không commit).
- Tool `write`/`DANGEROUS` (shell, cài/xóa app, ghi/xóa file...) nên tắt bớt
  bằng `tool_flags` khi chỉ cần dùng chế độ đọc.
