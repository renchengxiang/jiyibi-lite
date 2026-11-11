package com.yimu.lite;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 数据层：SQLite（金额一律以「分」存整数，避免浮点误差）
 * 表：account / category / bill / budget
 * 同时被界面与本地 MCP 服务复用。
 */
public class Db extends SQLiteOpenHelper {

    private static final String NAME = "yimu.db";
    private static final int VERSION = 1;

    private static Db instance;

    public static synchronized Db get(Context ctx) {
        if (instance == null) instance = new Db(ctx.getApplicationContext());
        return instance;
    }

    private Db(Context ctx) { super(ctx, NAME, null, VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE account(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'cash'," +
                "balance INTEGER NOT NULL DEFAULT 0," +
                "credit_limit INTEGER NOT NULL DEFAULT 0," +
                "billing_day INTEGER NOT NULL DEFAULT 0," +
                "created_at TEXT NOT NULL)");

        db.execSQL("CREATE TABLE category(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL, kind TEXT NOT NULL," +
                "parent_id INTEGER REFERENCES category(id)," +
                "icon TEXT NOT NULL DEFAULT '•'," +
                "sort INTEGER NOT NULL DEFAULT 0)");

        db.execSQL("CREATE TABLE bill(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "type TEXT NOT NULL, amount INTEGER NOT NULL," +
                "category_id INTEGER NOT NULL REFERENCES category(id)," +
                "account_id INTEGER REFERENCES account(id)," +
                "note TEXT NOT NULL DEFAULT ''," +
                "occurred_at TEXT NOT NULL, created_at TEXT NOT NULL)");
        db.execSQL("CREATE INDEX idx_bill_date ON bill(occurred_at DESC)");

        db.execSQL("CREATE TABLE budget(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "month TEXT NOT NULL, category_id INTEGER," +
                "amount INTEGER NOT NULL)");
        db.execSQL("CREATE UNIQUE INDEX idx_budget_uniq ON budget(month, IFNULL(category_id,0))");

        seed(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldV, int newV) { }

    // ---------------- 种子数据 ----------------
    private static final String[][] EXPENSE = {
            {"餐饮", "🍜", "早餐,午餐,晚餐,咖啡奶茶"},
            {"交通", "🚇", "地铁公交,打车,加油"},
            {"购物", "🛍️", "日用,服饰,数码"},
            {"居住", "🏠", "房租,水电燃气,物业"},
            {"娱乐", "🎮", "电影,会员订阅"},
            {"医疗", "💊", ""},
            {"人情", "🎁", ""},
            {"其他", "📦", ""},
    };
    private static final String[][] INCOME = {
            {"工资", "💼", ""},
            {"奖金", "🏆", ""},
            {"理财收益", "📈", ""},
            {"报销", "🧾", ""},
            {"其他收入", "💰", ""},
    };

    private void seed(SQLiteDatabase db) {
        String now = nowIso();
        // 暂时只留一个默认账户
        long defAcc = insertAccount(db, "现金钱包", "cash", 128600, 0, 0, now);

        for (String[] c : EXPENSE) {
            long pid = insertCategory(db, c[0], "expense", null, c[1]);
            if (!c[2].isEmpty()) {
                int i = 0;
                for (String sub : c[2].split(",")) insertCategory(db, sub, "expense", pid, iconFor(i++));
            }
        }
        for (String[] c : INCOME) insertCategory(db, c[0], "income", null, c[1]);

        // 示例账单（相对今天）
        addBillRaw(db, "expense", 2850, catId(db, "午餐"), defAcc, "楼下快餐", day(-0));
        addBillRaw(db, "expense", 1500, catId(db, "咖啡奶茶"), defAcc, "美式", day(-0));
        addBillRaw(db, "expense", 4200, catId(db, "打车"), defAcc, "加班回家", day(-1));
        addBillRaw(db, "expense", 19900, catId(db, "日用"), defAcc, "纸巾洗衣液", day(-2));
        addBillRaw(db, "expense", 380000, catId(db, "房租"), defAcc, "本月房租", day(-3));
        addBillRaw(db, "income", 1850000, catId(db, "工资"), defAcc, "本月工资", day(-3));
        addBillRaw(db, "expense", 6800, catId(db, "晚餐"), defAcc, "和朋友吃饭", day(-4));
        addBillRaw(db, "expense", 2500, catId(db, "地铁公交"), defAcc, "地铁充值", day(-5));

        ContentValues b = new ContentValues();
        b.put("month", today().substring(0, 7));
        b.putNull("category_id");
        b.put("amount", 800000);
        db.insert("budget", null, b);
    }

    private static String iconFor(int i) {
        String[] icons = {"🥣", "🍚", "🍲", "☕"};
        return icons[Math.min(i, icons.length - 1)];
    }

    private long insertAccount(SQLiteDatabase db, String name, String kind, int balance, int limit, int day, String now) {
        ContentValues v = new ContentValues();
        v.put("name", name); v.put("kind", kind); v.put("balance", balance);
        v.put("credit_limit", limit); v.put("billing_day", day); v.put("created_at", now);
        return db.insert("account", null, v);
    }

    private long insertCategory(SQLiteDatabase db, String name, String kind, Long parent, String icon) {
        ContentValues v = new ContentValues();
        v.put("name", name); v.put("kind", kind); v.put("icon", icon);
        if (parent != null) v.put("parent_id", parent);
        return db.insert("category", null, v);
    }

    private long catId(SQLiteDatabase db, String name) {
        try (Cursor c = db.rawQuery("SELECT id FROM category WHERE name=? ORDER BY id LIMIT 1", new String[]{name})) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    private void addBillRaw(SQLiteDatabase db, String type, int cents, long cat, long acc, String note, String date) {
        ContentValues v = new ContentValues();
        v.put("type", type); v.put("amount", cents); v.put("category_id", cat);
        if (acc > 0) v.put("account_id", acc);
        v.put("note", note); v.put("occurred_at", date); v.put("created_at", nowIso());
        db.insert("bill", null, v);
        if (acc > 0) db.execSQL("UPDATE account SET balance = balance + ? WHERE id = ?",
                new Object[]{type.equals("expense") ? -cents : cents, acc});
    }

    // ---------------- 日期工具 ----------------
    static String nowIso() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.CHINA).format(new Date());
    }

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
    }

    static String day(int offset) {
        long t = System.currentTimeMillis() + offset * 86_400_000L;
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date(t));
    }

    static String money(long cents) {
        return String.format(Locale.CHINA, "¥%,.2f", cents / 100.0);
    }

    static double yuan(long cents) { return Math.round(cents) / 100.0; }

    // ---------------- 查询：总览 ----------------
    public JSONObject overview() {
        SQLiteDatabase db = getReadableDatabase();
        String t = today();
        String m = t.substring(0, 7);
        JSONObject o = new JSONObject();
        try {
            JSONObject today = sums(db, "occurred_at = ?", new String[]{t});
            JSONObject month = sums(db, "substr(occurred_at,1,7) = ?", new String[]{m});
            int count = intQ(db, "SELECT COUNT(*) FROM bill WHERE substr(occurred_at,1,7)=?", new String[]{m});

            long budget = longQ(db, "SELECT amount FROM budget WHERE month=? AND category_id IS NULL", new String[]{m}, -1);
            long used = (long) (month.optDouble("expense", 0) * 100);
            long assets = longQ(db, "SELECT IFNULL(SUM(balance),0) FROM account", null, 0);
            long debt = longQ(db, "SELECT IFNULL(SUM(balance),0) FROM account WHERE kind='credit'", null, 0);

            o.put("date", t);
            o.put("today", today);
            JSONObject mo = new JSONObject(month.toString());
            mo.put("month", m);
            mo.put("count", count);
            o.put("month", mo);
            if (budget >= 0) {
                JSONObject b = new JSONObject();
                b.put("amount", yuan(budget));
                b.put("used", yuan(used));
                b.put("remaining", yuan(budget - used));
                o.put("budget", b);
            } else {
                o.put("budget", JSONObject.NULL);
            }
            JSONObject a = new JSONObject();
            a.put("total", yuan(assets));
            a.put("debt", yuan(debt));
            o.put("assets", a);
            o.put("currency", "CNY");
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return o;
    }

    private JSONObject sums(SQLiteDatabase db, String where, String[] args) throws JSONException {
        String sql = "SELECT " +
                "IFNULL(SUM(CASE WHEN type='expense' THEN amount END),0) AS e," +
                "IFNULL(SUM(CASE WHEN type='income' THEN amount END),0) AS i " +
                "FROM bill WHERE " + where;
        try (Cursor c = db.rawQuery(sql, args)) {
            c.moveToFirst();
            JSONObject o = new JSONObject();
            o.put("expense", yuan(c.getLong(0)));
            o.put("income", yuan(c.getLong(1)));
            return o;
        }
    }

    private int intQ(SQLiteDatabase db, String sql, String[] args) {
        try (Cursor c = db.rawQuery(sql, args)) { return c.moveToFirst() ? c.getInt(0) : 0; }
    }

    private long longQ(SQLiteDatabase db, String sql, String[] args, long def) {
        try (Cursor c = db.rawQuery(sql, args)) { return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : def; }
    }

    // ---------------- 查询：账单 ----------------
    public JSONObject queryBills(String from, String to, String type, String category,
                                 String keyword, int limit, int offset) {
        SQLiteDatabase db = getReadableDatabase();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        java.util.List<String> args = new java.util.ArrayList<>();
        if (from != null && !from.isEmpty()) { where.append(" AND b.occurred_at >= ?"); args.add(from); }
        if (to != null && !to.isEmpty()) { where.append(" AND b.occurred_at <= ?"); args.add(to); }
        if (type != null && !type.isEmpty()) { where.append(" AND b.type = ?"); args.add(type); }
        if (category != null && !category.isEmpty()) {
            where.append(" AND (c.name = ? OR pc.name = ?)");
            args.add(category); args.add(category);
        }
        if (keyword != null && !keyword.isEmpty()) {
            where.append(" AND (b.note LIKE ? OR c.name LIKE ?)");
            args.add("%" + keyword + "%"); args.add("%" + keyword + "%");
        }
        int lim = Math.min(Math.max(limit, 1), 100);
        int off = Math.max(offset, 0);

        JSONObject out = new JSONObject();
        JSONArray arr = new JSONArray();
        try {
            String base = " FROM bill b JOIN category c ON c.id=b.category_id" +
                    " LEFT JOIN category pc ON pc.id=c.parent_id" + where;
            try (Cursor c = db.rawQuery(
                    "SELECT b.id,b.type,b.amount,b.occurred_at,b.note,c.name,pc.name,c.icon,b.account_id" +
                            base + " ORDER BY b.occurred_at DESC, b.id DESC LIMIT ? OFFSET ?",
                    concat(args, String.valueOf(lim), String.valueOf(off)))) {
                while (c.moveToNext()) {
                    JSONObject b = new JSONObject();
                    b.put("id", c.getLong(0));
                    b.put("type", c.getString(1));
                    b.put("amount", yuan(c.getLong(2)));
                    b.put("occurred_at", c.getString(3));
                    b.put("note", c.getString(4));
                    b.put("category", c.getString(5));
                    b.put("parent_category", c.isNull(6) ? "" : c.getString(6));
                    b.put("icon", c.getString(7));
                    b.put("account_id", c.isNull(8) ? JSONObject.NULL : c.getLong(8));
                    arr.put(b);
                }
            }
            long matched = longQ(db, "SELECT COUNT(*)" + base, args.toArray(new String[0]), 0);
            JSONObject sum = sumsJoined(db, base, args.toArray(new String[0]));

            out.put("returned_count", arr.length());
            out.put("matched_count", matched);
            out.put("matched_expense", sum.optDouble("expense", 0));
            out.put("matched_income", sum.optDouble("income", 0));
            out.put("truncated", off + arr.length() < matched);
            out.put("next_offset", off + arr.length() < matched ? off + arr.length() : JSONObject.NULL);
            out.put("bills", arr);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    private JSONObject sumsJoined(SQLiteDatabase db, String base, String[] args) throws JSONException {
        try (Cursor c = db.rawQuery("SELECT " +
                "IFNULL(SUM(CASE WHEN b.type='expense' THEN b.amount END),0)," +
                "IFNULL(SUM(CASE WHEN b.type='income' THEN b.amount END),0)" + base, args)) {
            c.moveToFirst();
            JSONObject o = new JSONObject();
            o.put("expense", yuan(c.getLong(0)));
            o.put("income", yuan(c.getLong(1)));
            return o;
        }
    }

    private static String[] concat(java.util.List<String> a, String... extra) {
        String[] out = new String[a.size() + extra.length];
        for (int i = 0; i < a.size(); i++) out[i] = a.get(i);
        System.arraycopy(extra, 0, out, a.size(), extra.length);
        return out;
    }

    // ---------------- 写入：账单 ----------------
    public JSONObject addBill(String type, double amount, String category, String account,
                              String note, String date) {
        if (!(amount > 0)) throw new IllegalArgumentException("amount 必须为正数");
        if (!type.equals("expense") && !type.equals("income"))
            throw new IllegalArgumentException("type 必须是 expense 或 income");
        if (category == null || category.isEmpty())
            throw new IllegalArgumentException("缺少 category");

        SQLiteDatabase db = getWritableDatabase();
        String catName;
        long cat;
        String catKind;
        try (Cursor c = db.rawQuery("SELECT id,name,kind FROM category WHERE name=? LIMIT 1", new String[]{category})) {
            if (!c.moveToFirst())
                throw new IllegalArgumentException("分类「" + category + "」不存在，可先调用 list_categories 查看可用分类");
            cat = c.getLong(0);
            catName = c.getString(1);
            catKind = c.getString(2);
        }
        if (!catKind.equals(type))
            throw new IllegalArgumentException("分类「" + category + "」属于" +
                    (catKind.equals("expense") ? "支出" : "收入") + "，与 type 不符");

        long acc = 0;
        String accName = null;
        if (account != null && !account.isEmpty()) {
            try (Cursor c = db.rawQuery("SELECT id,name FROM account WHERE name=? LIMIT 1", new String[]{account})) {
                if (!c.moveToFirst())
                    throw new IllegalArgumentException("账户「" + account + "」不存在，可先调用 list_accounts 查看账户");
                acc = c.getLong(0);
                accName = c.getString(1);
            }
        }
        String when = (date == null || date.isEmpty()) ? today() : date;
        if (!when.matches("\\d{4}-\\d{2}-\\d{2}"))
            throw new IllegalArgumentException("date 格式应为 yyyy-MM-dd");

        long cents = Math.round(amount * 100);
        ContentValues v = new ContentValues();
        v.put("type", type); v.put("amount", cents); v.put("category_id", cat);
        if (acc > 0) v.put("account_id", acc);
        v.put("note", note == null ? "" : note);
        v.put("occurred_at", when);
        v.put("created_at", nowIso());
        long id = db.insert("bill", null, v);
        if (acc > 0) db.execSQL("UPDATE account SET balance = balance + ? WHERE id = ?",
                new Object[]{type.equals("expense") ? -cents : cents, acc});

        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("type", type);
            o.put("amount", yuan(cents));
            o.put("category", catName);
            o.put("account", accName == null ? "" : accName);
            o.put("note", note == null ? "" : note);
            o.put("date", when);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return o;
    }

    public void deleteBill(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor c = db.rawQuery("SELECT type,amount,account_id FROM bill WHERE id=?",
                    new String[]{String.valueOf(id)})) {
                if (c.moveToFirst() && !c.isNull(2)) {
                    long acc = c.getLong(2);
                    String type = c.getString(0);
                    long amount = c.getLong(1);
                    long delta = type.equals("expense") ? amount : -amount;
                    db.execSQL("UPDATE account SET balance = balance + ? WHERE id = ?",
                            new Object[]{delta, acc});
                }
            }
            db.delete("bill", "id=?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 单笔账单完整信息（含 id），供点开详情与编辑表单回填。
     * amount 以「元」返回；account_id 为 null 表示挂在默认账户上。
     */
    public JSONObject getBill(long id) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT b.id,b.type,b.amount,b.note,b.occurred_at," +
                        "c.name,pc.name,c.icon,b.account_id" +
                        " FROM bill b JOIN category c ON c.id=b.category_id" +
                        " LEFT JOIN category pc ON pc.id=c.parent_id" +
                        " WHERE b.id=?", new String[]{String.valueOf(id)})) {
            if (!c.moveToFirst()) return null;
            JSONObject o = new JSONObject();
            o.put("id", c.getLong(0));
            o.put("type", c.getString(1));
            o.put("amount", yuan(c.getLong(2)));
            o.put("note", c.getString(3));
            o.put("date", c.getString(4));
            o.put("category", c.getString(5));
            o.put("parent_category", c.isNull(6) ? "" : c.getString(6));
            o.put("icon", c.getString(7));
            o.put("account_id", c.isNull(8) ? JSONObject.NULL : c.getLong(8));
            return o;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 编辑一笔已有账单：重算账户余额（先回退旧账，再应用新账，两步在同一事务里）。
     * @param accountName 传 null/空 表示不关联账户（余额不动的纯记录）
     */
    public void updateBill(long id, String type, double amount, String category,
                           String accountName, String note, String date) {
        if (!(amount > 0)) throw new IllegalArgumentException("amount 必须为正数");
        if (!type.equals("expense") && !type.equals("income"))
            throw new IllegalArgumentException("type 必须是 expense 或 income");
        if (category == null || category.isEmpty())
            throw new IllegalArgumentException("缺少 category");
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}"))
            throw new IllegalArgumentException("date 格式应为 yyyy-MM-dd");

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long accOld = 0;
            String typeOld = null;
            long centsOld = 0;
            try (Cursor c = db.rawQuery("SELECT type,amount,account_id FROM bill WHERE id=?",
                    new String[]{String.valueOf(id)})) {
                if (!c.moveToFirst()) throw new IllegalArgumentException("账单不存在：id=" + id);
                typeOld = c.getString(0);
                centsOld = c.getLong(1);
                accOld = c.isNull(2) ? 0 : c.getLong(2);
            }

            // 账户按名字解析（内部统一走默认账户「现金钱包」）
            long accNew = 0;
            if (accountName != null && !accountName.isEmpty()) {
                try (Cursor c = db.rawQuery("SELECT id FROM account WHERE name=? LIMIT 1",
                        new String[]{accountName})) {
                    if (!c.moveToFirst())
                        throw new IllegalArgumentException("账户「" + accountName + "」不存在");
                    accNew = c.getLong(0);
                }
            }

            long cat;
            try (Cursor c = db.rawQuery("SELECT id FROM category WHERE name=? LIMIT 1",
                    new String[]{category})) {
                if (!c.moveToFirst())
                    throw new IllegalArgumentException("分类「" + category + "」不存在");
                cat = c.getLong(0);
            }

            long cents = Math.round(amount * 100);

            // 1) 回退旧账对余额的影响
            if (accOld > 0) {
                long delta = typeOld.equals("expense") ? centsOld : -centsOld;
                db.execSQL("UPDATE account SET balance = balance + ? WHERE id = ?",
                        new Object[]{delta, accOld});
            }
            // 2) 应用新账
            if (accNew > 0) {
                long delta = type.equals("expense") ? -cents : cents;
                db.execSQL("UPDATE account SET balance = balance + ? WHERE id = ?",
                        new Object[]{delta, accNew});
            }

            ContentValues v = new ContentValues();
            v.put("type", type);
            v.put("amount", cents);
            v.put("category_id", cat);
            if (accNew > 0) v.put("account_id", accNew); else v.putNull("account_id");
            v.put("note", note == null ? "" : note);
            v.put("occurred_at", date);
            int n = db.update("bill", v, "id=?", new String[]{String.valueOf(id)});
            if (n != 1) throw new IllegalStateException("更新失败：id=" + id);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ---------------- 账户 / 分类 / 统计 ----------------
    public JSONArray accounts() {
        SQLiteDatabase db = getReadableDatabase();
        JSONArray arr = new JSONArray();
        try (Cursor c = db.rawQuery(
                "SELECT id,name,kind,balance FROM account ORDER BY id LIMIT 1", null)) {
            while (c.moveToNext()) {
                JSONObject a = new JSONObject();
                a.put("id", c.getLong(0));
                a.put("name", c.getString(1));
                a.put("kind", c.getString(2));
                a.put("balance", yuan(c.getLong(3)));
                arr.put(a);
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return arr;
    }

    /**
     * 日 / 月 / 年三档汇总，统一结构：
     * { level, current: {key, expense, income, count}, groups: [{key, expense, income, count}] }
     * - day   ：本月每天的收支
     * - month ：本年每个月的收支
     * - year  ：全部年份的收支
     */
    public JSONObject periodsSummary(String level) {
        SQLiteDatabase db = getReadableDatabase();
        boolean isDay = "day".equals(level);
        boolean isYear = "year".equals(level);
        if (!isDay && !isYear && !"month".equals(level))
            throw new IllegalArgumentException("level 必须是 day / month / year");

        // 截取键长：day→10（yyyy-MM-dd） month→7（yyyy-MM） year→4（yyyy）
        int len = isDay ? 10 : (isYear ? 4 : 7);
        String today = today();
        String currentKey = today.substring(0, len);

        String sql = "SELECT substr(occurred_at,1," + len + ") AS k," +
                "IFNULL(SUM(CASE WHEN type='expense' THEN amount END),0)," +
                "IFNULL(SUM(CASE WHEN type='income' THEN amount END),0)," +
                "COUNT(*) FROM bill GROUP BY k ORDER BY k DESC";
        JSONArray groups = new JSONArray();
        JSONObject current = null;
        try {
            try (Cursor c = db.rawQuery(sql, null)) {
                while (c.moveToNext()) {
                    JSONObject g = new JSONObject();
                    String key = c.getString(0);
                    g.put("key", key);
                    g.put("expense", yuan(c.getLong(1)));
                    g.put("income", yuan(c.getLong(2)));
                    g.put("count", c.getInt(3));
                    if (key.equals(currentKey)) current = new JSONObject(g.toString());
                    groups.put(g);
                }
            }
            if (current == null) {
                current = new JSONObject();
                current.put("key", currentKey);
                current.put("expense", 0d);
                current.put("income", 0d);
                current.put("count", 0);
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }

        JSONObject out = new JSONObject();
        try {
            out.put("level", level);
            out.put("current", current);
            out.put("groups", groups);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    // ---------------- 预算 / 账户 设置 ----------------

    /** 读本月总预算（分），没有预算行时返回 0 */
    public long budgetCents() {
        return longQ(getReadableDatabase(),
                "SELECT amount FROM budget WHERE month=? AND category_id IS NULL",
                new String[]{today().substring(0, 7)}, 0);
    }

    /** 设置本月总预算（元）。0 或负数表示删除本月预算（首页就不显示预算） */
    public void setBudget(double yuan) {
        SQLiteDatabase db = getWritableDatabase();
        String m = today().substring(0, 7);
        if (!(yuan > 0)) {
            db.delete("budget", "month=? AND category_id IS NULL", new String[]{m});
            return;
        }
        long cents = Math.round(yuan * 100);
        ContentValues v = new ContentValues();
        v.put("month", m);
        v.putNull("category_id");
        v.put("amount", cents);
        db.insertWithOnConflict("budget", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 更新默认账户的名称和余额（分）。余额按输入值直接覆盖。 */
    public void updateDefaultAccount(String name, double balanceYuan) {
        if (name == null || name.trim().isEmpty())
            throw new IllegalArgumentException("账户名不能为空");
        JSONArray arr = accounts();
        if (arr.length() == 0) throw new IllegalStateException("没有默认账户");
        long id = arr.optJSONObject(0).optLong("id");
        ContentValues v = new ContentValues();
        v.put("name", name.trim());
        v.put("balance", Math.round(balanceYuan * 100));
        getWritableDatabase().update("account", v, "id=?", new String[]{String.valueOf(id)});
    }

    // ---------------- CSV 导出 ----------------

    /**
     * 全量账单（按时间正序，便于 Excel 阅读），CSV 导出专用。
     * 每项字段：date,type,amount,category,parent_category,note
     */
    public JSONArray exportRows() {
        SQLiteDatabase db = getReadableDatabase();
        JSONArray arr = new JSONArray();
        String sql = "SELECT b.occurred_at,b.type,b.amount,c.name,pc.name,b.note" +
                " FROM bill b JOIN category c ON c.id=b.category_id" +
                " LEFT JOIN category pc ON pc.id=c.parent_id" +
                " ORDER BY b.occurred_at ASC, b.id ASC";
        try (Cursor c = db.rawQuery(sql, null)) {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("date", c.getString(0));
                o.put("type", c.getString(1));
                o.put("amount", yuan(c.getLong(2)));
                o.put("category", c.getString(3));
                o.put("parent_category", c.isNull(4) ? "" : c.getString(4));
                o.put("note", c.getString(5) == null ? "" : c.getString(5));
                arr.put(o);
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return arr;
    }

    public JSONObject categories() {
        SQLiteDatabase db = getReadableDatabase();
        JSONObject out = new JSONObject();
        try {
            out.put("expense", categoryTree(db, "expense"));
            out.put("income", categoryTree(db, "income"));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    private JSONArray categoryTree(SQLiteDatabase db, String kind) throws JSONException {
        JSONArray arr = new JSONArray();
        try (Cursor p = db.rawQuery(
                "SELECT id,name,icon FROM category WHERE kind=? AND parent_id IS NULL ORDER BY sort,id",
                new String[]{kind})) {
            while (p.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("name", p.getString(1));
                o.put("icon", p.getString(2));
                JSONArray subs = new JSONArray();
                try (Cursor s = db.rawQuery(
                        "SELECT name,icon FROM category WHERE parent_id=? ORDER BY sort,id",
                        new String[]{String.valueOf(p.getLong(0))})) {
                    while (s.moveToNext()) {
                        JSONObject so = new JSONObject();
                        so.put("name", s.getString(0));
                        so.put("icon", s.getString(1));
                        subs.put(so);
                    }
                }
                o.put("sub_categories", subs);
                arr.put(o);
            }
        }
        return arr;
    }

    public JSONObject categoryStats(String month) {
        SQLiteDatabase db = getReadableDatabase();
        String m = (month == null || month.isEmpty()) ? today().substring(0, 7) : month;
        JSONObject out = new JSONObject();
        JSONArray arr = new JSONArray();
        long total = 0;
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT IFNULL(pc.name,c.name) AS cat, SUM(b.amount) AS amt" +
                        " FROM bill b JOIN category c ON c.id=b.category_id" +
                        " LEFT JOIN category pc ON pc.id=c.parent_id" +
                        " WHERE b.type='expense' AND substr(b.occurred_at,1,7)=?" +
                        " GROUP BY cat ORDER BY amt DESC", new String[]{m})) {
            while (c.moveToNext()) {
                rows.add(new String[]{c.getString(0), String.valueOf(c.getLong(1))});
                total += c.getLong(1);
            }
        }
        try {
            for (String[] r : rows) {
                long amt = Long.parseLong(r[1]);
                JSONObject o = new JSONObject();
                o.put("category", r[0]);
                o.put("amount", yuan(amt));
                o.put("percent", total == 0 ? 0 : Math.round(amt * 1000.0 / total) / 10.0);
                arr.put(o);
            }
            out.put("month", m);
            out.put("total", yuan(total));
            out.put("categories", arr);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return out;
    }
}
