package com.yimu.lite;

import android.app.Activity;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 汇总页：日/月/年三段汇总 + 本月分类占比 + 账户设置 + 本月预算。
 */
public class SummaryPage {

    private final Activity a;
    private final PageContract nav;
    private final Db db;

    private TextView segDay, segMonth, segYear, sumCurrent;
    private LinearLayout sumBox;
    private String sumLevel = "month";

    private TextView statsTotal;
    private LinearLayout statsBox;

    private EditText etBudget, etAccName, etAccBalance;
    private TextView budgetMsg, accMsg, exportMsg;

    public SummaryPage(Activity a, PageContract nav, Db db) {
        this.a = a;
        this.nav = nav;
        this.db = db;
    }

    public View build() {
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        LinearLayout box = Ui.column(a);
        sv.addView(box);

        // ---- 日 / 月 / 年 ----
        LinearLayout card = Ui.card(a, Ui.CARD);
        card.setPadding(Ui.dp(a, 18), Ui.dp(a, 14), Ui.dp(a, 18), Ui.dp(a, 14));

        LinearLayout seg = new LinearLayout(a);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        segDay = Ui.segItem(a, "日", false);
        segMonth = Ui.segItem(a, "月", true);
        segYear = Ui.segItem(a, "年", false);
        segDay.setOnClickListener(v -> setLevel("day"));
        segMonth.setOnClickListener(v -> setLevel("month"));
        segYear.setOnClickListener(v -> setLevel("year"));
        seg.addView(segDay, Ui.lp(0, 1f, Ui.dp(a, 8)));
        seg.addView(segMonth, Ui.lp(0, 1f, Ui.dp(a, 8)));
        seg.addView(segYear, Ui.lp(0, 1f, 0));
        card.addView(seg);
        card.addView(Ui.space(a, 14));

        sumCurrent = Ui.tv(a, "", 15, Ui.INK2, false);
        sumCurrent.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 10));
        card.addView(sumCurrent);

        sumBox = Ui.column(a);
        card.addView(sumBox);
        box.addView(card);

        box.addView(Ui.space(a, 14));

        // ---- 本月分类占比 ----
        LinearLayout catCard = Ui.card(a, Ui.CARD);
        catCard.setPadding(Ui.dp(a, 18), Ui.dp(a, 14), Ui.dp(a, 18), Ui.dp(a, 14));
        catCard.addView(Ui.tv(a, "本月分类占比", 18, Ui.INK, true));
        statsTotal = Ui.tv(a, "", 15, Ui.INK2, false);
        statsTotal.setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 12));
        catCard.addView(statsTotal);
        statsBox = Ui.column(a);
        catCard.addView(statsBox);
        box.addView(catCard);

        box.addView(Ui.space(a, 14));

        // ---- 账户设置 ----
        LinearLayout accCard = Ui.card(a, Ui.CARD);
        accCard.setPadding(Ui.dp(a, 18), Ui.dp(a, 14), Ui.dp(a, 18), Ui.dp(a, 14));
        accCard.addView(Ui.tv(a, "账户设置", 18, Ui.INK, true));
        accCard.addView(Ui.space(a, 4));
        accCard.addView(Ui.tv(a, "改名和改余额，余额按这里填的数字直接覆盖", 14, Ui.INK2, false));
        accCard.addView(Ui.space(a, 10));

        LinearLayout accNameRow = new LinearLayout(a);
        accNameRow.setOrientation(LinearLayout.HORIZONTAL);
        accNameRow.setGravity(Gravity.CENTER_VERTICAL);
        accNameRow.addView(Ui.tv(a, "名称", 16, Ui.INK2, false));
        etAccName = new EditText(a);
        etAccName.setTextSize(18);
        etAccName.setTextColor(Ui.INK);
        etAccName.setSingleLine(true);
        etAccName.setBackground(null);
        LinearLayout.LayoutParams anLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        anLp.leftMargin = Ui.dp(a, 12);
        accNameRow.addView(etAccName, anLp);
        accCard.addView(accNameRow);
        accCard.addView(Ui.space(a, 8));

        LinearLayout accBalRow = new LinearLayout(a);
        accBalRow.setOrientation(LinearLayout.HORIZONTAL);
        accBalRow.setGravity(Gravity.CENTER_VERTICAL);
        accBalRow.addView(Ui.tv(a, "余额", 16, Ui.INK2, false));
        etAccBalance = new EditText(a);
        etAccBalance.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etAccBalance.setTextSize(22);
        etAccBalance.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        etAccBalance.setTextColor(Ui.INK);
        etAccBalance.setSingleLine(true);
        etAccBalance.setBackground(null);
        LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        abLp.leftMargin = Ui.dp(a, 12);
        accBalRow.addView(etAccBalance, abLp);
        accCard.addView(accBalRow);
        accCard.addView(Ui.space(a, 12));

        TextView accSave = Ui.bigAction(a, "保存账户", Ui.INK, Color.WHITE);
        accSave.setOnClickListener(v -> saveAccount());
        accCard.addView(accSave, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 52)));
        accMsg = Ui.tv(a, "", 15, Ui.INK2, false);
        accMsg.setPadding(0, Ui.dp(a, 8), 0, 0);
        accMsg.setGravity(Gravity.CENTER);
        accCard.addView(accMsg);
        box.addView(accCard);

        box.addView(Ui.space(a, 14));

        // ---- 本月预算 ----
        LinearLayout budgetCard = Ui.card(a, Ui.CARD);
        budgetCard.setPadding(Ui.dp(a, 18), Ui.dp(a, 14), Ui.dp(a, 18), Ui.dp(a, 14));
        budgetCard.addView(Ui.tv(a, "本月预算", 18, Ui.INK, true));
        budgetCard.addView(Ui.space(a, 4));
        budgetCard.addView(Ui.tv(a, "填 0 或清空表示不设预算（首页就不显示预算）", 14, Ui.INK2, false));
        budgetCard.addView(Ui.space(a, 10));

        etBudget = new EditText(a);
        etBudget.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etBudget.setTextSize(28);
        etBudget.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        etBudget.setTextColor(Ui.INK);
        etBudget.setHint("0.00");
        etBudget.setBackground(null);
        budgetCard.addView(etBudget);
        budgetCard.addView(Ui.space(a, 12));

        TextView budgetSave = Ui.bigAction(a, "保存预算", Ui.INK, Color.WHITE);
        budgetSave.setOnClickListener(v -> saveBudget());
        budgetCard.addView(budgetSave, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 52)));
        budgetMsg = Ui.tv(a, "", 15, Ui.INK2, false);
        budgetMsg.setPadding(0, Ui.dp(a, 8), 0, 0);
        budgetMsg.setGravity(Gravity.CENTER);
        budgetCard.addView(budgetMsg);
        box.addView(budgetCard);

        box.addView(Ui.space(a, 14));

        // ---- 数据导出 ----
        LinearLayout exportCard = Ui.card(a, Ui.CARD);
        exportCard.setPadding(Ui.dp(a, 18), Ui.dp(a, 14), Ui.dp(a, 18), Ui.dp(a, 14));
        exportCard.addView(Ui.tv(a, "导出账单（备份）", 18, Ui.INK, true));
        exportCard.addView(Ui.space(a, 4));
        exportCard.addView(Ui.tv(a, "换手机前导出一份 CSV，用微信发给自己就不会丢",
                14, Ui.INK2, false));
        exportCard.addView(Ui.space(a, 12));

        TextView exportBtn = Ui.bigAction(a, "导出全部账单", Ui.INK, Color.WHITE);
        exportBtn.setOnClickListener(v -> exportCsv());
        exportCard.addView(exportBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 52)));
        exportMsg = Ui.tv(a, "", 14, Ui.INK2, false);
        exportMsg.setPadding(0, Ui.dp(a, 8), 0, 0);
        exportMsg.setGravity(Gravity.CENTER);
        exportCard.addView(exportMsg);
        box.addView(exportCard);

        box.addView(Ui.space(a, 20));
        return sv;
    }

    private void exportCsv() {
        CsvExporter.Result r = CsvExporter.export(a, db);
        exportMsg.setTextColor(r.ok ? Ui.GREEN : Ui.ACCENT);
        exportMsg.setText(r.message);
        Ui.toast(a, r.ok ? "已导出 " + r.rows + " 笔" : r.message);
    }

    /** 页面进入时全量刷新（汇总 + 分类占比 + 设置回填） */
    public void refresh() {
        refreshSummary();
        refreshStats();
        refreshSettings();
    }

    private void setLevel(String level) {
        sumLevel = level;
        Ui.styleSeg(a, segDay, "day".equals(level));
        Ui.styleSeg(a, segMonth, "month".equals(level));
        Ui.styleSeg(a, segYear, "year".equals(level));
        refreshSummary();
    }

    private void refreshSummary() {
        try {
            JSONObject s = db.periodsSummary(sumLevel);
            JSONObject cur = s.getJSONObject("current");
            sumCurrent.setText(labelFor(sumLevel, cur.getString("key"))
                    + " · 支出 ¥" + Ui.fmt(cur.getDouble("expense"))
                    + " · 收入 ¥" + Ui.fmt(cur.getDouble("income"))
                    + " · " + cur.getInt("count") + " 笔");
            sumBox.removeAllViews();
            JSONArray arr = s.getJSONArray("groups");
            if (arr.length() == 0) {
                sumBox.addView(Ui.tv(a, "还没有任何记录", 16, Ui.INK2, false));
                return;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject g = arr.getJSONObject(i);
                LinearLayout row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, Ui.dp(a, 9), 0, Ui.dp(a, 9));
                row.addView(Ui.tv(a, labelFor(sumLevel, g.getString("key")), 16, Ui.INK, false),
                        Ui.lp(0, 1f, 0));
                TextView right = Ui.tv(a, "支 ¥" + Ui.fmt(g.getDouble("expense"))
                        + " / 收 ¥" + Ui.fmt(g.getDouble("income")), 14, Ui.INK2, false);
                right.setGravity(Gravity.END);
                row.addView(right);
                sumBox.addView(row);
                View divider = new View(a);
                divider.setBackgroundColor(Ui.LINE);
                sumBox.addView(divider, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }
        } catch (Exception e) {
            Ui.toast(a, "汇总失败：" + e.getMessage());
        }
    }

    private static String labelFor(String level, String key) {
        if ("day".equals(level)) return key;
        if ("year".equals(level)) return key + " 年";
        String[] p = key.split("-");
        return p[0] + " 年 " + Integer.valueOf(p[1]) + " 月";
    }

    private void refreshStats() {
        try {
            JSONObject s = db.categoryStats(null);
            statsTotal.setText(s.getString("month") + " · 合计 ¥" + Ui.fmt(s.getDouble("total")));
            statsBox.removeAllViews();
            JSONArray arr = s.getJSONArray("categories");
            if (arr.length() == 0) {
                statsBox.addView(Ui.tv(a, "本月还没有支出记录", 16, Ui.INK2, false));
                return;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.getJSONObject(i);
                LinearLayout wrap = Ui.column(a);
                wrap.setPadding(0, Ui.dp(a, 9), 0, Ui.dp(a, 9));

                LinearLayout line = new LinearLayout(a);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.addView(Ui.tv(a, c.getString("category"), 16, Ui.INK, false), Ui.lp(0, 1f, 0));
                line.addView(Ui.tv(a, "¥" + Ui.fmt(c.getDouble("amount")) + " · "
                        + c.getDouble("percent") + "%", 14, Ui.INK2, false));
                wrap.addView(line);

                ProgressBar pb = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
                pb.setMax(100);
                pb.setProgress((int) Math.round(c.getDouble("percent")));
                pb.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
                pb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFEFE9DC));
                wrap.addView(pb, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 10)));
                statsBox.addView(wrap);
            }
        } catch (Exception e) {
            Ui.toast(a, "统计失败：" + e.getMessage());
        }
    }

    private void refreshSettings() {
        try {
            JSONArray arr = db.accounts();
            if (arr.length() > 0) {
                JSONObject o = arr.getJSONObject(0);
                if (etAccName != null) etAccName.setText(o.getString("name"));
                if (etAccBalance != null)
                    etAccBalance.setText(String.format(java.util.Locale.CHINA, "%.2f",
                            o.getDouble("balance")));
            }
            long b = db.budgetCents();
            if (etBudget != null) {
                etBudget.setText(b > 0
                        ? String.format(java.util.Locale.CHINA, "%.2f", b / 100.0) : "");
            }
            if (accMsg != null) accMsg.setText("");
            if (budgetMsg != null) budgetMsg.setText("");
        } catch (Exception e) {
            Ui.toast(a, "读取设置失败：" + e.getMessage());
        }
    }

    private void saveAccount() {
        try {
            String name = etAccName.getText().toString().trim();
            String bal = etAccBalance.getText().toString().trim();
            if (name.isEmpty()) { accMsg.setText("账户名不能为空"); return; }
            double balance = bal.isEmpty() ? 0 : Double.parseDouble(bal);
            db.updateDefaultAccount(name, balance);
            accMsg.setText("已保存");
            Ui.toast(a, "账户已保存");
        } catch (Exception e) {
            accMsg.setText("失败：" + e.getMessage());
        }
    }

    private void saveBudget() {
        try {
            String t = etBudget.getText().toString().trim();
            double yuan = t.isEmpty() ? 0 : Double.parseDouble(t);
            if (yuan < 0) { budgetMsg.setText("预算不能是负数"); return; }
            db.setBudget(yuan);
            budgetMsg.setText(yuan > 0 ? "已设预算 ¥" + Ui.fmt(yuan) : "已取消预算");
        } catch (Exception e) {
            budgetMsg.setText("失败：" + e.getMessage());
        }
    }
}
