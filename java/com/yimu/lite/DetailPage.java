package com.yimu.lite;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONObject;

/** 账单详情/编辑页：回填 + 保存修改 + 删除。 */
public class DetailPage {

    private final Activity a;
    private final PageContract nav;
    private final Db db;

    private long billId = 0;
    private String detailType = "expense";
    private TextView segEditExpense, segEditIncome, detailMsg;
    private EditText etEditAmount, etEditNote, etEditDate;
    private Spinner spEditCategory;
    private Cats cats = new Cats();

    public DetailPage(Activity a, PageContract nav, Db db) {
        this.a = a;
        this.nav = nav;
        this.db = db;
    }

    public View build() {
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        LinearLayout box = Ui.column(a);
        sv.addView(box);

        TextView back = Ui.tv(a, "← 返回明细", 17, Ui.ACCENT, true);
        back.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 10));
        back.setOnClickListener(v -> nav.goTo(PageContract.PAGE_LIST));
        box.addView(back);

        LinearLayout card = Ui.card(a, Ui.CARD);
        card.setPadding(Ui.dp(a, 18), Ui.dp(a, 16), Ui.dp(a, 18), Ui.dp(a, 16));

        LinearLayout seg = new LinearLayout(a);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        segEditExpense = Ui.segItem(a, "支出", true);
        segEditIncome = Ui.segItem(a, "收入", false);
        segEditExpense.setOnClickListener(v -> setType("expense"));
        segEditIncome.setOnClickListener(v -> setType("income"));
        seg.addView(segEditExpense, Ui.lp(0, 1f, Ui.dp(a, 8)));
        seg.addView(segEditIncome, Ui.lp(0, 1f, 0));
        card.addView(seg);
        card.addView(Ui.space(a, 16));

        card.addView(Ui.tv(a, "金额", 15, Ui.INK2, false));
        etEditAmount = Ui.amountInput(a);
        card.addView(etEditAmount);
        card.addView(Ui.space(a, 12));

        card.addView(Ui.tv(a, "分类", 15, Ui.INK2, false));
        spEditCategory = new Spinner(a);
        card.addView(spEditCategory);
        card.addView(Ui.space(a, 12));

        card.addView(Ui.tv(a, "日期", 15, Ui.INK2, false));
        etEditDate = new EditText(a);
        etEditDate.setInputType(InputType.TYPE_CLASS_DATETIME);
        etEditDate.setTextColor(Ui.INK);
        etEditDate.setTextSize(18);
        card.addView(etEditDate);
        card.addView(Ui.space(a, 12));

        card.addView(Ui.tv(a, "备注", 15, Ui.INK2, false));
        etEditNote = new EditText(a);
        etEditNote.setHint("可以不写");
        etEditNote.setTextColor(Ui.INK);
        etEditNote.setTextSize(18);
        card.addView(etEditNote);
        card.addView(Ui.space(a, 18));

        LinearLayout btns = new LinearLayout(a);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView save = Ui.bigAction(a, "保存修改", Ui.INK, Color.WHITE);
        save.setOnClickListener(v -> save());
        TextView del = Ui.bigAction(a, "删除", Ui.SEG_OFF, Ui.ACCENT);
        del.setOnClickListener(v -> confirmDelete());
        LinearLayout.LayoutParams sp1 = new LinearLayout.LayoutParams(0, Ui.dp(a, 60), 1f);
        sp1.rightMargin = Ui.dp(a, 10);
        btns.addView(save, sp1);
        btns.addView(del, new LinearLayout.LayoutParams(0, Ui.dp(a, 60), 1f));
        card.addView(btns);

        detailMsg = Ui.tv(a, "", 16, Ui.INK2, false);
        detailMsg.setPadding(0, Ui.dp(a, 10), 0, 0);
        detailMsg.setGravity(Gravity.CENTER);
        card.addView(detailMsg);

        box.addView(card);
        box.addView(Ui.space(a, 20));
        return sv;
    }

    public void refresh() {
        billId = nav.currentDetailBillId();
        try {
            cats = Cats.load(db);           // 先备好分类，避免下拉为空
            JSONObject b = db.getBill(billId);
            if (b == null) {
                detailMsg.setText("这笔账已不存在");
                return;
            }
            detailType = b.getString("type");
            Ui.styleSeg(a, segEditExpense, "expense".equals(detailType));
            Ui.styleSeg(a, segEditIncome, "income".equals(detailType));
            etEditAmount.setText(String.format(java.util.Locale.CHINA, "%.2f", b.getDouble("amount")));
            etEditDate.setText(b.getString("date"));
            etEditNote.setText(b.optString("note", ""));
            fillSpinner(b.getString("category"));
            detailMsg.setText("");
        } catch (Exception e) {
            detailMsg.setText("读取失败：" + e.getMessage());
        }
    }

    private void setType(String type) {
        detailType = type;
        Ui.styleSeg(a, segEditExpense, "expense".equals(type));
        Ui.styleSeg(a, segEditIncome, "income".equals(type));
        fillSpinner(null);
    }

    private void fillSpinner(String selected) {
        android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(a,
                android.R.layout.simple_spinner_item, cats.names(detailType));
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spEditCategory.setAdapter(ad);
        if (selected != null) spEditCategory.setSelection(cats.indexOf(detailType, selected));
    }

    private void save() {
        try {
            String amtText = etEditAmount.getText().toString().trim();
            if (amtText.isEmpty()) { detailMsg.setText("请输入金额"); return; }
            double amount = Double.parseDouble(amtText);
            if (spEditCategory.getSelectedItem() == null) { detailMsg.setText("没有可选分类"); return; }
            String category = Cats.nameOf(spEditCategory.getSelectedItem());
            String date = etEditDate.getText().toString().trim();
            if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) { detailMsg.setText("日期格式应为 yyyy-MM-dd"); return; }

            db.updateBill(billId, detailType, amount, category, null,
                    etEditNote.getText().toString().trim(), date);
            detailMsg.setText("已保存修改");
            Ui.toast(a, "已保存修改");
        } catch (Exception e) {
            detailMsg.setText("失败：" + e.getMessage());
        }
    }

    private void confirmDelete() {
        final long id = billId;
        new AlertDialog.Builder(a)
                .setTitle("删除这笔账")
                .setMessage("删除后无法恢复，确定吗？")
                .setPositiveButton("删除", (d, w) -> {
                    try {
                        db.deleteBill(id);
                        Ui.toast(a, "已删除");
                        nav.goTo(PageContract.PAGE_LIST);
                    } catch (Exception ex) {
                        detailMsg.setText("删除失败：" + ex.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
