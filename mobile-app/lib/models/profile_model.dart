import 'package:flutter/material.dart';

class ProfileOption {
  final String title;
  final IconData icon;
  final Function() onTap;

  ProfileOption({required this.title, required this.icon, required this.onTap});
}

class UserProfile {
  final String name;
  final String email;
  final String avatarUrl;

  UserProfile({
    required this.name,
    required this.email,
    required this.avatarUrl,
  });
}
