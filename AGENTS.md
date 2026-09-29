# AGENTS.md — E-Muse

Hướng dẫn làm việc trong repo này cho agent. Đọc trước khi đụng vào code.

## Repo gồm gì

- `app/` — app Android (`io.github.thoitiet.emuse`, versionName `0.1.0`):
  foreground service serve MCP-over-HTTP trên `127.0.0.1:18789`, public qua
  Cloudflare Tunnel (Quick hoặc named), thực thi lệnh, trả kết quả.
- `.github/workflows/release.yml` — CI: build release APK, sign, verify,
  upload artifact. Chỉ publish GitHub Release khi có tag hoặc manual
  `release_tag`.

Luồng: `Pi (MCP client) --HTTPS POST /mcp--> Cloudflare Tunnel --> App E-Muse --> Android`.

UI: màn hình cài đặt phong cách Miuix/HyperOS (section title + grouped cards,
bo góc 20dp), viết bằng View thuần trong `MainActivity.kt`.

## Lệnh thường dùng

```bash
# Test tool MCP (server "E-muse-direct" trong ~/.config/mcp/mcp.json,
# URL ở EMUSE_DIRECT_URL trong ~/.config/mcp/mcp.env)
~/workspace/skills/mcp/bin/mcp tools E-muse-direct --refresh
~/workspace/skills/mcp/bin/mcp call E-muse-direct tool_flags --args '{}'

# App: build local (cần Android SDK + JDK 17; sandbox thường fail ở Gradle daemon)
gradle :app:assembleDebug
```

## Quy tắc bắt buộc

1. **Không tạo tag / GitHub Release** nếu Boss chưa yêu cầu rõ.
2. **Không test trên điện thoại thật** (flash, su, thao tác đặc quyền)
   nếu Boss chưa duyệt.
3. **Không lưu raw key/token** vào file, memory hay chat log. Secret chỉ ở:
   GitHub Actions Secrets, hoặc file local mode 600
   (vd `~/workspace/user/emuse-keystore/`, `~/.config/mcp/mcp.env`,
   `hidden/tunnel_token.txt` — `hidden/` đã gitignore).
4. Key Boss paste trong chat: dùng tạm một lần rồi xóa.
5. Sửa app → push → chờ CI GitHub Actions verify (nguồn sự thật), không đoán.
6. Repo public: README/docs chỉ chứa hướng dẫn chung — không để lộ URL,
   token, API key hay domain riêng của ai.

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
- Binary Go (cloudflared) trên Android không đọc được system CA store →
  mọi TLS handshake fail `x509: certificate signed by unknown authority`.
  Fix: app tải Mozilla CA bundle (`curl.se/ca/cacert.pem`) vào filesDir,
  export `SSL_CERT_FILE` khi spawn cloudflared.
- Không bao giờ "tái tạo" secret từ trí nhớ — từng gửi nhầm tunnel token
  với phần secret bịa, làm tunnel fail auth. Luôn copy nguyên văn từ file.

## Thêm tool mới (checklist)

1. Thêm `Cmd` vào `Cmds` trong `app/.../Protocol.kt`.
2. Thêm `ToolDef` vào `app/.../mcp/ToolDefs.kt` (kind `query`/`write` đúng).
3. Implement executor trong `app/.../exec/` + case trong `CommandDispatcher.execute()`.
4. Cập nhật `docs/TOOLS.md` và bảng tool trong `README.md`.
