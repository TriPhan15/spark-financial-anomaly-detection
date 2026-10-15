import 'package:flutter/material.dart';

import 'package:finance_ai_app/config/app_config.dart';
import 'package:finance_ai_app/core/extensions/build_context_extension.dart';
import 'package:finance_ai_app/core/utils/email_format.dart';
import 'package:finance_ai_app/screens/auth/password_recovery_errors.dart';
import 'package:finance_ai_app/screens/auth/reset_password_page.dart';
import 'package:finance_ai_app/services/remote_api_helper.dart';
import 'package:finance_ai_app/widgets/auth/auth_form_layout.dart';
import 'package:finance_ai_app/widgets/common/primary_button.dart';
import 'package:finance_ai_app/widgets/dialogs/app_dialog.dart';

/// First step of the password recovery: asks the server to email a one-time code (OTP) to the address typed here, then
/// opens [ResetPasswordScreen] for that address. Only the server can do this: in demo mode the screen just says so.
class ForgotPasswordScreen extends StatefulWidget {
  const ForgotPasswordScreen({super.key});

  @override
  State<ForgotPasswordScreen> createState() => _ForgotPasswordScreenState();
}

class _ForgotPasswordScreenState extends State<ForgotPasswordScreen> {
  final _formKey = GlobalKey<FormState>();
  final _emailController = TextEditingController();
  bool _isSending = false;
  // Errors show once the user has tried to send, then follow what they type.
  AutovalidateMode _autovalidateMode = AutovalidateMode.disabled;

  @override
  void dispose() {
    _emailController.dispose();
    super.dispose();
  }

  String? _validateEmail(String? value) {
    final email = value?.trim() ?? '';
    if (email.isEmpty) return context.loc.forgotPasswordEmailRequired;
    if (!EmailFormat.isValid(email)) {
      return context.loc.forgotPasswordEmailInvalid;
    }
    return null;
  }

  Future<void> _sendOtp() async {
    if (_isSending) return;
    final loc = context.loc;
    if (!AppConfig.isFull) {
      await AppDialog.info(
        context,
        title: loc.forgotPasswordTitle,
        message: loc.passwordRecoveryServerOnly,
      );
      return;
    }
    if (!_formKey.currentState!.validate()) {
      setState(() => _autovalidateMode = AutovalidateMode.onUserInteraction);
      return;
    }
    // Only the surrounding spaces go: the server looks the address up as it is typed, so its case is kept.
    final email = _emailController.text.trim();
    FocusScope.of(context).unfocus();
    setState(() => _isSending = true);
    try {
      await RemoteApiHelper.instance.forgotPassword(email: email);
    } catch (e) {
      PasswordRecoveryErrors.log(PasswordRecoveryRequest.sendOtp, e);
      if (!mounted) return;
      setState(() => _isSending = false);
      await AppDialog.error(
        context,
        title: loc.forgotPasswordSendFailed,
        message: PasswordRecoveryErrors.messageOf(
          PasswordRecoveryRequest.sendOtp,
          e,
          loc,
        ),
        buttonText: loc.dialogGotIt,
      );
      return;
    }
    if (!mounted) return;
    setState(() => _isSending = false);
    // Pushed, not replaced: back from the next step, the user can correct the address and ask again.
    Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => ResetPasswordScreen(email: email)),
    );
  }

  @override
  Widget build(BuildContext context) {
    final loc = context.loc;
    return AuthFormLayout(
      title: loc.forgotPasswordTitle,
      heading: loc.forgotPasswordHeading,
      description: loc.forgotPasswordDescription,
      child: Form(
        key: _formKey,
        autovalidateMode: _autovalidateMode,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            AuthFieldLabel(loc.forgotPasswordEmailLabel),
            TextFormField(
              controller: _emailController,
              keyboardType: TextInputType.emailAddress,
              autofillHints: const [AutofillHints.email],
              autocorrect: false,
              textInputAction: TextInputAction.send,
              onFieldSubmitted: (_) => _sendOtp(),
              validator: _validateEmail,
              decoration: InputDecoration(
                hintText: loc.forgotPasswordEmailHint,
              ),
            ),
            const SizedBox(height: 28),
            PrimaryButton(
              text: _isSending
                  ? loc.passwordRecoverySending
                  : loc.forgotPasswordSendOtp,
              onPressed: _isSending ? null : _sendOtp,
            ),
          ],
        ),
      ),
    );
  }
}
