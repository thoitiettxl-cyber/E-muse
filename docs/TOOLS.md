# MCP Tools E-Muse (20)

Mọi tool (trừ `device_list`, `tool_flags`) đều có `deviceId` optional —
bắt buộc khi có nhiều máy cùng kết nối.

| Tool | Loại | Nguy hiểm | Mô tả |
|---|---|---|---|
| `device_list` | query | - | Liệt kê máy đang kết nối (Worker serve, không qua máy) |
| `device_info` | query | - | Model, Android/SDK, root, accessibility, screen-capture |
| `shell_exec` | write | **yes** | Shell `sh -c`; `asRoot` → `su -c` (cần root) |
| `app_list` | query | - | App đã cài (`system: true` để gồm system app) |
| `app_info` | query | - | Chi tiết 1 package |
| `app_install` | write | **yes** | Cài APK base64 — hiện dialog xác nhận của hệ thống |
| `app_uninstall` | write | **yes** | Gỡ app — hiện dialog xác nhận |
| `app_start` | write | - | Mở app (package / action+uri+extras) |
| `app_stop` | write | **yes** | Force-stop (cần root) |
| `file_list` | query | - | Liệt kê thư mục |
| `file_pull` | query | - | Đọc file → base64 (tối đa 10MB) |
| `file_push` | write | **yes** | Ghi file base64 (root fallback ngoài sandbox) |
| `file_delete` | write | **yes** | Xóa file/thư mục |
| `screen_capture` | query | - | Screenshot PNG (image block + width/height); cần root hoặc MediaProjection đã cấp quyền |
| `input_tap` | write | - | Chạm element (`elementId` từ `ui_snapshot`, ưu tiên) hoặc (x, y) pixel |
| `input_swipe` | write | - | Vuốt (x1,y1 → x2,y2, durationMs) |
| `input_key` | write | - | Key code Android (3=HOME, 4=BACK, 66=ENTER...) |
| `input_text` | write | - | Gõ text vào field đang focus |
| `ui_dump` | query | - | Cây UI dạng JSON (cần accessibility hoặc root) |
| `ui_snapshot` | query | - | **List element gọn kiểu Eta/E-Jev** (id, text, desc, bounds) — cần accessibility; dùng id với `input_tap` thay vì đoán tọa độ |
| `tool_flags` | query | - | **Xem/bật/tắt tool** — luôn khả dụng, không tắt được chính nó |

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
- Trạng thái lưu trong Durable Object → survives deploy Worker.
- `EMUSE_WRITE_DISABLED=1` vẫn chặn mọi tool write ở call-time,
  không bị flag bypass.

## Gợi ý preset

- **Chỉ đọc + quan sát**: tắt hết write trừ `app_start` nếu cần —
  giữ `device_*`, `app_list`, `app_info`, `file_list`, `file_pull`,
  `screen_capture`, `ui_dump`, `tool_flags`.
- **Điều khiển full**: `{"reset": true}`.
