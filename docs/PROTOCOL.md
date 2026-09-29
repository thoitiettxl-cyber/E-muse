# HTTP / MCP surface trên máy

App serve HTTP trên `127.0.0.1:<mcpPort>` (mặc định 18789), public qua
Cloudflare Tunnel. Mọi request `/mcp` phải có header
`EMUSE_API_KEY: <key-trong-app>` — sai/thiếu → `401`.

## Endpoints

| Method | Path | Mô tả |
|---|---|---|
| `GET` | `/health` | `{"ok":true,"mode":"direct"}` |
| `POST` | `/mcp` | JSON-RPC 2.0 (MCP): `initialize`, `tools/list`, `tools/call`, `ping` |
| `*` | `/device/connect` | legacy, giữ tương thích |

## `tools/call` → executor

MCP tool được map sang `cmd` nội bộ (`Cmds` trong
`app/.../Protocol.kt`), chạy bởi `CommandDispatcher.executeCommand`
(timeout 120s, clamp 1s–120s).

| `cmd` (protocol) | Tool MCP | Executor (app) |
|---|---|---|
| `device.info` | `device_info` | `CommandDispatcher.deviceInfo()` |
| `shell.exec` | `shell_exec` | `ShellExecutor` |
| `app.list` | `app_list` | `AppExecutor` |
| `app.info` | `app_info` | `AppExecutor` |
| `app.install` | `app_install` | `AppExecutor` (+ `InstallReceiver`, `Session.commit(intentSender)`) |
| `app.uninstall` | `app_uninstall` | `AppExecutor` |
| `app.start` | `app_start` | `AppExecutor` |
| `app.stop` | `app_stop` | `AppExecutor` (root) |
| `file.list` | `file_list` | `FileExecutor` |
| `file.pull` | `file_pull` | `FileExecutor` (tối đa 10MB) |
| `file.push` | `file_push` | `FileExecutor` (root fallback ngoài sandbox) |
| `file.delete` | `file_delete` | `FileExecutor` |
| `screen.capture` | `screen_capture` | `ScreenExecutor` + `ScreenCapture` (MediaProjection; root fallback) |
| `input.tap` | `input_tap` | `InputExecutor` (tọa độ hoặc `elementId` từ `ui_snapshot`) |
| `input.swipe` | `input_swipe` | `InputExecutor` |
| `input.key` | `input_key` | `InputExecutor` |
| `input.text` | `input_text` | `InputExecutor` |
| `ui.dump` | `ui_dump` | `UiDumpExecutor` (accessibility; root fallback) |
| `ui.snapshot` | `ui_snapshot` | `UiSnapshotter` (flatten/filter, id `e0…`) |

Thêm tool mới: thêm `Cmd` vào `Protocol.kt` + `ToolDef` vào `ToolDefs.kt` +
executor + case trong `CommandDispatcher.execute()`.

## `ui_snapshot` → `input_tap(elementId)`

`ui_snapshot` trả danh sách element gọn (`id`, `text`, `bounds`, `clickable`).
`input_tap` nhận `elementId` (vd `e3`) → `node.performAction(CLICK)` trước,
fallback gesture/root — client không cần đoán tọa độ.
