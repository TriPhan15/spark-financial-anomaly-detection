import 'package:flutter/material.dart';

enum CategoryType { expense, income }

class CategoryModel {
  final int? id;
  final String name;
  final String iconName;
  final int colorValue;
  final CategoryType type;
  final int? userId;

  const CategoryModel({
    this.id,
    required this.name,
    this.iconName = 'category',
    required this.colorValue,
    required this.type,
    this.userId,
  });

  Color get color => Color(colorValue);

  Map<String, dynamic> toMap() {
    return {
      if (id != null) 'id': id,
      'name': name,
      'icon_name': iconName,
      'color_value': colorValue,
      'type': type == CategoryType.expense ? 0 : 1,
      'user_id': userId,
    };
  }

  factory CategoryModel.fromMap(Map<String, dynamic> map) {
    return CategoryModel(
      id: map['id'] as int?,
      name: map['name'] as String? ?? '',
      iconName: map['icon_name'] as String? ?? 'category',
      colorValue: map['color_value'] as int? ?? 0xFF9E9E9E,
      type: (map['type'] as int?) == 0
          ? CategoryType.expense
          : CategoryType.income,
      userId: map['user_id'] as int?,
    );
  }

  CategoryModel copyWith({
    int? id,
    String? name,
    String? iconName,
    int? colorValue,
    CategoryType? type,
    int? userId,
  }) {
    return CategoryModel(
      id: id ?? this.id,
      name: name ?? this.name,
      iconName: iconName ?? this.iconName,
      colorValue: colorValue ?? this.colorValue,
      type: type ?? this.type,
      userId: userId ?? this.userId,
    );
  }
}
