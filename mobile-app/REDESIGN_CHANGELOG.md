# Nhật ký Redesign — LTDD_Finance (DonutPay)

> Tổng hợp toàn bộ thay đổi UI/UX trong session. Nguyên tắc xuyên suốt: **chỉ đổi giao diện + thêm tiện ích, giữ nguyên logic chức năng**. Nhãn khác trên mockup được đổi tên về đúng chức năng đã có của đồ án (không thêm chức năng mới).

Ngày: 2026-05-30
Theme: **Light + Aubergine Purple** (đảo từ dark-navy + mint/pink + glassmorphism cũ).

---

## 1. Tệp THÊM MỚI

| Đường dẫn | Mục đích |
|---|---|
| `lib/widgets/dialogs/app_dialog.dart` | Widget dialog dùng chung (success / error / info / confirm). Thay cho thư mục `widgets/dialogs/` trước đó bỏ trống (chỉ có `.gitkeep`). |
| `REDESIGN_CHANGELOG.md` | File này. |

## 2. Tệp XÓA

- Không xóa file nào.
- `widgets/common/custom_text_field.dart`: **giữ lại** — không còn dùng ở login/register (đã thay bằng `_input` nội bộ) nhưng vẫn dùng ở `set_user_info.dart`.

---

## 3. Tệp SỬA — chi tiết theo đường dẫn

### `lib/core/theme/app_theme.dart` (nền tảng — mọi màn hình đọc từ đây)
- Repurpose hằng số màu cũ (navy*/mint*/textWhite...) sang giá trị **light + tím cà**, để mọi màn hình đổi theme mà không phải sửa từng file.
- `themeNotifier` mặc định `ThemeMode.light`; `darkTheme => lightTheme`.
- Thêm helper: `softShadow`, `card`, `purpleCardGradient`, `darkCardGradient`.
- **+ Animation:** thêm `pageTransitionsTheme` (FadeUpwards cho Android/Linux/Windows, Cupertino cho iOS/macOS) → **mọi `MaterialPageRoute` mượt fade+slide tự động**.
- Thêm `import 'package:flutter/cupertino.dart';`.

### `lib/main.dart`
- Theme mặc định lưu trong prefs: `'dark'` → `'light'`.

### `lib/screens/main_scaffold.dart`
- Bottom nav: bỏ glassmorphism (`BackdropFilter`) → `BottomAppBar` trắng, có nhãn (Trang chủ / Thống kê / AI / Cá nhân).
- FAB → tím (`AppTheme.primary`), icon trắng. Sheet quét hóa đơn → nền tím nhạt.
- **+ Animation:** nav item dùng `AnimatedScale` (icon phóng to khi chọn) + `AnimatedDefaultTextStyle` (nhãn).

### `lib/screens/dashboard/dashboard_page.dart`
- Thẻ **Tổng tài sản**: bỏ glass (trắng 5% → vô hình trên nền sáng) → **gradient tím + đổ bóng**, chữ trắng.
- Phần trend: bỏ 2 ô Thu nhập/Chi tiêu → **1 capsule** (mũi tên ↑/↓ + `%` + "so với tháng trước"). % = net tháng này vs tháng trước.
- Chuông thông báo: mở sheet **defer sang frame sau** (`addPostFrameCallback`) → né assertion `mouse_tracker.dart` (lỗi debug desktop).
- **+ Animation:** số dư ẩn/hiện dùng `AnimatedSwitcher` fade.

### `lib/screens/ledger/ledger_page.dart`
- Viết lại layout 1 trang cuộn kiểu "Statistic" (mockup): bỏ `TabController` 4 tab.
- Lịch: **stepper gọn `‹ dd/MM/yyyy ›`**; `‹ ›` nhảy ±1 ngày; chạm ngày → mở **lịch tháng đầy đủ** (`showDatePicker`).
- Header kỳ: dropdown **Tuần / Tháng** điều khiển biểu đồ + tổng + danh sách.
- Biểu đồ cột: **2 màu riêng biệt** Thu = xanh lá (`positive`), Chi = hồng (`dangerColor`); legend cập nhật.
- Khối: Thu nhập/Chi tiêu, Giao dịch gần đây, Tổng kết (tổng thu/chi/số dư + ngân sách + xuất Excel).
- Bỏ `_dayStripController`, `_shiftMonth`; thêm `_shiftDay`, `_pickFullDate`.
- **+ Animation:** biểu đồ `AnimatedSwitcher` cross-fade khi đổi kỳ/tháng.
- Giữ nguyên: tìm kiếm, lọc loại, thêm giao dịch, xuất Excel, ngân sách, `reload()`.

### `lib/screens/profile/profile_page.dart`
- Bỏ banner gradient navy tối (sót từ theme cũ) → **header căn giữa**: tiêu đề "Cá nhân", avatar tròn tím, tên, email.
- Item các mục → **icon line đơn** (bỏ hộp nền màu).
- **Bỏ nút bút (edit)** ở action + **bỏ bảng `Ví tiền | Giao dịch | Tổng tài sản`** (kèm dọn code chết: `_buildStats/_statItem/_vDivider/_fmtCompact/_totalAssets/_txCount`).
- **Đăng xuất** → `AppDialog.confirm(danger)` "Bạn có chắc chắn?".

### `lib/screens/auth/login_page.dart`
- `StatelessWidget` → `StatefulWidget`.
- Bỏ "Xin chào / Đăng nhập Nhom7" → **"Donut Finance App"** căn giữa + phụ đề.
- **Chỗ đặt logo** (placeholder 88×88; `// TODO: Image.asset`).
- Thêm **nhãn** cho ô Email + Mật khẩu; nút **con mắt** ẩn/hiện mật khẩu.
- Thêm **Ghi nhớ đăng nhập** (checkbox) + "Quên mật khẩu?".
- Thêm **đăng nhập ngoài** (Google/Apple/Facebook — placeholder snackbar) + divider "Hoặc đăng nhập với".
- Lỗi đăng nhập → `AppDialog.error`.

### `lib/screens/auth/register_page.dart`
- Redesign: tiêu đề "Tạo tài khoản", **nhãn** từng ô, nút con mắt.
- **Checklist mật khẩu chỉ hiện khi focus ô mật khẩu** (`FocusNode` + `AnimatedSize`). Bỏ vòng tròn check cũ.
- Thêm **đăng ký ngoài** + dòng **"Bằng việc đăng ký, bạn đồng ý với Điều khoản và Điều kiện sử dụng"**.
- Lỗi đăng ký / email tồn tại → `AppDialog.error`.

### `lib/screens/profile/set_user_info.dart` (Thiết lập thông tin)
- **Bỏ ô Họ và tên** (đã có từ đăng ký; giữ `fullName` cũ khi lưu).
- Thêm: **Ngày sinh (DD/MM/YYYY)** — không bắt buộc, có thì kiểm tra đúng định dạng + ngày thật (`_isValidDob`), kèm nút mở **date picker**.
- **Email (khôi phục tài khoản)** — bắt buộc + kiểm tra định dạng (dùng khi quên mật khẩu / mất tài khoản).
- **Địa chỉ** — không bắt buộc.
- **Số điện thoại** — kiểm tra `0 + 9 số` nếu nhập.

### `lib/screens/ledger/add_transaction_page.dart`
- Thêm giao dịch: thành công → `AppDialog.success` rồi mới `pop`; thất bại / số tiền sai / hết phiên → `AppDialog.error`.

### `lib/models/user_model.dart`
- Thêm trường `address` (constructor, `toMap`, `fromMap`, `copyWith`).

### `lib/services/sqflite_helper.dart`
- Bảng `users` thêm cột `address TEXT`.
- `_dbVersion 4 → 5` + `onUpgrade`: `ALTER TABLE users ADD COLUMN address` (có guard PRAGMA, **không mất dữ liệu cũ**).

---

## 4. AppDialog — cách dùng lại

```dart
import 'package:finance_ai_app/widgets/dialogs/app_dialog.dart';

await AppDialog.success(context, title: 'Thành công', message: '...');
await AppDialog.error(context,   title: 'Lỗi',        message: '...');
await AppDialog.info(context,    title: 'Thông báo',  message: '...');

final ok = await AppDialog.confirm(context,
    title: 'Đăng xuất?', message: 'Bạn có chắc chắn?',
    confirmText: 'Đăng xuất', danger: true);
if (ok) { ... }
```
Có sẵn animation scale + fade (`showGeneralDialog`, `easeOutBack`).

---

## 5. Tổng kết animation đã thêm

| Vị trí | Hiệu ứng |
|---|---|
| Toàn app (mọi route) | `pageTransitionsTheme` fade + slide |
| AppDialog | scale (`easeOutBack`) + fade khi mở |
| Bottom nav | `AnimatedScale` icon + `AnimatedDefaultTextStyle` nhãn |
| Dashboard số dư | `AnimatedSwitcher` fade khi ẩn/hiện |
| Ledger biểu đồ | `AnimatedSwitcher` cross-fade khi đổi kỳ/tháng |
| Register checklist | `AnimatedSize` mở/đóng theo focus |

---

## 6. Lưu ý chạy

- DB cũ là v4 → migration v5 chạy khi mở DB lần tới. Dùng **full restart** (stop → run hoặc `R`), không phải hot reload `r`. Dữ liệu cũ giữ nguyên.
- Lỗi `mouse_tracker.dart` (nếu còn) = bug framework **chỉ debug + desktop (chuột)**, không xảy ra trên mobile/release.
- Logo đăng nhập: bỏ ảnh vào `assets/`, sửa `_logoPlaceholder` trong `login_page.dart`.
- 10 cảnh báo `flutter analyze` còn lại là **pre-existing**, không thuộc các file đã sửa trong session.

## 7. Còn lại (chưa đụng — gợi ý lần sau)
- Transfer: dialog Confirm/Success/Failed theo mockup.
- AI tab / Savings: tinh chỉnh spacing.
- Remember me: hiện chỉ là state UI, chưa lưu bền (wire sau nếu cần).
- Đăng nhập/đăng ký bằng app ngoài: hiện là placeholder (chưa có backend OAuth).
