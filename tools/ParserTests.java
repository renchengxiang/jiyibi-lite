import com.yimu.lite.VoiceParser;

/**
 * 纯 Java 断言测试（无 JUnit 依赖，CI 友好）。
 * 运行：javac + java，任何失败以非零退出码结束。
 */
public class ParserTests {

    private static int passed = 0, failed = 0;

    private static void eq(String name, double expected, String input) {
        double got = VoiceParser.parse(input).amount;
        if (Math.abs(got - expected) < 0.005) { passed++; }
        else { failed++; System.out.println("FAIL [金额] " + name + "  input=" + input
                + "  expected=" + expected + "  got=" + got); }
    }

    private static void eq(String name, String expectedCat, String input) {
        String got = VoiceParser.parse(input).category;
        if (expectedCat.equals(got)) { passed++; }
        else { failed++; System.out.println("FAIL [分类] " + name + "  input=" + input
                + "  expected=" + expectedCat + "  got=" + got); }
    }

    private static void eqType(String name, String expectedType, String input) {
        String got = VoiceParser.parse(input).type;
        if (expectedType.equals(got)) { passed++; }
        else { failed++; System.out.println("FAIL [类型] " + name + "  input=" + input
                + "  expected=" + expectedType + "  got=" + got); }
    }

    public static void main(String[] args) {
        // ---- 基础金额：阿拉伯数字 ----
        eq("小数元", 25.50, "花了25.5元");
        eq("块角", 12.30, "12块3");
        eq("纯数字", 66.00, "66");

        // ---- 中文数字 ----
        eq("三十块五", 30.50, "买菜花了三十块五");
        eq("三十五", 35.00, "打车三十五");
        eq("五千", 5000.00, "收到工资五千");
        eq("省略三千八", 3800.00, "交房租三千八");
        eq("一万二", 12000.00, "发工资一万二");
        eq("二十块零五", 20.50, "医院挂号二十块零五");
        eq("一百二十八块六", 128.60, "买药一百二十八块六");
        eq("三百", 300.00, "给我报销三百");

        // ---- 收支类型 ----
        eqType("收入-工资", "income", "收到工资五千");
        eqType("支出-买菜", "expense", "买菜花了三十块五");
        eqType("收入-报销", "income", "给我报销三百");
        eqType("收入-发工资", "income", "发工资一万二");

        // ---- 分类 ----
        eq("餐饮", "餐饮", "买菜花了三十块五");
        eq("交通-打车", "交通", "打车三十五");
        eq("医疗", "医疗", "买药一百二十八块六");
        eq("居住", "居住", "交房租三千八");
        eq("工资", "工资", "收到工资五千");
        eq("报销", "报销", "给我报销三百");

        // ---- 热词纠错：金额量词同音错字（小模型高频错） ----
        eq("会→块+角", 30.50, "买菜花了三十会五");
        eq("会→块", 35.00, "打车三十五会");
        eq("快→块", 30.00, "买菜话了三十快");
        eq("搅→角", 128.60, "买药一百二十八搅六");

        // ---- 热词纠错：分类词错字 ----
        eq("搭车→打车", "交通", "搭车二十五");
        eq("医院", "医疗", "医院挂号二十块零五");
        eq("地铁", "交通", "地铁充值二十五");

        // ---- 边界：无金额 ----
        double none = VoiceParser.parse("今天天气不错").amount;
        if (none == 0) passed++; else { failed++; System.out.println("FAIL [无金额] got=" + none); }

        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }
}
