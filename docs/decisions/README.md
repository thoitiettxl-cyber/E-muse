# Decisions

Biên bản quyết định kiến trúc (ADR) của E-Muse. Lưu các lựa chọn lasting về
sản phẩm, kiến trúc, tương thích, security, sở hữu dữ liệu và validation.

Dùng `docs/templates/decision.md`. Lựa chọn cục bộ trong lúc làm thì giữ
trong active plan, chỉ promote cái lasting thành ADR ở đây.

## Thêm ADR khi

- lựa chọn sản phẩm/kiến trúc lasting thay đổi;
- tương thích public hoặc sở hữu dữ liệu thay đổi;
- policy security hoặc recovery thay đổi;
- validation bị thêm/bớt/yếu đi đáng kể; hoặc
- thứ bậc nguồn sự thật thay đổi.

## Lịch sử

Quyết định bị thay thế: giữ file cũ, thêm dòng "Superseded by NNNN" ở đầu,
không xóa lịch sử — để agent không nhầm authority cũ với hành vi hiện tại.
