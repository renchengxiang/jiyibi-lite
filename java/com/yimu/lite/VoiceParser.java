package com.yimu.lite;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中文口语记账解析器（纯 Java，无 Android 依赖，便于在电脑上单测）。
 * 输入：一句口语，如「买菜花了三十块五」
 * 输出：金额（元）、收支类型、分类、备注
 *
 * 支持：阿拉伯数字、中文数字（含十百千万、两）、块/元/圆/毛/角/分/点，
 *      以及「三十块五」这类省略「毛」的说法。
 */
public final class VoiceParser {

    public static final class Result {
        public final String type;      // expense / income
        public final double amount;    // 元
        public final String category;  // 分类名
        public final String note;      // 原始文本
        public final String raw;

        Result(String type, double amount, String category, String note, String raw) {
            this.type = type;
            this.amount = amount;
            this.category = category;
            this.note = note;
            this.raw = raw;
        }

        public boolean ok() { return amount > 0; }

        @Override public String toString() {
            return "Result{type=" + type + ", amount=" + amount + ", category=" + category + "}";
        }
    }

    private static final String CN_DIGITS = "零一二三四五六七八九";
    private static final Pattern CN_RUN = Pattern.compile("[零一二三四五六七八九十百千万亿两点两]+");

    private VoiceParser() { }

    public static Result parse(String input) {
        if (input == null) input = "";
        String text = input.trim();
        text = fixHotwords(text);
        String normalized = normalizeNumbers(text);

        double amount = extractAmount(normalized);
        String type = isIncome(text) ? "income" : "expense";
        String category = detectCategory(text, type);

        String note = text.length() > 24 ? text.substring(0, 24) : text;
        return new Result(type, amount, category, note, text);
    }

    /**
     * 热词纠错：小模型（vosk-model-small-cn-0.22）的高频同音/近音错字映射。
     * 重点覆盖两类：
     *  1) 金额量词被听错（块→会/快…，毛/角→搅/猫…），丢了就解析不出金额
     *  2) 常见记账词被听错（打车、地铁、房租、工资…），丢了就分不了类
     * 只做「整词替换」，不动数字。
     */
    static String fixHotwords(String text) {
        if (text == null || text.isEmpty()) return text;
        String[][] REPLACES = {
                // 金额量词：小模型常见把「块」识别成 会/快/愧，把「元」识别成 园/员
                // 优先长模式：「数字+会+数字」=「数字块五（角）」；单独的「会」才换成「块」
                {"会五", "块五"}, {"会六", "块六"}, {"会七", "块七"},
                {"会八", "块八"}, {"会九", "块九"}, {"会了", "块五"},
                {"快五", "块五"}, {"快六", "块六"}, {"快七", "块七"},
                {"快八", "块八"}, {"快九", "块九"}, {"快了", "块五"},
                {"愧五", "块五"}, {"愧了", "块五"},
                {"筷子", "块五"},
                // 「五」被听成「我/屋/无」：只在量词后纠正，避免误改正文
                {"块我", "块五"}, {"块屋", "块五"}, {"块无", "块五"},
                {"会我", "块五"}, {"会屋", "块五"}, {"会无", "块五"},
                {"毛我", "毛五"}, {"角我", "角五"},
                {"会", "块"}, {"快", "块"}, {"愧", "块"},
                {"园", "元"}, {"员", "元"},
                // 角/毛：搅/猫/冒
                {"搅", "角"}, {"猫", "毛"}, {"冒五", "毛五"},
                // 常见分类词错字（含「地 铁」「打 车」这类中间被插入噪声字的情况）
                {"播放", "午饭"},
                {"搭车", "打车"}, {"地 铁", "地铁"}, {"公 组", "房租"},
                {"工 子", "工资"}, {"红 包", "红包"}, {"挂 号", "挂号"},
                {"早 泛", "早饭"}, {"午 泛", "午饭"}, {"晚 泛", "晚饭"},
                {"泛 馆", "饭馆"}, {"医 院", "医院"}, {"停 车", "停车"},
                {"电 影", "电影"}, {"门 票", "门票"}, {"快 递", "快递"},
        };
        for (String[] r : REPLACES) {
            if (text.contains(r[0])) text = text.replace(r[0], r[1]);
        }
        return text;
    }

    // ---------------- 数字归一化 ----------------
    static String normalizeNumbers(String text) {
        Matcher m = CN_RUN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String run = m.group();
            String digits = chineseToArabic(run);
            m.appendReplacement(sb, Matcher.quoteReplacement(digits));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 把一段中文数字（可能含「点」小数）转成阿拉伯数字字符串 */
    static String chineseToArabic(String cn) {
        int dot = cn.indexOf('点');
        if (dot >= 0) {
            String intPart = cn.substring(0, dot);
            String decPart = cn.substring(dot + 1);
            String i = String.valueOf(parseInteger(intPart));
            StringBuilder d = new StringBuilder();
            for (char c : decPart.toCharArray()) {
                int v = digitValue(c);
                if (v >= 0) d.append(v);
            }
            return d.length() == 0 ? i : i + "." + d;
        }
        return String.valueOf(parseInteger(cn));
    }

    private static int digitValue(char c) {
        int i = CN_DIGITS.indexOf(c);
        if (i >= 0) return i;
        if (c == '两') return 2;
        return -1;
    }

    private static long parseInteger(String cn) {
        if (cn.isEmpty()) return 0;
        boolean hasUnit = cn.matches(".*[十百千万亿].*");
        if (!hasUnit) {
            StringBuilder sb = new StringBuilder();
            for (char c : cn.toCharArray()) {
                int v = digitValue(c);
                if (v >= 0) sb.append(v);
            }
            return sb.length() == 0 ? 0 : Long.parseLong(sb.toString());
        }
        long total = 0, section = 0, number = 0, lastUnit = 0;
        for (char c : cn.toCharArray()) {
            int d = digitValue(c);
            if (c == '零') { continue; }
            if (d >= 0) { number = d; continue; }
            switch (c) {
                case '十': section += (number == 0 ? 1 : number) * 10; number = 0; lastUnit = 10; break;
                case '百': section += (number == 0 ? 1 : number) * 100; number = 0; lastUnit = 100; break;
                case '千': section += (number == 0 ? 1 : number) * 1000; number = 0; lastUnit = 1000; break;
                case '万':
                    total += (section + number) * 10000; section = 0; number = 0; lastUnit = 10000; break;
                case '亿':
                    total = (total + section + number) * 100000000L; section = 0; number = 0; lastUnit = 100000000L; break;
                default: break;
            }
        }
        // 「三千八」=3800、「一万二」=12000：省略了下一级单位
        if (number > 0 && lastUnit >= 100) {
            section += number * (lastUnit / 10);
            number = 0;
        }
        return total + section + number;
    }

    // ---------------- 金额 ----------------
    // 例：30块5 / 30.5元 / 30块5毛 / 5毛 / 128元整
    private static final Pattern P_YUAN_JIAO = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(?:块|元|圆)\\s*(\\d)?\\s*(?:毛|角)?");
    // 「128角6」：百位数以上带「角」是口语「一百二十八块角六」的省略，角位只有一位数字
    private static final Pattern P_MULTI_JIAO = Pattern.compile(
            "(\\d{2,})\\s*(?:毛|角)\\s*(\\d)?");
    private static final Pattern P_JIAO_ONLY = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(?:毛|角)");
    private static final Pattern P_FEN_ONLY = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*分");
    private static final Pattern P_ANY_NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    static double extractAmount(String normalized) {
        Matcher m = P_YUAN_JIAO.matcher(normalized);
        if (m.find()) {
            double yuan = Double.parseDouble(m.group(1));
            String jiao = m.group(2);
            if (jiao != null) {
                // 「30块5」中的 5 是角；若原文已写「30.5」则不重复加
                if (!m.group(1).contains(".")) return yuan + Integer.parseInt(jiao) / 10.0;
            }
            return yuan;
        }
        m = P_MULTI_JIAO.matcher(normalized);
        if (m.find()) {
            double yuan = Double.parseDouble(m.group(1));
            String jiao = m.group(2);
            return jiao == null ? yuan : yuan + Integer.parseInt(jiao) / 10.0;
        }
        m = P_JIAO_ONLY.matcher(normalized);
        if (m.find()) return Double.parseDouble(m.group(1)) / 10.0;

        m = P_FEN_ONLY.matcher(normalized);
        if (m.find()) return Double.parseDouble(m.group(1)) / 100.0;

        m = P_ANY_NUMBER.matcher(normalized);
        if (m.find()) return Double.parseDouble(m.group(1));
        return 0;
    }

    // ---------------- 收支类型 ----------------
    private static final String[] INCOME_WORDS = {
            "收到", "收入", "工资", "奖金", "报销", "退款", "退钱", "进账",
            "入账", "理财收益", "利息", "分红", "赚了", "补贴", "发了"
    };

    static boolean isIncome(String text) {
        for (String w : INCOME_WORDS) if (text.contains(w)) return true;
        return false;
    }

    // ---------------- 分类 ----------------
    private static final String[][] EXPENSE_RULES = {
            // 顺序即优先级
            {"餐饮", "饭,吃,餐,菜,面,米,粥,包子,馒头,外卖,咖啡,奶茶,水果,零食,早点,早餐,午餐,晚餐,夜宵,食堂,火锅,烧烤,喝"},
            {"交通", "打车,出租,滴滴,公交,地铁,加油,停车,高铁,火车,机票,车费,油费,过路费,充电桩,车,摩托"},
            {"居住", "房租,水电,燃气,物业,取暖,宽带,维修,装修"},
            {"医疗", "药,医院,看病,挂号,门诊,体检,诊所,住院,牙"},
            {"购物", "衣服,鞋,裤,超市,日用,纸巾,洗发,洗衣,淘宝,京东,拼多多,快递,买"},
            {"娱乐", "电影,游戏,会员,视频,旅游,门票,唱歌,按摩"},
            {"人情", "红包,礼,份子,随礼,请客,送礼"},
    };

    private static final String[][] INCOME_RULES = {
            {"工资", "工资,薪水,发工资"},
            {"奖金", "奖金,年终奖,红包收,奖励"},
            {"理财收益", "理财,利息,分红,收益,基金,股票"},
            {"报销", "报销,退款,退钱"},
    };

    static String detectCategory(String text, String type) {
        if ("income".equals(type)) {
            for (String[] r : INCOME_RULES)
                for (String k : r[1].split(","))
                    if (text.contains(k)) return r[0];
            return "其他收入";
        }
        for (String[] r : EXPENSE_RULES)
            for (String k : r[1].split(","))
                if (text.contains(k)) return r[0];
        return "其他";
    }
}
