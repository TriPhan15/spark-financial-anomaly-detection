# 04. Bảy feature hành vi

Model B không nhìn số tiền tuyệt đối. Nó nhìn một khoản chi so với thói quen của chính người đó. Tài liệu này mô tả từng feature, cách hai phía (Spark và API) cùng tính ra một con số, và cách kiểm chứng điều đó.

## 1. Định nghĩa

Gọi x là giao dịch đang xét của một người dùng, xảy ra lúc t. H là tối đa 100 giao dịch gần nhất của người đó xảy ra trước t một cách nghiêm ngặt (cùng thời điểm không tính), chỉ lấy giao dịch có số tiền dương.

| # | Feature | Công thức | Ý nghĩa |
|---|---|---|---|
| 1 | `hour` | giờ của t (0 đến 23) | Gian lận trong Sparkov tập trung về đêm |
| 2 | `day_of_week` | 0 = thứ Hai ... 6 = Chủ nhật | Nhịp chi tiêu trong tuần |
| 3 | `category_idx` | vị trí danh mục trong danh sách 14 danh mục Sparkov | Một số danh mục có tỉ lệ gian lận cao hơn hẳn |
| 4 | `amt_ratio_user` | số tiền của x / trung bình số tiền trong H | Khoản này gấp bao nhiêu lần mức chi thường ngày |
| 5 | `amt_ratio_user_cat` | số tiền của x / trung bình số tiền trong H cùng danh mục với x; nếu H không có giao dịch cùng danh mục thì bằng feature 4 | So với chính danh mục đó |
| 6 | `txn_count_24h` | số giao dịch trong H thuộc khoảng [t - 86.400 giây, t) | Chi dồn dập |
| 7 | `secs_since_prev` | t - thời điểm giao dịch gần nhất trong H | Chi ngay sau giao dịch trước |

Nếu H có dưới 5 giao dịch thì đó là cold start: không tính feature, không chấm điểm.

Các hằng số nằm ở đầu `behavior_features.py`: `HISTORY_WINDOW = 100`, `MIN_HISTORY = 5`, `SECONDS_PER_DAY = 86_400`, `FEATURE_COLUMNS` (thứ tự 7 feature, cũng là thứ tự đưa vào mô hình).

## 2. Vì sao dùng tỉ lệ

- Sparkov tính bằng USD, app dùng VND. Một bữa trưa 12 USD và một bữa trưa 50.000 đồng đều có tỉ lệ khoảng 1 so với thói quen của người đó. Mô hình học trên USD vẫn dùng được cho VND.
- Đề tài hỏi "khoản này có lệch khỏi thói quen không", và tỉ lệ đo đúng điều đó. 5 triệu là bình thường với người hay chi 5 triệu, và bất thường với người chỉ chi 50 nghìn.

Số liệu từ notebook cho thấy feature tách được hai lớp (trung vị trên dữ liệu đủ lịch sử):

| Feature | Trung vị nhóm gian lận | Trung vị nhóm bình thường |
|---|---|---|
| `hour` | 22 | 14 |
| `amt_ratio_user` | 4,94 | 0,67 |
| `amt_ratio_user_cat` | 2,87 | 0,88 |
| `txn_count_24h` | 4 | 3 |
| `secs_since_prev` | 4.881 | 15.721 |

## 3. Hai bản cài đặt, một định nghĩa

| | Spark (`behavior_features_spark.py`) | Python (`behavior_features.py`) |
|---|---|---|
| Dùng khi | Huấn luyện, notebook | API phục vụ |
| Đầu vào | DataFrame 1,85 triệu dòng | Một giao dịch + danh sách lịch sử từ app |
| Lấy H | `Window.partitionBy("cc_num").orderBy("ts", "trans_num").rowsBetween(-101, -1)`, `collect_list`, rồi `filter(x -> x.ts < ts)`, rồi `slice` giữ 100 phần tử cuối | `_window()`: lọc `ts < t` và `amount > 0`, sắp theo thời gian, lấy 100 phần tử cuối |
| Trung bình | `aggregate(history, 0D, (acc, x) -> acc + x.amt) / size(history)`, dùng `try_divide` | `sum / len` |
| Đếm 24 giờ | `size(filter(history, x -> x.ts >= ts - 86400))` | `sum(1 for ts in window if ts >= t - 86400)` |
| Giây từ giao dịch trước | `ts - array_max(transform(history, x -> x.ts))` | `t - max(ts)` |
| Thứ trong tuần | `(dayofweek(local_time) + 5) % 7` (Spark: Chủ nhật = 1) | `datetime.weekday()` (thứ Hai = 0) |

Bản Spark import `HISTORY_WINDOW`, `MIN_HISTORY`, `SECONDS_PER_DAY`, `SPARKOV_CATEGORIES` từ bản Python, nên hằng số chỉ khai báo một lần.

### Quy ước thời gian

Cả hai phía coi thời gian là giờ địa phương không có múi giờ, rồi đọc nó như thể là UTC:

- Spark đặt `spark.sql.session.timeZone = UTC`, nên `unix_timestamp` không dịch giờ.
- Python `to_epoch_seconds()` bỏ `tzinfo` nếu có, gắn UTC, rồi lấy `timestamp()`.

Nhờ vậy 23:40 trong app là 23:40 trong feature `hour`, không bị lệch 7 tiếng theo giờ Việt Nam.

## 4. Ánh xạ danh mục

App có danh mục tiếng Việt do admin tạo. Mô hình chỉ biết 14 danh mục Sparkov:

`entertainment, food_dining, gas_transport, grocery_net, grocery_pos, health_fitness, home, kids_pets, misc_net, misc_pos, personal_care, shopping_net, shopping_pos, travel`

`map_category()` làm như sau:

1. Chuẩn hóa: chữ thường, đổi `đ` thành `d`, bỏ dấu (NFD rồi bỏ ký tự dấu).
2. Nếu tên đã là một danh mục Sparkov thì giữ nguyên (dùng khi replay dữ liệu Sparkov).
3. Thay ký tự không phải chữ số bằng khoảng trắng, đệm hai đầu, rồi so khớp từ nguyên vẹn với danh sách từ khóa. Quy tắc đầu tiên khớp sẽ thắng.
4. Không khớp gì thì về `misc_pos`.

So khớp nguyên từ để "an" (ăn) không khớp bên trong "ban" (bán). Bảng từ khóa theo thứ tự ưu tiên:

| Danh mục Sparkov | Từ khóa (sau khi bỏ dấu) |
|---|---|
| `food_dining` | an uong, an, uong, ca phe, cafe, coffee, tra sua, nha hang, quan an, do an, food |
| `gas_transport` | xang, di chuyen, giao thong, xe, grab, taxi, gui xe, transport |
| `kids_pets` | con cai, tre em, em be, thu cung, cho meo, pet, kids |
| `grocery_net` | di cho online, bach hoa online |
| `grocery_pos` | sieu thi, di cho, cho, tap hoa, bach hoa, thuc pham, grocery |
| `shopping_net` | mua sam online, shopee, lazada, tiki, online |
| `shopping_pos` | mua sam, quan ao, giay dep, thoi trang, shopping |
| `travel` | du lich, khach san, ve may bay, travel, hotel |
| `entertainment` | giai tri, phim, rap phim, game, karaoke, entertainment |
| `health_fitness` | suc khoe, y te, thuoc, benh vien, gym, the thao, health |
| `personal_care` | lam dep, cat toc, my pham, spa, cham soc ca nhan |
| `home` | nha, nha cua, dien, nuoc, dien nuoc, internet, wifi, thue nha, hoa don, home |
| `misc_net` | dich vu online, dang ky, subscription |

Một số kết quả thực tế (chạy `map_category`):

| Danh mục app | Ra |
|---|---|
| Ăn uống, Cà phê | `food_dining` |
| Đi chợ | `grocery_pos` |
| Di chuyển | `gas_transport` |
| Mua sắm | `shopping_pos` |
| Mua sắm online | `shopping_net` |
| Hóa đơn, Nhà cửa | `home` |
| Giải trí | `entertainment` |
| Sức khỏe | `health_fitness` |
| Bán hàng, Giáo dục, Khác | `misc_pos` |

## 5. Ví dụ tính tay

Lịch sử của một người dùng (năm bữa trưa và một lần đi chợ):

| Thời điểm | Danh mục | Số tiền |
|---|---|---|
| 01/10/2026 12:00 | Ăn uống | 50.000 |
| 02/10/2026 12:00 | Ăn uống | 50.000 |
| 03/10/2026 12:00 | Ăn uống | 50.000 |
| 04/10/2026 12:00 | Ăn uống | 50.000 |
| 04/10/2026 17:30 | Đi chợ | 300.000 |
| 05/10/2026 12:00 | Ăn uống | 50.000 |

Giao dịch mới: 600.000 đồng, Ăn uống, thứ Ba 06/10/2026 12:30.

| Feature | Tính | Giá trị |
|---|---|---|
| `hour` | 12:30 | 12 |
| `day_of_week` | thứ Ba | 1 |
| `category_idx` | Ăn uống thành `food_dining`, vị trí 1 | 1 |
| `amt_ratio_user` | 600.000 / (550.000 / 6) | 6,55 |
| `amt_ratio_user_cat` | 600.000 / 50.000 | 12,0 |
| `txn_count_24h` | giao dịch gần nhất cách 24,5 giờ, ngoài khoảng 24 giờ | 0 |
| `secs_since_prev` | từ 05/10 12:00 đến 06/10 12:30 | 88.200 |

Có 6 giao dịch trước đó, đủ ngưỡng 5, nên giao dịch được chấm. Nếu bị gắn cờ, câu giải thích là "Số tiền gấp 12,0 lần mức chi trung bình của bạn cho danh mục này." Luật chặn thói quen không kích hoạt vì `amt_ratio_user` chỉ 6,55, dưới 10 (luật cần cả hai tỉ lệ từ 10 trở lên).

Có thể tự kiểm tra bằng Python từ thư mục `BigData`:

```python
from datetime import datetime
from behavior_features import Txn, compute_features, explain

history = [Txn(50000, datetime(2026, 10, d, 12, 0), "Ăn uống") for d in range(1, 6)]
history.append(Txn(300000, datetime(2026, 10, 4, 17, 30), "Đi chợ"))
features = compute_features(Txn(600000, datetime(2026, 10, 6, 12, 30), "Ăn uống"), history)
print(features, explain(features))
```

## 6. Kiểm chứng hai phía khớp nhau

Lúc huấn luyện, `train_behavior.py` chọn ngẫu nhiên 15 giao dịch gian lận và 15 giao dịch bình thường từ tập test, lấy lại mảng lịch sử thô của từng giao dịch, và ghi vào `artifacts/behavior/parity_sample.json` cùng với 7 feature và xác suất mà Spark tính.

`scripts/e2e_check.py` gửi 30 giao dịch đó qua `POST /v2/predict-anomaly` với lịch sử thô. API phải tự tính lại:

- feature lệch không quá `1e-6 × max(1, |giá trị|)`;
- xác suất lệch không quá `2e-6`.

Cả hai check đều PASS. Nghĩa là mô hình chạy sau API là đúng mô hình đã được đánh giá, với đúng feature lúc đánh giá.

## 7. Muốn đổi một feature

1. Sửa cả `behavior_features.py` và `behavior_features_spark.py`.
2. Huấn luyện lại: `bash scripts/train_on_cluster.sh behavior`. Metadata mới sẽ ghi danh sách feature mới.
3. Build lại API: `docker compose -f docker-compose.bigdata.yml --profile api up -d --build fraud-api`. API từ chối nạp nếu `featureColumns` trong metadata khác `FEATURE_COLUMNS` của code.
4. Chạy `python -X utf8 scripts/e2e_check.py`. Hai check parity phải PASS.
