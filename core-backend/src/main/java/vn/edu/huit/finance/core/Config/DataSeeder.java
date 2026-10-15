package vn.edu.huit.finance.core.Config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Random;

@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;
    private final Random random = new Random();

    @Override
    public void run(String... args) throws Exception {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class);
        if (count != null && count > 0) {
            System.out.println("Database đã có dữ liệu");
            return;
        }
        Integer userCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        if (userCount == null || userCount == 0) {
            System.out.println("Bảng users trống. Đang tạo user mẫu...");

            org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder encoder =
                    new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
            String pass = encoder.encode("123456");

            jdbcTemplate.update("INSERT INTO users (id, username, password, email, phone, role, active, wallet_balance) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    1, "admin", pass, "admin@huit.edu.vn", "0123456789", "ROLE_ADMIN", true, 10000000);
            jdbcTemplate.update("INSERT INTO users (id, username, password, email, phone, role, active, wallet_balance) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    2, "user_test", pass, "user@huit.edu.vn", "0987654321", "ROLE_USER", true, 5000000);
        }

        Integer categoryCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM categories", Integer.class);
        if (categoryCount == null || categoryCount == 0) {
            System.out.println("⚠️ Bảng categories trống. Đang tạo danh mục mẫu...");
            jdbcTemplate.update("INSERT INTO categories (id, name, type) VALUES (?, ?, ?)", 2, "Ăn uống", "EXPENSE");
            jdbcTemplate.update("INSERT INTO categories (id, name, type) VALUES (?, ?, ?)", 3, "Di chuyển", "EXPENSE");
            jdbcTemplate.update("INSERT INTO categories (id, name, type) VALUES (?, ?, ?)", 4, "Mua sắm", "EXPENSE");
            jdbcTemplate.update("INSERT INTO categories (id, name, type) VALUES (?, ?, ?)", 5, "Hóa đơn", "EXPENSE");
            jdbcTemplate.update("INSERT INTO categories (id, name, type) VALUES (?, ?, ?)", 6, "Thu nhập", "INCOME");
            jdbcTemplate.update("INSERT INTO categories (id, name, type) VALUES (?, ?, ?)", 8, "Khác", "EXPENSE");
            jdbcTemplate.execute(
                "SELECT setval(pg_get_serial_sequence('categories','id'), (SELECT MAX(id) FROM categories))");
        }
        LocalDate startDate = LocalDate.of(2025, 1, 1);
        int totalDays = 15;

        String[] officeCoffeePool = {"HL COFFEE", "THE COFFEE HOUSE", "PHUC LONG", "KATINAT", "HIGHLANDS"};
        String[] studentEatPool = {"MOMO*TRA SUA TOCO", "LOTTERIA", "XIEN QUE VIA HE", "MIXUE", "GONGCHA"};
        String[] groceryPool = {"COOPMART", "WINMART", "BACH HOA XANH", "AEON MALL", "MM MEGA MARKET"};
        String[] restaurantPool = {"HAIDILAO", "KICHI KICHI", "KING BBQ", "GOEGI HOUSE"};
        String[] peerNames = {"PHAN THANH TRI", "PHAN MINH TIEN", "PHAM ANH TUAN", "NGUYEN HO MINH TRONG", "NGUYEN SON TUNG"};
        String[] voucherPool = {"FREESHIP", "MEGA_SALE", "PAYDAY50", "HUIT2026", "LIXITET"};

        for (int userId = 1; userId <= 2; userId++) {
            int userType = random.nextDouble() < 0.4 ? 1 : (random.nextDouble() < 0.8 ? 2 : 3);

            String favoriteCoffee = officeCoffeePool[random.nextInt(officeCoffeePool.length)];
            String secondaryCoffee = officeCoffeePool[random.nextInt(officeCoffeePool.length)];
            String favoriteGrocery = groceryPool[random.nextInt(groceryPool.length)];
            String favoriteRestaurant = restaurantPool[random.nextInt(restaurantPool.length)];

            double dynamicWalletState = 1.0;
            int currentState = 0;
            int daysRemainingInState = 0;

            for (int d = 0; d < totalDays; d++) {
                LocalDate currentLocalDate = startDate.plusDays(d);
                int dayOfMonth = currentLocalDate.getDayOfMonth();
                int dayOfWeek = currentLocalDate.getDayOfWeek().getValue();
                int month = currentLocalDate.getMonthValue();

                double inflationDrift = 1.0 + ((double) d / 365.0) * 0.045;
                double monthEndSqueeze = (dayOfMonth > 22) ? 0.4 : 1.0;

                boolean isPreTetWeek = (month == 1 && dayOfMonth >= 15 && dayOfMonth <= 24);
                boolean isTetHoliday = (month == 1 && dayOfMonth >= 25) || (month == 2 && dayOfMonth <= 2);
                boolean isSummer = (month >= 5 && month <= 8);
                boolean isVietnamPublicHoliday = (month == 4 && dayOfMonth >= 29) || (month == 5 && dayOfMonth <= 1) || (month == 9 && dayOfMonth == 2);
                boolean isEcommerceDoubleDay = (dayOfMonth == month) || (month == 11 && dayOfMonth == 11) || (month == 12 && dayOfMonth == 12);

                dynamicWalletState = 0.7 * dynamicWalletState + 0.3 * 1.0;
                if (dayOfMonth >= 5 && dayOfMonth <= 8) dynamicWalletState += 0.4;
                if (dayOfMonth >= 25) dynamicWalletState -= 0.15;
                dynamicWalletState = Math.max(0.2, Math.min(dynamicWalletState, 1.8));

                if (dayOfMonth == 1) {
                    insertRealTransaction(userId, "PHI QUAN LY TAI KHOAN THANG " + month, 11000, currentLocalDate.atTime(0, 0, random.nextInt(59)), 5, true, "Hóa đơn", "COMPLETED", null);
                }
                if (random.nextDouble() < 0.01) {
                    insertRealTransaction(userId, "THU PHI SMS BANKING", 11000, currentLocalDate.atTime(8, random.nextInt(30)), 5, true, "Hóa đơn", "COMPLETED", null);
                }

                if (daysRemainingInState > 0) {
                    daysRemainingInState--;
                } else {
                    currentState = 0;
                    if ((isSummer || isVietnamPublicHoliday) && userType != 1 && random.nextDouble() < 0.02) {
                        currentState = 1;
                        daysRemainingInState = 2 + random.nextInt(3);
                        double flightHotel = (3000000 + generateStrictLogNormal(4000000)) * inflationDrift;

                        String note = generateTextualChaos("VNPAY*TRAVELOKA_VN", "Thanh toan ve may bay");
                        String status = random.nextDouble() < 0.05 ? "CANCELLED" : "COMPLETED";
                        insertRealTransaction(userId, note, flightHotel, getGaussianTime(currentLocalDate, 9, 30, 45), 4, true, "Mua sắm", status, null);
                        if(status.equals("COMPLETED")) dynamicWalletState -= 0.4;
                    }
                }

                if (currentState == 1) {
                    insertRealTransaction(userId, generateTextualChaos("POS_RESTAURANT_LOCAL", ""), (250000 + random.nextInt(300000)) * inflationDrift, getGaussianTime(currentLocalDate, 12, 15, 30), 2, true, "Ăn uống", "COMPLETED", null);
                    insertRealTransaction(userId, generateTextualChaos("POS_SEAFOOD_BEACH", ""), (500000 + random.nextInt(800000)) * inflationDrift, getGaussianTime(currentLocalDate, 19, 0, 45), 2, true, "Ăn uống", "COMPLETED", null);
                    if (random.nextBoolean()) {
                        insertRealTransaction(userId, generateTextualChaos("GRAB*TRIP_TOURIST", ""), (50000 + random.nextInt(100000)) * inflationDrift, getGaussianTime(currentLocalDate, 15, 30, 60), 3, true, "Di chuyển", "COMPLETED", null);
                    }
                    continue;
                }

                boolean isActualPayDay = false;
                if (dayOfMonth == 5 && dayOfWeek < 6) isActualPayDay = true;
                else if (dayOfMonth == 4 && dayOfWeek == 5 && currentLocalDate.plusDays(1).getDayOfWeek().getValue() == 6) isActualPayDay = true;
                else if (dayOfMonth == 6 && dayOfWeek == 1 && currentLocalDate.minusDays(1).getDayOfWeek().getValue() == 7) isActualPayDay = true;

                if (isActualPayDay) {
                    dynamicWalletState = 1.6;
                    if (userType == 1) {
                        insertRealTransaction(userId, generateTextualChaos("MOMO*CHUYENTIEN_BOME", "Tien an thang " + month), 4200000 + random.nextInt(800000), getGaussianTime(currentLocalDate, 11, 0, 120), 6, false, "Thu nhập", "COMPLETED", null);
                    } else {
                        double baseSalary = (userType == 2) ? 15000000 : 28000000;
                        double tetBonus = isPreTetWeek ? baseSalary * 1.2 : 0;
                        insertRealTransaction(userId, generateTextualChaos("MBVCB.FT25_SALARY", "Luong T" + month), baseSalary + tetBonus, getGaussianTime(currentLocalDate, 15, 30, 90), 6, false, "Thu nhập", "COMPLETED", null);
                    }
                }

                if (dayOfMonth == 7) {
                    double baseRent = (userType == 1) ? 2200000 : ((userType == 2) ? 4800000 : 8000000);
                    double electricity = isSummer ? 850000 + random.nextInt(300000) : 400000 + random.nextInt(150000);
                    String billStatus = random.nextDouble() < 0.02 ? "PENDING" : "COMPLETED";
                    insertRealTransaction(userId, generateTextualChaos("AUTODEB_DIENNUOC", "Tien dien nuoc"), (baseRent + electricity) * inflationDrift, currentLocalDate.atTime(0, 10 + random.nextInt(20)), 5, true, "Hóa đơn", billStatus, null);
                }

                if (!isTetHoliday && !isVietnamPublicHoliday) {
                    if (userType == 2 && (dayOfWeek == 1 || dayOfWeek == 3 || dayOfWeek == 5)) {
                        if (random.nextDouble() < monthEndSqueeze) {
                            double price = (45000 + (random.nextInt(3) * 10000)) * inflationDrift;
                            String shop = (random.nextDouble() < 0.83) ? favoriteCoffee : secondaryCoffee;
                            String vCode = random.nextDouble() < 0.1 ? "MORNING_COFFEE" : null;
                            insertRealTransaction(userId, generateTextualChaos("POS_" + shop.replace(" ", ""), ""), price, getGaussianTime(currentLocalDate, 7, 45, 15), 2, true, "Ăn uống", "COMPLETED", vCode);
                        }
                    }
                }

                boolean isShoppingDay = (dayOfWeek == 6 || dayOfWeek == 7 || isPreTetWeek || isEcommerceDoubleDay || isVietnamPublicHoliday);
                if (isShoppingDay) {
                    double shoppingChance = isPreTetWeek ? 0.95 : (isEcommerceDoubleDay ? 0.80 : 0.40);
                    if (random.nextDouble() < (shoppingChance * dynamicWalletState * monthEndSqueeze)) {
                        double grocery = ((userType == 1) ? 150000 : 500000 + generateStrictLogNormal(800000)) * inflationDrift;
                        if ((isEcommerceDoubleDay || isPreTetWeek) && userType != 1) grocery *= 1.5;
                        dynamicWalletState -= 0.1;
                        String merchant = isEcommerceDoubleDay ? "SHOPEE_MALL_RETAIL" : favoriteGrocery;

                        String usedVoucher = isEcommerceDoubleDay ? voucherPool[random.nextInt(voucherPool.length)] : null;
                        String shopStatus = random.nextDouble() < 0.03 ? "CANCELLED" : "COMPLETED";
                        insertRealTransaction(userId, generateTextualChaos("QR_PAY_" + merchant.replace(" ", ""), ""), grocery, getGaussianTime(currentLocalDate, 15, 30, 90), 4, true, "Mua sắm", shopStatus, usedVoucher);
                    }

                    if ((dayOfWeek == 6 || isVietnamPublicHoliday) && random.nextDouble() < (0.65 * dynamicWalletState * monthEndSqueeze)) {
                        double dinner = ((userType == 1) ? 150000 : 450000 + generateStrictLogNormal(1000000)) * inflationDrift;
                        LocalDateTime dinnerTime = getGaussianTime(currentLocalDate, 19, 0, 45);
                        insertRealTransaction(userId, generateTextualChaos("POS_VNPAY_" + favoriteRestaurant.replace(" ", ""), ""), dinner, dinnerTime, 2, true, "Ăn uống", "COMPLETED", null);

                        if (random.nextDouble() < 0.70) {
                            insertRealTransaction(userId, generateTextualChaos("GRAB*RIDE", "Book xe ve nha"), 60000 + random.nextInt(50000), dinnerTime.plusHours(2), 3, true, "Di chuyển", "COMPLETED", null);
                        }

                        if (random.nextDouble() < 0.40) {
                            String friend = peerNames[random.nextInt(peerNames.length)];
                            double splitAmount = dinner / (2 + random.nextInt(3));
                            insertRealTransaction(userId, generateTextualChaos("CHUYEN KHOAN", "Tra tien an toi " + friend), splitAmount, dinnerTime.plusMinutes(15 + Math.abs((long)(random.nextGaussian() * 20))), 8, true, "Khác", "COMPLETED", null);
                        }
                    }
                }

                if (dayOfMonth == 12) {
                    insertRealTransaction(userId, "APPLE SERVICES BILLING", 19000, currentLocalDate.atTime(2, 14, random.nextInt(59)), 5, true, "Hóa đơn", "COMPLETED", null);
                }
                if (dayOfMonth == 20 && userType != 1) {
                    insertRealTransaction(userId, "NETFLIX.COM AMSTERDAM", 260000, currentLocalDate.atTime(3, 45, random.nextInt(59)), 5, true, "Hóa đơn", "COMPLETED", null);
                }

                if (dayOfWeek == 7 && random.nextDouble() < 0.15) {
                    LocalDateTime lateNight = getGaussianTime(currentLocalDate, 1, 30, 45);
                    insertRealTransaction(userId, generateTextualChaos("CIRCLE K", "Mua do an dem"), 65000 + random.nextInt(40000), lateNight, 2, true, "Ăn uống", "COMPLETED", null);

                    if (random.nextBoolean()) {
                        insertRealTransaction(userId, generateTextualChaos("CYBER_GAMING_ESPORTS", "Nap gio net"), 100000, lateNight.plusMinutes(20), 4, true, "Giải trí", "COMPLETED", null);
                    }
                }
            }
        }
        System.out.println(" [COMPLETED] Data Seeder done");
    }

    private LocalDateTime getGaussianTime(LocalDate date, int peakHour, int peakMinute, int stdDevMinutes) {
        int baseMinutes = peakHour * 60 + peakMinute;
        int randomMinutes = (int) (random.nextGaussian() * stdDevMinutes);
        int finalMinutes = baseMinutes + randomMinutes;
        finalMinutes = Math.max(0, Math.min(1439, finalMinutes));
        return date.atTime(finalMinutes / 60, finalMinutes % 60, random.nextInt(59));
    }

    private String generateTextualChaos(String baseMerchant, String optionalNote) {
        StringBuilder note = new StringBuilder(baseMerchant);
        if (random.nextDouble() < 0.6) note.append("_TERM").append(1000 + random.nextInt(8999));
        if (random.nextDouble() < 0.3) {
            String[] gateways = {"_PAYOO", "_VNPAYQR", "_NAPAS", "_MOMO"};
            note.append(gateways[random.nextInt(gateways.length)]);
        }
        if (!optionalNote.isEmpty() && random.nextDouble() < 0.7) note.append(" - ").append(optionalNote);
        if (random.nextDouble() < 0.1) note.append("  ");
        return note.toString();
    }

    private double generateStrictLogNormal(double medianTarget) {
        double sigma = 0.50;
        double mu = Math.log(medianTarget);
        return Math.min(Math.exp(mu + random.nextGaussian() * sigma), medianTarget * 3.2);
    }

    private void insertRealTransaction(int userId, String note, double amount, LocalDateTime date, int categoryId, boolean isExpense, String categoryName, String status, String voucherCode) {
        long finalAmount = (amount < 100000) ? Math.round(amount / 1000.0) * 1000 : Math.round(amount / 500.0) * 500;
        if (finalAmount <= 0) finalAmount = 20000;

        String sql = "INSERT INTO transactions (user_id, note, amount, transaction_date, category_id, is_expense, category_name, status, voucher_code) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, userId, note, finalAmount, date, categoryId, isExpense, categoryName, status, voucherCode);
    }
}
