import 'package:flutter/material.dart';

class WalletModel {
  final int? id;
  final String name;
  final double balance;
  final int colorValue;
  final String iconName;
  final int? userId;

  const WalletModel({
    this.id,
    required this.name,
    required this.balance,
    this.colorValue = 0xFF2196F3,
    this.iconName = 'wallet',
    this.userId,
  });

  Color get color => Color(colorValue);

  Map<String, dynamic> toMap() {
    return {
      if (id != null) 'id': id,
      'name': name,
      'balance': balance,
      'color_value': colorValue,
      'icon_name': iconName,
      'user_id': userId,
    };
  }

  factory WalletModel.fromMap(Map<String, dynamic> map) {
    return WalletModel(
      id: map['id'] as int?,
      name: map['name'] as String? ?? '',
      balance: (map['balance'] as num?)?.toDouble() ?? 0.0,
      colorValue: map['color_value'] as int? ?? 0xFF2196F3,
      iconName: map['icon_name'] as String? ?? 'wallet',
      userId: map['user_id'] as int?,
    );
  }

  WalletModel copyWith({
    int? id,
    String? name,
    double? balance,
    int? colorValue,
    String? iconName,
    int? userId,
  }) {
    return WalletModel(
      id: id ?? this.id,
      name: name ?? this.name,
      balance: balance ?? this.balance,
      colorValue: colorValue ?? this.colorValue,
      iconName: iconName ?? this.iconName,
      userId: userId ?? this.userId,
    );
  }
}
