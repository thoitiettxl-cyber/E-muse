# Workflow làm việc E-Muse

## Sửa app Android (Kotlin)

Build local thường fail ở sandbox (Gradle daemon không chạy được) — nguồn
verify chính là **GitHub Actions**:

```bash
git add -A && git commit -m "<type>(<scope>): <mô tả>" && git push origin main
```

Rồi theo dõi run ở `https://github.com/thoitiettxl-cyber/E-muse/actions`.
Workflow `release.yml`: `assembleRelease` → sign (keystore từ secrets) →
verify APK + certificate → upload artifact. Chỉ publish Release khi có tag
hoặc manual `release_tag` — **không tự tạo tag**.

### Khi CI đỏ

1. Lấy job id từ run, rồi:
   `GET repos/thoitiettxl-cyber/E-muse/actions/jobs/{job_id}/logs`
   với GitHub surrogate Bearer → bắt **302 Location** → download trực tiếp
   **không kèm Authorization** (URL pre-signed). Đọc log text, không đoán.
2. Lỗi sign `Get Key failed: Given final block not properly padded` =
   secret lệch keystore local → set lại 4 secrets từ
   `~/workspace/user/emuse-keystore/` (keystore + `.store_pass` + `.key_pass`,
   mode 600). PUT secret trả 204 body rỗng là bình thường.
3. Lỗi compile Kotlin: đọc đúng dòng file báo lỗi, fix, push lại.

## Thêm tool mới

Xem checklist "Thêm tool mới" trong `AGENTS.md`, chi tiết protocol ở
`docs/PROTOCOL.md`, catalog tool ở `docs/TOOLS.md`.

## Bật/tắt tool (tool_flags)

Mặc định full 77 tools. Khi chỉ cần một số:

```bash
# Chỉ bật đọc + chụp màn hình, tắt hết write nguy hiểm
~/workspace/skills/mcp/bin/mcp call E-muse-direct tool_flags --args \
  '{"set":{"shell_exec":false,"app_install":false,"app_uninstall":false,"app_stop":false,"file_push":false,"file_delete":false,"input_tap":false,"input_swipe":false,"input_key":false,"input_text":false,"app_start":false}}'

# Về lại full
~/workspace/skills/mcp/bin/mcp call E-muse-direct tool_flags --args '{"reset":true}'
```

## Cài app lên máy (Boss tự làm)

1. Lấy APK từ CI artifact (`E-Muse-release.apk`) hoặc build local.
2. Mở app → đặt API key → bật Cloudflare Tunnel (Quick hoặc named) → bật "Chạy nền".
3. Bật Accessibility cho E-Muse (để tap/vuốt/gõ/UI dump không cần root).
4. Pi gọi qua MCP server `E-muse-direct` trong `~/.config/mcp/mcp.json`
   (`EMUSE_DIRECT_URL` trong `~/.config/mcp/mcp.env`, mode 600).
