# Đặc tả kiến trúc & Execution Plan — E-MUSE (bản hiệu quả)

> Trạng thái: **BẢN NHÁP — toàn bộ mục 2, 3 là ĐỀ XUẤT, chưa được duyệt.**
> Boss đã yêu cầu: không tự quyết kế hoạch. Mọi đề xuất đều đánh dấu
> `[ĐỀ XUẤT — CẦN BOSS QUYẾT]`.
> Ngày lập: 2026-09-29 (viết lại từ bản nháp, bổ sung kết quả 3 nghiên cứu:
> Eta device-side, Mangi-11/Eta, E-Jev v0.4.0). Người lập: Pi (subagent).
> File này chưa commit.

---

## 1. Vấn đề: "Pi thao tác chậm" là gì

### 1.1 Định nghĩa bằng số liệu

E-Muse là **remote hands**: Pi ra mọi quyết định, app chỉ observe + act.
TypeSafe Jev on-device đã nhanh 100% — **nút thắt nằm ở Pi thao tác từ xa**:

- **1 turn = 1 MCP tool call qua Cloudflare Tunnel**, mỗi call mất **vài giây**
  (round-trip HTTPS + forward + thực thi trên máy).
- Baseline đo ngày 2026-09-29 (smoke test Facebook: mở newsfeed, like + bình
  luận 3 bài — **dừng giữa chừng, chưa hoàn thành**): **≈ 25 turn**.

Phân rã 25 turn đó theo mẫu thao tác (ước tính từ log smoke test):

| Mẫu thao tác | Turn tốn | Vì sao lãng phí |
|---|---|---|
| 1 lần like | 3 (`ui_snapshot` → `input_tap` → `ui_snapshot` verify) | tap xong Pi không biết kết quả, phải snapshot lại |
| Snapshot bị cắt ở ~8000 ký tự (mcp CLI) | +1–2 / lần | Pi parse tay JSON dở, đôi khi phải snapshot lại |
| Mở composer bình luận | 2–3 | tap vùng cuối màn hình mới hiện field; thử-sai |
| Gõ + verify text | 2–3 | `input_text` không verify → Pi phải đọc lại field |
| Đợi màn hình load | N (poll thủ công) | không có công cụ "đợi" phía device |
| Tap nhầm do màn hình đổi (feed cuộn) | +2–3 / lần | không có freshness check, cache node cũ |

**Kết luận:** ~60% turn là *overhead giao thức* (verify lại, parse lại,
poll, sửa lỗi tap nhầm) — không phải *quyết định*. Plan này tấn công đúng
60% đó, không đụng vào triết lý remote-hands.

### 1.2 Nguyên tắc thiết kế (rút từ 3 nghiên cứu — ghi rõ nguồn)

| # | Nguyên tắc | Nguồn |
|---|---|---|
| P-A | **Pi chỉ chọn ý định, code điền tham số.** Pi chọn `elementId` + operation; app tự lo tọa độ, focus, submit. Không để Pi đoán tọa độ hay tự diễn giải snapshot thô. | Eta device-side (`JevActions.candidatesFor`, `JevPolicy.buildQuestions`) |
| P-B | **Gộp act + verify vào 1 turn phía device.** Sau action, device tự settle rồi trả trạng thái mới trong cùng response — Pi không cần turn verify riêng. | Eta settle 2-phase; Mangi-11/Eta (batch `[tap, observe]` trong 1 turn) |
| P-C | **Chỉ dispatch khi màn hình còn "tươi".** Mọi action trên node phải gắn `observation_id` của lần observe gần nhất; sai → `STALE`, bắt observe lại thay vì tap nhầm. | Mangi-11/Eta (`ObservationReferencePolicy`); E-Jev v0.4.0 (~12 dòng) |
| P-D | **Không retry mù, fail fast.** Timeout không chứng minh action chưa chạy → outcome `UNKNOWN` thì **cấm** replay (kể cả fallback root); caller re-observe. | Eta (`MainThreadCallGate`, `ShellActionOutcomePolicy`); v0.4.0 (no-retry) |
| P-E | **Sleep cố định ở device là ĐỦ cho remote hands.** tap 350ms / swipe 650ms / text 500ms sau action; chưa cần fingerprint-poll phức tạp vội. | E-Jev v0.4.0 (`waitForUiSettle`); E-Muse đã có `settleAfter()` |
| P-F | **Direct API thay GUI bất cứ khi nào được.** Tác vụ hệ thống (âm lượng, media, trạng thái) gọi thẳng Android API — 1 call thay chuỗi thao tác GUI. | Mangi-11/Eta ("đừng dùng GUI") |
| P-G | **Device tự lo việc chờ đợi.** `wait_for_text`: poll tree 350ms/lần, 1 call trả kết quả — thay N turn poll của Pi. | Mangi-11/Eta (`RootShellDeviceController.waitForText`) |
| P-H | **Kỷ luật rẻ trước, tinh vi sau.** 80% độ tin cậy đến từ kỷ luật đơn giản (fail-closed, freshness, no-retry, anti-loop), không phải executor phức tạp. Đừng vội port scroll contracts 4 file hay macro nguyên bản. | E-Jev v0.4.0 |

---

## 2. Kiến trúc mục tiêu (to-be)

**Bất biến:** giữ triết lý remote-hands (mục 1.1 bản nháp cũ: mọi quyết định
thuộc Pi; 1 chủ/1 máy; không agent loop on-device). Mọi mục con dưới đây là
**[ĐỀ XUẤT — CẦN BOSS QUYẾT]**.

Xếp theo **impact / độ khó**: rẻ + giảm nhiều turn lên trước.

### 2.1 [ĐỀ XUẤT] `tap_and_observe` — gộp 3 turn thành 1 (impact lớn nhất, dễ)

- **Vấn đề:** 1 thao tác UI tốn 3 turn (snapshot → tap → snapshot verify).
- **Mô tả:** 1 call = `tapElement(id)` → `settleAfter("tap")` **có sẵn**
  (`RootCommands.kt:94`, sleep 350ms — theo P-E, chưa cần fingerprint-poll)
  → `snapshot()` mới → trả **cả kết quả tap + snapshot mới trong cùng response**.
- **Signature gợi ý:**
  `tap_and_observe { elementId: string, observationId?: string }`
  → `{ tap: {ok, via, id}, snapshot: {observation_id, count, elements} }`
- **File thay đổi:** `exec/UiSnapshotter.kt` (thêm `tapAndObserve()`, tái dùng
  `tapElement()` + `settleAfter()` + `snapshot()`); `Protocol.kt`
  (+`Cmds.INPUT_TAP_OBSERVE`); `mcp/ToolDefs.kt` (kind `write`);
  `CommandDispatcher.kt` (+case); `docs/TOOLS.md`.
- **Acceptance:** 1 lần like = 1 turn; snapshot trả về phản ánh đúng trạng thái
  sau tap.

### 2.2 [ĐỀ XUẤT] `observation_id` binding — chống tap nhầm (rẻ, dễ)

- **Vấn đề:** `tapElement()` (`UiSnapshotter.kt:103`) dùng cache node cũ, không
  validate; feed Facebook cuộn liên tục → tap nhầm element đã đổi.
- **Mô tả:** mỗi `snapshot()` sinh `observation_id` (uuid tăng đơn điệu).
  `input_tap` / `tap_and_observe` nhận thêm `observationId` (optional ở phase
  này để không vỡ client cũ); nếu Pi gửi id mà **không khớp** snapshot mới
  nhất → lỗi `STALE_OBSERVATION`, **không dispatch tap**. (Theo P-C; v0.4.0
  chứng minh chỉ ~12 dòng.)
- **File thay đổi:** `exec/UiSnapshotter.kt` (sinh + lưu id, validate trước
  tap); `mcp/ToolDefs.kt` (thêm param `observationId` cho `input_tap`,
  `tap_and_observe`); `docs/TOOLS.md`.
- **Acceptance:** snapshot → scroll đổi màn hình → tap id cũ kèm observationId
  cũ → nhận `STALE_OBSERVATION`, không có tap nào dispatch.

### 2.3 [ĐỀ XUẤT] `wait_for_text` — device tự đợi (dễ, giảm N turn poll)

- **Vấn đề:** Pi "đợi load xong" bằng cách gọi nhiều `ui_snapshot` liên tiếp.
- **Mô tả:** poll accessibility tree mỗi 350ms tới khi text xuất hiện
  (case-insensitive, NFC-normalized cho tiếng Việt), timeout cấu hình được
  (mặc định 10s, tối đa 60s); trả về trong **1 call**.
  (Theo P-G.)
- **Signature gợi ý:** `wait_for_text { text: string, timeoutMs?: number }`
  → `{ found: boolean, elapsedMs: number }`
- **File thay đổi:** `exec/UiSnapshotter.kt` (thêm `waitForText()`);
  `Protocol.kt` (+`Cmds.UI_WAIT_TEXT`); `mcp/ToolDefs.kt`;
  `CommandDispatcher.kt`; `docs/TOOLS.md`.
- **Acceptance:** đoạn "đợi load xong" từ N turn poll → 1 turn.

### 2.4 [ĐỀ XUẤT] `ui_snapshot` gọn: `query` / `compact` / `maxNodes` (dễ)

- **Vấn đề:** snapshot luôn full tree (tối đa 500 node — `UiSnapshotter.kt:18`);
  mcp CLI cắt output ở ~8000 ký tự → Pi parse tay, tốn 1–2 turn/lần.
- **Mô tả:** thêm params: `query` (chỉ trả elements có text/desc chứa chuỗi),
  `compact` (bỏ `bounds` khi không cần tọa độ), `maxNodes` (mặc định 500).
  (Học "screenshot economy" của Mangi-11/Eta: trả ít nhất có thể, chỉ xin thêm
  khi cần.)
- **File thay đổi:** `exec/UiSnapshotter.kt`; `mcp/ToolDefs.kt`; `docs/TOOLS.md`.
- **Acceptance:** `ui_snapshot {query:"Thích", compact:true}` trên feed Facebook
  trả < 2000 ký tự, parse được ngay.

### 2.5 [ĐỀ XUẤT] Sửa fallback mù sau gesture timeout (rẻ, chống double-tap)

- **Vấn đề (bug tiềm ẩn):** `MuseAccessibilityService.gesture()`
  (`MuseAccessibilityService.kt:26`) chờ latch tới **8s**, trả `Boolean`;
  `InputExecutor.tap()` (`InputExecutor.kt:28-40`): nếu `svc.tap()` trả `false`
  (kể cả trường hợp **timeout nhưng gesture có thể đã dispatch**) → rơi xuống
  `shellOrThrow("input tap")` → **double-tap**. Vi phạm P-D.
- **Mô tả (bản rẻ, theo P-D + P-H):** phân biệt 3 outcome ở tầng service —
  `DISPATCHED` / `NOT_STARTED` / `UNKNOWN` (timeout). `UNKNOWN` →
  **không fallback root**, trả `{ok:false, code:"ACTION_OUTCOME_UNKNOWN",
  note:"re-observe before retrying"}` (mẫu đã có sẵn trong `shellOrThrow`,
  `InputExecutor.kt:14-21` — mở rộng ra cả đường accessibility).
  `CallGate` đầy đủ (state machine PENDING→RUNNING→FINISHED/CANCELLED, timeout
  3s như Eta) để Phase 2 nếu cần.
- **File thay đổi:** `MuseAccessibilityService.kt` (gesture trả outcome);
  `exec/InputExecutor.kt` (tôn trọng `UNKNOWN`: cấm fallback).
- **Acceptance:** giả lập gesture timeout → không có tap thứ hai từ root;
  response báo rõ `ACTION_OUTCOME_UNKNOWN`.

### 2.6 [ĐỀ XUẤT] Gõ tiếng Việt chắc chắn: read-back + NFC (dễ–trung bình)

- **Vấn đề:** `InputExecutor.text()` (`InputExecutor.kt:66-95`) `ACTION_SET_TEXT`
  ghi đè toàn bộ field, **không verify**, không chuẩn hóa NFC → lỗi dấu; Pi
  phải tốn turn đọc lại.
- **Mô tả:** sau `SET_TEXT` → `node.refresh()` → **read-back verify**
  (bỏ qua với password) → so sánh chuẩn hóa NFC → khôi phục cursor bằng
  `ACTION_SET_SELECTION`. (Theo Eta `setNodeText` + `JevInputCheck`.)
  Fallback `input text` (root) **không hỗ trợ Unicode** — giới hạn platform,
  chỉ document rõ, không fix được.
- **File thay đổi:** `exec/InputExecutor.kt`; `docs/TOOLS.md` (ghi giới hạn).
- **Acceptance:** gõ "Tiếng Việt có dấu 123" → read-back khớp 100%.

### 2.7 [ĐỀ XUẤT] `input_submit` — submit không double (dễ)

- **Vấn đề:** không có tool submit chắc chắn; Pi phải tap nút Gửi (thêm turn)
  hoặc `input_key(66)` mù.
- **Mô tả:** thử `ACTION_IME_ENTER` trên focused node trước (kể cả node không
  advertise) → fallback `input keyevent 66` (root); sau dispatch poll ngắn xem
  focus/keyboard đổi; outcome `UNKNOWN` → **dừng, không thử tiếp**.
  (Theo Eta `JevSubmit`.)
- **Signature gợi ý:** `input_submit {}` → `{ok, via, code?}`
- **File thay đổi:** `exec/InputExecutor.kt` (thêm `submit()`); `Protocol.kt`
  (+`Cmds.INPUT_SUBIT`); `mcp/ToolDefs.kt`; `CommandDispatcher.kt`;
  `docs/TOOLS.md`.
- **Acceptance:** submit comment 1 turn, không double-post (test thủ công).

### 2.8 [ĐỀ XUẤT] Overlay `TYPE_ACCESSIBILITY_OVERLAY` (rẻ, 1 hàm)

- **Vấn đề:** `FloatingOverlay.kt:242` dùng `TYPE_APPLICATION_OVERLAY` → orb
  lọt vào accessibility tree và screenshot; Pi có nguy cơ tap nhầm chính orb
  của mình.
- **Mô tả:** khi `MuseAccessibilityService.instance != null`, `overlayParams()`
  dùng `TYPE_ACCESSIBILITY_OVERLAY` → orb **không bị `ui_snapshot` quan sát,
  không dính screenshot**. (Theo Eta `JevOverlayHost`.)
- **File thay đổi:** `FloatingOverlay.kt` (1 hàm `overlayParams`).
- **Acceptance:** bật bóng nổi → `ui_snapshot` không chứa node của orb.

### 2.9 [ĐỀ XUẤT] 3 lớp anti-loop rẻ ở tầng app (dễ)

- **Vấn đề:** khi Pi ra quyết định lặp (tap cùng element mãi), app không tự bảo
  vệ — tốn turn vô ích.
- **Mô tả:** trong `CommandDispatcher`/`UiSnapshotter`, theo dõi:
  (a) signature `fingerprint:action` lặp → cảnh báo trong response;
  (b) counter no-progress (3 action liên tiếp không đổi màn hình) → báo Pi;
  (c) `STALE` liên tiếp 3 lần → gợi ý Pi đổi chiến lược.
  Chỉ **báo**, không tự dừng task (quyết định thuộc Pi — remote-hands).
  (Theo v0.4.0: STUCK / NO_PROGRESS / UNSTABLE_SCREEN.)
- **File thay đổi:** `CommandDispatcher.kt` (+ tracker nhỏ) hoặc
  `exec/ActionGuard.kt` mới.
- **Acceptance:** lặp tap cùng element 3 lần → response có `warning: "STUCK"`.

### 2.10 [ĐỀ XUẤT] Direct API tools batch 1 — "đừng dùng GUI" (trung bình)

- **Vấn đề:** tác vụ hệ thống (âm lượng, trạng thái máy) hiện phải đi qua GUI
  hoặc `shell_exec` thô, tốn nhiều turn.
- **Mô tả:** 3 tool gọi thẳng Android API (theo P-F; mô tả tool ghi rõ ưu tiên
  dùng thay GUI):
  - `device_volume { level?: number }` (AudioManager — dễ)
  - `device_status {}` (pin, màn hình, âm lượng hiện tại — dễ)
  - `media_control { action: play|pause|next|prev }` (MediaSession — **cần xác
    minh** API/quyền trên Android 16 trước khi code)
- **File thay đổi:** mới `exec/DeviceApiExecutor.kt`; `Protocol.kt` (+3 `Cmds`);
  `mcp/ToolDefs.kt`; `CommandDispatcher.kt`; `docs/TOOLS.md`.
- **Acceptance:** mỗi tác vụ hệ thống 1 call thay cho chuỗi thao tác GUI.

### 2.11 [ĐỀ XUẤT] `run_script` — macro batch (phase sau, chỉ khi cần)

- **Mô tả:** `run_script { steps: [{op: tap|swipe|key|text|wait_text, ...}] }` —
  device chạy tuần tự, dừng khi step fail, trả kết quả từng step.
  (Ý tưởng từ Mangi-11/Eta batch serial; **không** port `JevMacros` nguyên bản
  — quá gắn với Jev policy, theo P-H.)
- **Chỉ làm khi** 2.1–2.2 xong và Boss thấy Pi gọi từng tool vẫn chưa đủ nhanh.
- **File thay đổi:** mới `exec/ScriptExecutor.kt` (tái dùng `UiSnapshotter`,
  `InputExecutor`); `Protocol.kt`; `mcp/ToolDefs.kt`;
  `CommandDispatcher.kt`; `docs/TOOLS.md`.
- **Acceptance:** mở app → scroll → like 3 bài trong 1–2 turn; step fail dừng
  đúng chỗ.

### 2.12 FIX BẮT BUỘC (không phải đề xuất): xóa domain riêng khỏi source

- **Vấn đề:** domain riêng của Boss đang hardcode trong repo public —
  `Prefs.kt` (default `tunnelHostname`) và `MainActivity.kt:270`
  (`.ifEmpty { ... }`).
- **Fix:** cả hai chỗ → chuỗi rỗng `""` (user nhập tay khi dùng named tunnel).
- **Không cần quyết kiến trúc** — vệ sinh bảo mật, làm ngay khi Boss cho phép
  (hoặc Boss tự sửa rồi push).
- **Acceptance:** `grep -rn` không còn domain riêng trong toàn repo (trừ file
  local đã gitignore). **Không ghi literal domain vào spec này.**

---

## 3. Execution plan (ĐỀ XUẤT — chưa quyết)

Thứ tự: **vệ sinh bảo mật → kỷ luật rẻ → giảm turn → độ tin cậy → mở rộng**.
Boss duyệt tới phase nào thì làm tới đó.

### Phase 0 — Kỷ luật rẻ + vệ sinh (không đổi kiến trúc, impact/rủi ro thấp)

| Task | Việc (mục) | File | Acceptance |
|---|---|---|---|
| T0.1 | Xóa domain riêng → `""` (2.12) | `Prefs.kt`, `MainActivity.kt:270` | `grep` không còn domain riêng |
| T0.2 | `observation_id` binding (2.2) | `UiSnapshotter.kt`, `ToolDefs.kt`, `docs/TOOLS.md` | tap id cũ → `STALE`, không dispatch |
| T0.3 | Sửa fallback mù sau gesture timeout (2.5) | `MuseAccessibilityService.kt`, `InputExecutor.kt` | timeout → `ACTION_OUTCOME_UNKNOWN`, không double-tap |
| T0.4 | Overlay `TYPE_ACCESSIBILITY_OVERLAY` (2.8) | `FloatingOverlay.kt` | snapshot không thấy orb |
| T0.5 | Sửa doc stale: KDoc còn ref `workers/`, `ARCHITECTURE.md` còn `/device/connect`, key `"E-muse"` cũ trong `.mcp.json`, `docs/TOOLS.md` đếm tool | 5 file | không còn ref sai |

**Nghiệm thu phase:** CI xanh; E1–E2 (mục 5) pass; chưa cần giảm turn.

### Phase 1 — Giảm turn (mục tiêu: smoke test like 3 bài ≤ 12 turn)

| Task | Việc (mục) | File | Acceptance |
|---|---|---|---|
| T1.1 | `tap_and_observe` (2.1) — dùng `settleAfter()` có sẵn | `UiSnapshotter.kt`, `Protocol.kt`, `ToolDefs.kt`, `CommandDispatcher.kt`, `docs/TOOLS.md` | 1 lần like = 1 turn |
| T1.2 | `ui_snapshot` thêm `query`/`compact`/`maxNodes` (2.4) | `UiSnapshotter.kt`, `ToolDefs.kt`, `docs/TOOLS.md` | query gọn < 2000 ký tự, parse ngay |
| T1.3 | `wait_for_text` (2.3) | `UiSnapshotter.kt`, `Protocol.kt`, `ToolDefs.kt`, `CommandDispatcher.kt`, `docs/TOOLS.md` | đợi load = 1 turn |

**Nghiệm thu phase:** kịch bản E2 (like 3 bài) **≤ 12 turn** (baseline 25);
ghi số turn thực tế vào mục 5.3.

### Phase 2 — Độ tin cậy (mục tiêu: thao tác chắc, không double, không lỗi dấu)

| Task | Việc (mục) | File | Acceptance |
|---|---|---|---|
| T2.1 | `CallGate` đầy đủ (nâng cấp từ T0.3): state machine + timeout 3s (2.5) | mới `exec/CallGate.kt`, `MuseAccessibilityService.kt`, `UiSnapshotter.kt` | timeout chưa start → retry được; đã start → cấm retry |
| T2.2 | `input_text` read-back + NFC (2.6) | `InputExecutor.kt`, `docs/TOOLS.md` | gõ TV read-back khớp 100% |
| T2.3 | `input_submit` (2.7) | `InputExecutor.kt`, `Protocol.kt`, `ToolDefs.kt`, `CommandDispatcher.kt`, `docs/TOOLS.md` | submit 1 turn, không double-post |
| T2.4 | Anti-loop 3 lớp (2.9) | `CommandDispatcher.kt` / mới `exec/ActionGuard.kt` | lặp 3 lần → response có `warning` |

**Nghiệm thu phase:** E3 (comment 1 bài) pass không double; E4 (stale) pass;
E7 (gõ TV) read-back 100%.

### Phase 3 — Mở rộng (chỉ khi Phase 1–2 đạt mục tiêu)

| Task | Việc (mục) | File | Acceptance |
|---|---|---|---|
| T3.1 | Direct API tools batch 1 (2.10) | mới `exec/DeviceApiExecutor.kt`, `Protocol.kt`, `ToolDefs.kt`, `CommandDispatcher.kt`, `docs/TOOLS.md` | mỗi tác vụ hệ thống 1 call |
| T3.2 | `run_script` (2.11) — **chỉ khi Boss chốt cần** | mới `exec/ScriptExecutor.kt`, ... | 1–2 turn cho chuỗi 3 thao tác |
| T3.3 | Nâng cấp settle → fingerprint-poll 2-phase — **tùy chọn**, chỉ khi sleep cố định (P-E) tỏ ra không đủ trên máy thật | mới `exec/Settle.kt` | giảm flaky khi màn hình load chậm |
| T3.4 | Docs đồng bộ + review lại spec này | `docs/*`, `README.md` | docs khớp code |

**Nghiệm thu phase:** E2E full (like + comment 3 bài) **≤ 10 turn**.

**Phụ thuộc:** T1.1 sau T0.4 (snapshot mới không thấy orb); T2.1 sau T0.3;
T2.4 dùng `observation_id` từ T0.2; T3.2 sau T1.1 + T0.2; T3.3 độc lập.

---

## 4. Cái KHÔNG làm (và lý do)

| # | Không làm | Lý do |
|---|---|---|
| NG-A | Agent loop / model on-device (port `JevAgent`, `JevPolicy`) | Đổi triết lý remote-hands; Pi đã là lớp quyết định. TypeSafe Jev đã nhanh 100% ở vai trò của nó — vấn đề là Pi thao tác, không phải thiếu agent on-device |
| NG-B | Scroll contracts 4 file + axis guard của Eta/E-Jev | Quá tinh vi cho remote hands; `input_swipe` + `wait_for_text` đủ cho nhu cầu hiện tại (P-H) |
| NG-C | OCR hook (LSPosed + ColorOS) | Chưa test máy thật, phụ thuộc ROM, rủi ro privacy/cloud OCR; accessibility tree đủ cho Facebook |
| NG-D | Port `JevMacros` nguyên bản | Gắn chặt với Jev policy; `run_script` đơn giản (2.11) đạt hiệu quả tương đương nếu cần |
| NG-E | Đổi element id `e0…eN` sang tree-path (`ui.0.3`) | Đang hoạt động tốt với Pi; đổi chỉ thêm phức tạp, không giảm turn |
| NG-F | Risk tiers / confidence gating / LLM escalation | Cần signal từ model Jev; Pi qua MCP không có — Pi đã là escalation layer |
| NG-G | Fingerprint-poll settle ngay từ đầu | P-E: sleep cố định đã đủ; chỉ nâng cấp ở T3.3 nếu máy thật chứng minh cần |
| NG-H | `AccessibilityProtectionProtocol` (backend system_server) | Cần module ngoài app với quyền system; E-Muse không có |

---

## 5. Test plan — đo bằng số turn

### 5.1 Kịch bản E2E (trên máy Boss — CẦN DUYỆT trước mỗi lần chạy)

| # | Kịch bản | Bước | Pass khi | Cần task |
|---|---|---|---|---|
| E1 | Mở app + snapshot | `app_start` Facebook → `ui_snapshot` | 200, `count` > 0, parse được | — |
| E2 | Like 3 bài (đo turn) | `tap_and_observe` từng nút Thích | mỗi bài 1 turn; nút đổi trạng thái; **tổng ≤ 12** | T1.1 |
| E3 | Comment 1 bài | mở composer → `input_text` (nội dung Boss duyệt trước) → `input_submit` | comment xuất hiện, **không double** | T2.2, T2.3 |
| E4 | Stale handling | snapshot → scroll đổi màn hình → tap id cũ | `STALE_OBSERVATION`, không dispatch | T0.2 |
| E5 | Overlay vô hình | bật bóng nổi → `ui_snapshot` | không có node của orb | T0.4 |
| E6 | Auth | key sai / tool bị tắt | 401 / `-32000 Tool ... is disabled` | — |
| E7 | Gõ tiếng Việt | `input_text` "Tiếng Việt có dấu 123" → read-back | khớp 100% (NFC) | T2.2 |
| E8 | Anti-loop | tap cùng element 3 lần liên tiếp | response có `warning: "STUCK"` | T2.4 |

### 5.2 Verify không cần máy thật

- **CI GitHub Actions là nguồn sự thật cho compile**: push → chờ run xanh.
  Không đoán lỗi build.
- Checklist thêm tool mới (AGENTS.md): `Cmd` trong `Protocol.kt` → `ToolDef`
  trong `ToolDefs.kt` (kind đúng) → executor trong `exec/` + case trong
  `CommandDispatcher.execute()` → cập nhật `docs/TOOLS.md` + bảng tool README.
- `tool_flags` cho phép tắt tool mới khi test để cô lập lỗi.

### 5.3 Bảng đo turn (điền sau mỗi phase)

| Kịch bản | Baseline (2026-09-29) | Sau Phase 1 | Sau Phase 2 | Sau Phase 3 |
|---|---|---|---|---|
| Like 3 bài (E2) | ~25 turn (cả smoke test, dừng giữa chừng) | mục tiêu ≤ 12 | — | — |
| Comment 1 bài (E3) | chưa đo được (chưa post được comment nào) | — | mục tiêu ≤ 5 | — |
| Full E2E (E2+E3×3) | chưa hoàn thành | — | — | mục tiêu ≤ 10 |

---

## 6. Câu hỏi [CẦN BOSS QUYẾT]

1. [CẦN QUYẾT] Duyệt các đề xuất 2.1–2.11 tới phase nào (Phase 0 / 1 / 2 / 3)?
   Khuyến nghị của spec: Phase 0+1 trước (rẻ, giảm turn rõ rệt), Phase 2 sau
   khi đo thực tế.
2. [CẦN QUYẾT] T0.1 (xóa domain riêng): Boss tự sửa hay cho Pi làm rồi push?
3. [CẦN QUYẾT] T3.2 `run_script`: có cần không, hay Pi gọi từng tool gộp
   (`tap_and_observe`) là đủ?
4. [CẦN QUYẾT] Có cho test E2E trên máy thật (like/comment Facebook thật)
   không? Nội dung comment do ai duyệt trước mỗi lần chạy?
5. [CẦN QUYẾT] Batch direct API tools đầu (T3.1) gồm những gì — volume/status
   như đề xuất, hay tool khác Boss cần hơn?
6. [CẦN QUYẾT] `observationId` ở Phase 0 để optional (không vỡ client cũ) hay
   bắt buộc ngay?

---

*Rủi ro kỹ thuật cần xác minh trên máy thật: `media_control` (API/quyền
Android 16); overhead nếu sau này bật accessibility-event listener cho
generation counter (hiện tại spec dùng uuid đơn giản, chưa cần listener);
Quick Tunnel URL đổi mỗi restart (dùng named tunnel cố định hoặc Boss báo URL
mới). Mọi thay đổi kiến trúc sau này cập nhật vào file này trước khi code.*
