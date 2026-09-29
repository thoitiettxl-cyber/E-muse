# AGENTS.md — E-Muse

Hướng dẫn làm việc trong repo này cho agent. Đọc trước khi đụng vào code.

## Repo gồm gì

- `app/` — app Android (`io.github.thoitiet.emuse`, versionName `0.1.0`):
  foreground service giữ WebSocket tới Worker, thực thi lệnh, trả kết quả.
- `workers/mcp/` — Cloudflare Worker `e-muse-mcp` (TypeScript, tự cài tay MCP
  JSON-RPC 2.0, không dùng MCP SDK — theo mẫu KSHT):
  `index.ts` (entry) · `server.ts` (MCP handler) · `device.ts` (Durable Object
  `DeviceLink`) · `protocol.ts` (protocol + types chung) · `tools.ts`
  (định nghĩa 20 MCP tools) · `auth.ts`.
- `.github/workflows/release.yml` — CI: build release APK, sign, verify,
  upload artifact. Chỉ publish GitHub Release khi có tag hoặc manual
  `release_tag`.

Luồng: `Pi (MCP client) --POST /mcp--> Worker --WebSocket--> App E-Muse --> Android`.

## Lệnh thường dùng

```bash
# Worker: typecheck + deploy
cd ~/workspace/E-muse && npx tsc --noEmit && npm run deploy

# Test tool MCP (đã đăng ký server "E-muse" trong ~/.config/mcp/mcp.json)
~/workspace/skills/mcp/bin/mcp tools E-muse --refresh
~/workspace/skills/mcp/bin/mcp call E-muse tool_flags --args '{}'

# App: build local (cần Android SDK + JDK 17; sandbox thường fail ở Gradle daemon)
gradle :app:assembleDebug
```

## Quy tắc bắt buộc

1. **Không tạo tag / GitHub Release** nếu Boss chưa yêu cầu rõ.
2. **Không test trên điện thoại thật** (flash, su, thao tác đặc quyền)
   nếu Boss chưa duyệt.
3. **Không lưu raw key/token** vào file, memory hay chat log. Secret chỉ ở:
   GitHub Actions Secrets, `wrangler secret`, hoặc file local mode 600
   (vd `~/workspace/user/emuse-keystore/`, `~/.config/mcp/mcp.env`).
4. Key Boss paste trong chat: dùng tạm một lần rồi xóa.
5. Sửa Worker → typecheck → deploy → test thật qua `mcp call` rồi mới báo xong.
6. Sửa app → push → chờ CI GitHub Actions verify (nguồn sự thật), không đoán.

## Bài học đã trả giá

- `PackageInstaller.Session.commit()` cần `IntentSender`, không phải
  `PendingIntent` — bug compile từng nằm từ commit scaffold đầu tiên.
- CI sign fail `KeytoolException: Get Key failed: Given final block not
  properly padded` = `EMUSE_KEY_PASSWORD` sai. Keystore `emuse-release.keystore`
  (PKCS12) được tạo với **key password = store password** (file `.key_pass` cũ
  là stale, đã đồng bộ lại). Verify bằng Java `KeyStore.getKey("emuse", pass)`
  — đúng cái Gradle làm; `keytool -list` chỉ check storepass nên không đủ.
  `PUT` secret trả 204 body rỗng — code phải tolerate.
- Lấy CI log: `GET repos/.../actions/jobs/{job_id}/logs` với GitHub surrogate
  Bearer → bắt 302 Location → download trực tiếp **không kèm Authorization**
  (URL đã pre-signed). Response là log text, không phải ZIP. Đọc log trước,
  không đoán nguyên nhân.
- Sandbox chặn Gradle daemon local (TCP loopback giữa process lỗi kiểu lạ);
  GitHub Actions là nguồn verify build app chính.
- `hiddenapibypass` giữ `6.0` (đã verify aar đầy đủ + API tương thích).

## Thêm tool mới (checklist)

1. Thêm `Cmd` vào `workers/mcp/protocol.ts` **và** `Cmds` trong
   `app/.../Protocol.kt` (hai bên phải đồng bộ tên).
2. Thêm `ToolDef` vào `workers/mcp/tools.ts` (kind `query`/`write` đúng).
3. Implement executor trong `app/.../exec/` + case trong `CommandDispatcher`.
4. Worker tự chặn tool bị tắt (flag) — app không cần biết flag.
5. Cập nhật `docs/TOOLS.md`.
