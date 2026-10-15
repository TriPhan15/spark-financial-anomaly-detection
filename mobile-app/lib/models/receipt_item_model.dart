class ReceiptItemModel {
  String name;
  int quantity;
  double price;

  ReceiptItemModel({
    required this.name,
    required this.quantity,
    required this.price,
  });

  double get total => quantity * price;
}

class ReceiptModel {
  String storeName;
  String date;
  List<ReceiptItemModel> items;
  double tax;

  ReceiptModel({
    required this.storeName,
    required this.date,
    required this.items,
    this.tax = 0.0,
  });

  double get subTotal => items.fold(0, (sum, item) => sum + item.total);
  double get grandTotal => subTotal + tax;
}
