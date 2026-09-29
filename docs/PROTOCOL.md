# Protocol app ↔ Worker (WebSocket)

App mở WebSocket tới `/device/connect`. Message đầu tiên bắt buộc là `hello`:

```json
{ "type": "hello", "deviceId": "<ANDROID_ID>", "deviceName": "<Build.MODEL>", "token": "<EMUSE_API_KEY>" }
```

- Token sai/thiếu → socket bị đóng `4401`.
- Kết nối mới cùng `deviceId` sẽ đá kết nối cũ (`4400`).
- Socket không `hello` trong 15s → bị đóng `4401` (auth timeout).

## Worker → app (lệnh)

```json
{ "id": "<uuid>", "cmd": "shell.exec", "args": { "command": "ls /sdcard" } }
```

## App → worker (kết quả)

```json
{ "id": "<uuid>", "ok": true, "result": { ... } }
{ "id": "<uuid>", "ok": false, "error": "..." }
```

Worker chờ tối đa 120s (`timeoutMs` của từng tool, clamp 1s–120s); quá hạn hoặc
máy mất kết nối → lỗi `device timeout` / `device disconnected`.

## Danh sách `cmd`

Tên `cmd` phải khớp **cả hai phía**:

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
| `input.tap` | `input_tap` | `InputExecutor` |
| `input.swipe` | `input_swipe` | `InputExecutor` |
| `input.key` | `input_key` | `InputExecutor` |
| `input.text` | `input_text` | `InputExecutor` |
| `ui.dump` | `ui_dump` | `UiDumpExecutor` (accessibility; root fallback) |

Định nghĩa chuẩn: union `Cmd` trong `workers/mcp/protocol.ts` ↔ `Cmds`
trong `app/src/main/java/io/github/thoitiet/emuse/Protocol.kt`.
Thêm cmd mới phải sửa cả hai + `tools.ts` + executor + `CommandDispatcher`.
