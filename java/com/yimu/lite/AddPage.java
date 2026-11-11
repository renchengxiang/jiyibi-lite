package com.yimu.lite;

import android.app.Activity;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/** 手动记账页：支出/收入切换 + 金额 + 分类 + 日期 + 备注。 */
public class AddPage {

    private final Activity a;
    private final PageContract nav;
    private final Db db;

    private String addType = "expense";
    private TextView segExpense, segIncome, addMsg;
    private EditText etAmount, etNote, etDate;
    private android.widget.Spinner spCategory;
    private Cats cats = new Cats();

    public AddPage(Activity a, PageContract nav, Db db) {
        this.a = a;
        this.nav = nav;
        this.db = db;
    }

    public View build() {
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        LinearLayout box = Ui.column(a);
        sv.addView(box);

        TextView back = Ui.tv(a, "← 返回语音记账", 17, Ui.ACCENT, true);
        back.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 10));
        back.setOnClickListener(v -> nav.goTo(PageContract.PAGE_VOICE));
        box.addView(back);

        LinearLayout card = Ui.card(a, Ui.CARD);
        card.setPadding(Ui.dp(a, 18), Ui.dp(a, 16), Ui.dp(a, 18), Ui.dp(a, 16));

        LinearLayout seg = new LinearLayout(a);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        segExpense = Ui.segItem(a, "支出", true);
        segIncome = Ui.segItem(a, "收入", false);
        segExpense.setOnClickListener(v -> setType("expense"));
        segIncome.setOnClickListener(v -> setType("income"));
        seg.addView(segExpense, Ui.lp(0, 1f, Ui.dp(a, 8)));
        seg.addView(segIncome, Ui.lp(0, 1f, 0));
        card.addView(seg);
        card.addView(Ui.space(a, 16));

        card.addView(Ui.tv(a, "金额", 15, Ui.INK2, false));
        etAmount = Ui.amountInput(a);
        card.addView(etAmount);
        card.addView(Ui.space(a, 12));

        card.addView(Ui.tv(a, "分类", 15, Ui.INK2, false));
        spCategory = new android.widget.Spinner(a);
        card.addView(spCategory);
        card.addView(Ui.space(a, 12));

        card.addView(Ui.tv(a, "日期", 15, Ui.INK2, false));
        etDate = new EditText(a);
        etDate.setText(Db.today());
        etDate.setInputType(InputType.TYPE_CLASS_DATETIME);
        etDate.setTextColor(Ui.INK);
        etDate.setTextSize(18);
        card.addView(etDate);
        card.addView(Ui.space(a, 12));

        card.addView(Ui.tv(a, "备注", 15, Ui.INK2, false));
        etNote = new EditText(a);
        etNote.setHint("可以不写");
        etNote.setTextColor(Ui.INK);
        etNote.setTextSize(18);
        card.addView(etNote);
        card.addView(Ui.space(a, 18));

        TextView save = Ui.bigAction(a, "保存这笔账", Ui.INK, Color.WHITE);
        save.setOnClickListener(v -> save());
        card.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 60)));

        addMsg = Ui.tv(a, "", 16, Ui.INK2, false);
        addMsg.setPadding(0, Ui.dp(a, 10), 0, 0);
        addMsg.setGravity(Gravity.CENTER);
        card.addView(addMsg);

        box.addView(card);
        box.addView(Ui.space(a, 20));
        return sv;
    }

    public void refresh() {
        try {
            cats = Cats.load(db);
            fillSpinner();
        } catch (Exception e) {
            Ui.toast(a, "加载失败：" + e.getMessage());
        }
    }

    private void setType(String type) {
        addType = type;
        Ui.styleSeg(a, segExpense, "expense".equals(type));
        Ui.styleSeg(a, segIncome, "income".equals(type));
        fillSpinner();
    }

    private void fillSpinner() {
        android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(a,
                android.R.layout.simple_spinner_item, cats.names(addType));
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCategory.setAdapter(ad);
    }

    private void save() {
        try {
            String amtText = etAmount.getText().toString().trim();
            if (amtText.isEmpty()) { addMsg.setText("请输入金额"); return; }
            double amount = Double.parseDouble(amtText);
            String category = Cats.nameOf(spCategory.getSelectedItem());
            String date = etDate.getText().toString().trim();
            if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) { addMsg.setText("日期格式应为 yyyy-MM-dd"); return; }

            JSONObject created = db.addBill(addType, amount, category, null,
                    etNote.getText().toString().trim(), date);
            addMsg.setText("已记入「" + created.getString("category") + "」¥"
                    + Ui.fmt(created.getDouble("amount")));
            etAmount.setText("");
            etNote.setText("");
        } catch (Exception e) {
            addMsg.setText("失败：" + e.getMessage());
        }
    }
}
