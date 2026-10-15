// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Vietnamese (`vi`).
class AppLocalizationsVi extends AppLocalizations {
  AppLocalizationsVi([String locale = 'vi']) : super(locale);

  @override
  String get settings => 'Cài đặt';

  @override
  String get language => 'Ngôn ngữ';

  @override
  String get theme => 'Giao diện';

  @override
  String get dark => 'Tối';

  @override
  String get light => 'Sáng';

  @override
  String get system => 'Theo hệ thống';

  @override
  String get systemThemeDescription => 'Tự đổi theo cài đặt của thiết bị';

  @override
  String get english => 'English';

  @override
  String get vietnamese => 'Tiếng Việt';

  @override
  String get settingsSaveFailed =>
      'Không lưu được cài đặt. Thay đổi chỉ có hiệu lực đến khi đóng ứng dụng.';

  @override
  String get notificationsTitle => 'Thông báo';

  @override
  String get notificationsMarkAllRead => 'Đọc tất cả';

  @override
  String get notificationsEmpty => 'Chưa có thông báo nào.';

  @override
  String get notificationsLoadFailed => 'Không tải được thông báo.';

  @override
  String get notificationsLoadMoreFailed => 'Không tải thêm được thông báo.';

  @override
  String get notificationsRetry => 'Thử lại';

  @override
  String get notificationsMarkReadFailed =>
      'Không đánh dấu được thông báo là đã đọc. Vui lòng thử lại.';

  @override
  String get notificationsMarkAllReadFailed =>
      'Không đánh dấu được các thông báo là đã đọc. Vui lòng thử lại.';

  @override
  String get notificationsDetailUnavailable =>
      'Chưa thể mở chi tiết của thông báo này.';

  @override
  String get notificationsUnread => 'Chưa đọc';

  @override
  String get notificationTypeBudgetWarning => 'Sắp chạm hạn mức ngân sách';

  @override
  String get notificationTypeBudgetExceeded => 'Đã vượt ngân sách';

  @override
  String get notificationTypeTransactionAlert => 'Cảnh báo giao dịch';

  @override
  String get notificationTypeReceiptProcessed => 'Hóa đơn đã được xử lý';

  @override
  String get notificationTypeOther => 'Thông báo';

  @override
  String get sessionEnded =>
      'Phiên đăng nhập đã kết thúc, có thể do mật khẩu vừa được thay đổi. Vui lòng đăng nhập lại.';

  @override
  String get dialogGotIt => 'Đã hiểu';

  @override
  String get forgotPasswordTitle => 'Quên mật khẩu';

  @override
  String get forgotPasswordHeading => 'Nhận mã OTP';

  @override
  String get forgotPasswordDescription =>
      'Nhập email tài khoản của bạn. Chúng tôi sẽ gửi mã OTP để bạn đặt mật khẩu mới.';

  @override
  String get forgotPasswordEmailLabel => 'Email';

  @override
  String get forgotPasswordEmailHint => 'Nhập địa chỉ email';

  @override
  String get forgotPasswordEmailRequired => 'Vui lòng nhập email.';

  @override
  String get forgotPasswordEmailInvalid => 'Email không hợp lệ.';

  @override
  String get forgotPasswordSendOtp => 'Gửi mã OTP';

  @override
  String get forgotPasswordSendFailed => 'Không gửi được mã OTP';

  @override
  String get forgotPasswordRejected =>
      'Không thể gửi mã OTP. Vui lòng kiểm tra email và thử lại.';

  @override
  String get resetPasswordTitle => 'Đặt lại mật khẩu';

  @override
  String get resetPasswordHeading => 'Tạo mật khẩu mới';

  @override
  String resetPasswordDescription(String email) {
    return 'Nhập mã OTP đã được gửi tới $email, sau đó đặt mật khẩu mới.';
  }

  @override
  String get resetPasswordOtpLabel => 'Mã OTP';

  @override
  String resetPasswordOtpHint(int length) {
    return 'Mã gồm $length chữ số';
  }

  @override
  String get resetPasswordOtpRequired => 'Vui lòng nhập mã OTP.';

  @override
  String resetPasswordOtpInvalid(int length) {
    return 'Mã OTP phải gồm đúng $length chữ số.';
  }

  @override
  String get resetPasswordResendOtp => 'Gửi lại mã OTP';

  @override
  String resetPasswordResendIn(int seconds) {
    return 'Gửi lại mã sau $seconds giây';
  }

  @override
  String get resetPasswordOtpResent =>
      'Đã gửi lại mã OTP. Vui lòng kiểm tra email.';

  @override
  String get resetPasswordNewPasswordLabel => 'Mật khẩu mới';

  @override
  String get resetPasswordNewPasswordHint => 'Nhập mật khẩu mới';

  @override
  String get resetPasswordPasswordRequired => 'Vui lòng nhập mật khẩu mới.';

  @override
  String resetPasswordPasswordPolicy(int min, int max) {
    return 'Từ $min đến $max ký tự, gồm ít nhất một chữ cái và một chữ số.';
  }

  @override
  String get resetPasswordConfirmPasswordLabel => 'Xác nhận mật khẩu mới';

  @override
  String get resetPasswordConfirmPasswordHint => 'Nhập lại mật khẩu mới';

  @override
  String get resetPasswordConfirmRequired => 'Vui lòng xác nhận mật khẩu mới.';

  @override
  String get resetPasswordMismatch => 'Mật khẩu xác nhận không khớp.';

  @override
  String get resetPasswordSubmit => 'Đặt lại mật khẩu';

  @override
  String get resetPasswordSubmitting => 'Đang xử lý...';

  @override
  String get resetPasswordSuccessTitle => 'Đặt lại mật khẩu thành công';

  @override
  String get resetPasswordSuccessMessage =>
      'Mật khẩu đã được thay đổi. Vui lòng đăng nhập bằng mật khẩu mới.';

  @override
  String get resetPasswordBackToLogin => 'Về trang đăng nhập';

  @override
  String get resetPasswordFailed => 'Đặt lại mật khẩu thất bại';

  @override
  String get resetPasswordRejected =>
      'Không thể đặt lại mật khẩu. Vui lòng kiểm tra mã OTP và thử lại.';

  @override
  String get passwordShow => 'Hiện mật khẩu';

  @override
  String get passwordHide => 'Ẩn mật khẩu';

  @override
  String get passwordRecoverySending => 'Đang gửi...';

  @override
  String get passwordRecoveryServerOnly =>
      'Khôi phục mật khẩu cần kết nối máy chủ và không khả dụng ở chế độ demo.';

  @override
  String get passwordRecoveryNetworkError =>
      'Không thể kết nối máy chủ. Vui lòng kiểm tra mạng và thử lại.';

  @override
  String get passwordRecoveryTimeout =>
      'Máy chủ phản hồi quá lâu. Vui lòng thử lại.';

  @override
  String get passwordRecoveryServerError =>
      'Máy chủ đang gặp sự cố. Vui lòng thử lại sau.';

  @override
  String get passwordRecoveryUnexpectedError =>
      'Đã xảy ra lỗi. Vui lòng thử lại.';
}
