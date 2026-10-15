import 'package:flutter/foundation.dart';

@immutable
class UserModel {
  final int? id;
  final String email;
  final String password;
  final String fullName;
  final String? birthdate;
  final String? phone;
  final String? address;

  const UserModel({
    this.id,
    required this.email,
    required this.password,
    required this.fullName,
    this.birthdate,
    this.phone,
    this.address,
  });

  Map<String, dynamic> toMap() {
    return {
      'email': email,
      'password': password,
      'full_name': fullName,
      'birthdate': birthdate,
      'phone': phone,
      'address': address,
    };
  }

  factory UserModel.fromMap(Map<String, dynamic> map) {
    return UserModel(
      id: map['id'] as int?,
      email: (map['email'] as String?) ?? '',
      password: (map['password'] as String?) ?? '',
      fullName: (map['full_name'] as String?) ?? '',
      birthdate: map['birthdate'] as String?,
      phone: map['phone'] as String?,
      address: map['address'] as String?,
    );
  }

  UserModel copyWith({
    int? id,
    String? email,
    String? password,
    String? fullName,
    String? birthdate,
    String? phone,
    String? address,
  }) {
    return UserModel(
      id: id ?? this.id,
      email: email ?? this.email,
      password: password ?? this.password,
      fullName: fullName ?? this.fullName,
      birthdate: birthdate ?? this.birthdate,
      phone: phone ?? this.phone,
      address: address ?? this.address,
    );
  }
}
