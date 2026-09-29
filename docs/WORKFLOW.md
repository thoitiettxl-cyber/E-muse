# Workflow làm việc E-Muse

Theo mẫu repository-harness. Hành vi sản phẩm, kiến trúc, quyết định, plan,
code, test và tín hiệu runtime của repo là nguồn sự thật.

## Bản đồ repo

- `AGENTS.md`: bản đồ入口 và ranh giới thẩm quyền.
- `README.md`, `docs/ARCHITECTURE.md`, `docs/PROTOCOL.md`, `docs/TOOLS.md`:
  ý định và ràng buộc hiện tại.
- `docs/plans/`: việc bền vững; `docs/decisions/`: quyết định kiến trúc (ADR).
- Code, test, CI và tín hiệu runtime: sự thật chạy được và quan sát được.

## Chọn hình thức việc

### Việc có cần nhớ bền vững?

Việc bounded, một lần xong → làm trực tiếp, không cần plan.

Tạo một plan trong `docs/plans/` khi việc kéo dài qua session, có phụ thuộc
đáng kể, cần khôi phục, hoặc không resume an toàn được chỉ từ git diff.
Giữ outcome, ngữ cảnh, cách làm, rủi ro, tiến độ, quyết định và validation
trong cùng một file; xong thì chuyển sang `docs/plans/completed/`.

### Việc có cần người quyết?

Trước khi sửa, xác định thẩm quyền cho policy mới quan sát được từ bên ngoài.
Nếu còn lựa chọn khác biệt đáng kể, **dừng và hỏi quyết định nhỏ nhất**.
Không tự bịa policy.

Ranh giới đã rõ trong repo này, không cần hỏi lại:

- **Không tự tạo tag / GitHub Release** khi chưa có yêu cầu rõ.
- **Không tự chạy tool gây thay đổi/đặc quyền trên điện thoại thật**
  khi Boss chưa duyệt (Boss tự flash/test).
- APK proof duy nhất qua **GitHub Actions CI**, không build local
  (Gradle daemon không chạy được trong sandbox).

Các trường hợp khác còn mơ hồ (ý định sản phẩm, recovery khó, giảm
validation, security, compatibility) → dừng hỏi.

### Cái gì chứng minh hành vi?

- Compile + sign + APK: **GitHub Actions** (`E-Muse Build` → `assembleRelease`
  → sign bằng keystore từ secrets → verify APK/certificate → upload artifact).
  Plan, checklist hay lời báo "xong" không thay thế được CI xanh.
- Đọc log CI trực tiếp, không đoán. Khi CI đỏ:
  1. Lấy job id từ run, `GET repos/thoitiettxl-cyber/E-muse/actions/jobs/{job_id}/logs`
     với GitHub surrogate Bearer → bắt **302 Location** → download trực tiếp
     **không kèm Authorization** (URL pre-signed).
  2. Lỗi sign `Get Key failed: Given final block not properly padded` =
     secret lệch keystore local → set lại 4 secrets từ
     `~/workspace/user/emuse-keystore/` (keystore + `.store_pass` + `.key_pass`,
     mode 600). PUT secret trả 204 body rỗng là bình thường.
  3. Lỗi compile Kotlin: đọc đúng dòng file báo lỗi, fix, review lại phần fix,
     push lại.

### Việc có mã hóa invariant?

Với ranh giới kiến trúc / reliability / security / quality (ví dụ: 77 tools
map đúng group và fail-closed, audit log không ghi arguments, API key trong
EncryptedSharedPreferences, tunnel token không vào git):

1. Tìm thẩm quyền đã chấp nhận trong repo nêu ranh giới đó. Convention ngầm
   hay sở thích không ghi thành văn **không** phải policy — thiếu thẩm quyền
   thì dừng.
2. Thêm check cơ học nhỏ nhất bao phủ đúng phạm vi, báo lỗi nêu rõ vi phạm,
   rule và hành động tiếp theo.
3. Cần proof dương (hành vi cho phép pass) và proof âm (hành vi cấm fail đúng
   lý do).

Không tự ý đổi CI, hook hay branch protection khi chưa được duyệt riêng.

## Các luồng việc

### Đọc / review / chẩn đoán (read-only)

Chỉ đọc đúng phần cần cho câu trả lời, review, chẩn đoán, plan hoặc status.
Không sửa file. Phát hiện được gì cũng không mặc nhiên được quyền sửa.

### Thay đổi bounded

Nêu lại outcome → kiểm tra thẩm quyền, implementation, pattern, proof →
sửa nhỏ nhất thành một khối cohérent → chạy check liên quan → báo outcome,
thay đổi, bằng chứng và giới hạn. Không cần plan riêng.

### Thay đổi bền vững có plan

Tạo/resume một active plan, triển khai theo nhóm verifiable, promote quyết
định lasting thành ADR, chạy proof của repo, ghi kết quả rồi move plan sang
`docs/plans/completed/`.

### Vận hành app thật

Pi **không** tự thao tác trên điện thoại thật. Boss tự làm theo runbook:

1. Lấy APK từ CI artifact (`E-Muse-release.apk`).
2. Mở app → đặt API key → bật Cloudflare Tunnel (Quick hoặc named) → bật
   "Chạy nền".
3. Bật Accessibility cho E-Muse (tap/vuốt/gõ/UI dump không cần root).
4. Pi gọi qua MCP server `E-muse-direct` (`EMUSE_DIRECT_URL` trong
   `~/.config/mcp/mcp.env`, mode 600).

## Chuẩn hoàn tất

Một thay đổi được coi là xong khi: outcome tồn tại hoặc blocker đã nêu rõ,
sự thật trong repo còn hiện tại, proof phù hợp đã pass (hoặc gap đã công
khai), plan đã cập nhật, và báo cáo tách bạch facts / limits / việc chưa thử.
Mô tả không thay thế bằng chứng quan sát được.

## Phụ lục E-Muse

### Thêm tool mới

Checklist "Thêm tool mới" trong `AGENTS.md`; chi tiết protocol ở
`docs/PROTOCOL.md`; catalog ở `docs/TOOLS.md`. Tool mới phải map đúng group
gating và fail-closed (invariant mục trên).

### Bật/tắt tool (tool_flags)

Mặc định full 77 tools. Khi chỉ cần một số:

```bash
# Chỉ bật đọc + chụp màn hình, tắt hết write nguy hiểm
~/workspace/skills/mcp/bin/mcp call E-muse-direct tool_flags --args \
  '{"set":{"shell_exec":false,"app_install":false,"app_uninstall":false,"app_stop":false,"file_push":false,"file_delete":false,"input_tap":false,"input_swipe":false,"input_key":false,"input_text":false,"app_start":false}}'

# Về lại full
~/workspace/skills/mcp/bin/mcp call E-muse-direct tool_flags --args '{"reset":true}'
```
