# UI Redesign — E-Muse (2026-09-29)

Boss feedback (5 điểm):
1. Chưa có menu Tools như Eta → không biết trực quan E-Muse hỗ trợ tools gì cho MCP.
2. Permissions không trực quan.
3. Settings không trực quan.
4. Bóng nổi không hiển thị nổi + log.
5. Home hiện tại hỗn loạn.

## Thiết kế

Phong cách: Miuix/HyperOS (section title + grouped cards, bo 20dp), View thuần
(không Compose — E-Muse dùng View thuần, không đổi stack).

### Navigation mới (bottom nav, 4 tab)
- **Home**: status card (Service / MCP local / Tunnel URL / Root) + 3 switch
  (Chạy nền, Bóng nổi, Tunnel) + nav cards tới Tools / Quyền / Cài đặt.
- **Tools** (mới, ToolsActivity): kiểu Eta — section title theo ToolGroup
  (4 nhóm) + grid 2 cột card tool. Mỗi card: icon tròn màu theo nhóm,
  tên tool, mô tả ngắn tiếng Việt. Header mỗi section có switch bật/tắt
  cả nhóm (thay cho "Quyền tool" trên Home cũ).
- **Quyền** (mới, PermissionsActivity): kiểu Eta — danh sách status rows:
  "Đã bật" (xanh) / "Cần chú ý" (cam). Bấm row → mở settings hoặc request
  quyền. Bỏ kiểu tick-checkbox "chọn rồi cấp" (khó hiểu).
  Gồm: runtime permissions (danh bạ, cuộc gọi, SMS, lịch, media, vị trí),
  Accessibility, Chụp màn hình, Cài đặt APK, Dùng app, Root.
- **Cài đặt** (mới, SettingsActivity): API key + Tunnel (token, hostname)
  gom gọn một chỗ.

### Bóng nổi + log
- Kiểm tra/sửa tap-to-expand panel log (hiện tại user báo không hiện).
- Panel: header E-Muse + nút ✕, log scroll 30 dòng gần nhất, màu trạng thái
  trên viền (xanh lá = connected, cam = busy, xám = offline).

### Nguồn dữ liệu Tools screen
- `TOOL_DEFS` (ToolDefs.kt): name + description (tiếng Anh gốc).
- `TOOL_GROUP_BY_NAME` (ToolGroups.kt): nhóm của từng tool.
- Mô tả tiếng Việt ngắn: map `TOOL_VI_DESC` mới trong ToolsActivity
  (fallback: description gốc cắt ngắn).

## Validation
- `scripts/verify-phase.sh` trước mỗi commit (bắt buộc theo AGENTS.md).
- CI GitHub Actions build release APK (nguồn sự thật).
- Không test trên máy thật khi Boss chưa duyệt (quy tắc repo).

## Trạng thái
- [x] ToolsActivity + grid cards + group switches
- [x] PermissionsActivity kiểu Eta
- [x] SettingsActivity
- [x] MainActivity gọn lại + bottom nav
- [x] Sửa FloatingOverlay panel log
- [x] verify-phase.sh pass
- [ ] Commit + push, chờ CI — CHƯA LÀM theo yêu cầu: để uncommitted cho Boss review trước

## Ghi chú implement (2026-09-29)
- `UiKit.kt` mới: theme tokens + builders dùng chung (sectionTitle, hintText,
  card, divider, circleIcon, switchRow, actionRow, infoRow, inputRow,
  navCard, header) + `addBottomNav(activity, selected)` (4 tab: Trang chủ /
  Tools / Quyền / Cài đặt, FLAG_ACTIVITY_REORDER_TO_FRONT).
- `ToolsActivity.kt`: 4 section theo ToolGroup + section "Hệ thống"
  (tool_flags); mỗi section có header card với switch bật/tắt cả nhóm;
  grid 2 cột; card có icon tròn màu theo nhóm + mô tả tiếng Việt
  (TOOL_VI_DESC, 77 tools; fallback description gốc cắt 120 ký tự).
- `PermissionsActivity.kt`: rows "Đã bật"/"Cần chú ý", bấm row để request
  quyền hoặc mở settings tương ứng; MediaProjection launcher chuyển từ
  MainActivity sang đây; Root là info row.
- `SettingsActivity.kt`: API key + Tunnel (switch/token/hostname/URL copy);
  persist trong onPause + restart tunnel khi đổi token/hostname.
- `MainActivity.kt`: viết lại gọn (status card + 3 switch + nav cards),
  dùng UiKit; bỏ projectionLauncher, group switches, checkbox quyền.
- `FloatingOverlay.kt`: bỏ OnClickListener riêng, tap-vs-drag trong một
  OnTouchListener duy nhất (DOWN consume → tap gọi togglePanel trực tiếp);
  thêm badge đỏ đếm số dòng log trên bóng nổi; panel force visible +
  bringToFront sau addView.
- `AndroidManifest.xml`: khai báo 3 activity mới (exported=false).
- `ToolGroups.kt`: message groupBlockReason đổi "section Quyền tool" →
  "tab Tools".
- Không commit/push — chờ Boss review, rồi CI build release APK mới verify
  compile thật (sandbox không chạy được Gradle daemon).
