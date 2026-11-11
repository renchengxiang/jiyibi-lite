package com.yimu.lite;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** 明细页：类型筛选 + 账单列表（点行进编辑、✕ 确认删除）。 */
public class ListPage {

    private final Activity a;
    private final PageContract nav;
    private final Db db;

    private Spinner spFilter;
    private LinearLayout listBox;
    private TextView listSummary;

    public ListPage(Activity a, PageContract nav, Db db) {
        this.a = a;
        this.nav = nav;
        this.db = db;
    }

    public View build() {
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        LinearLayout box = Ui.column(a);
        sv.addView(box);

        LinearLayout card = Ui.card(a, Ui.CARD);
        card.setPadding(Ui.dp(a, 18), Ui.dp(a, 14), Ui.dp(a, 18), Ui.dp(a, 14));

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.tv(a, "账单明细", 18, Ui.INK, true), Ui.lp(0, 1f, 0));
        spFilter = new Spinner(a);
        ArrayAdapter<String> fa = new ArrayAdapter<>(a, android.R.layout.simple_spinner_item,
                new String[]{"全部", "支出", "收入"});
        fa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spFilter.setAdapter(fa);
        spFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { refresh(); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        head.addView(spFilter, new LinearLayout.LayoutParams(Ui.dp(a, 120), ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(head);

        listSummary = Ui.tv(a, "", 15, Ui.INK2, false);
        listSummary.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 8));
        card.addView(listSummary);

        listBox = Ui.column(a);
        card.addView(listBox);
        box.addView(card);
        box.addView(Ui.space(a, 20));
        return sv;
    }

    public void refresh() {
        try {
            String type = null;
            int pos = spFilter == null ? 0 : spFilter.getSelectedItemPosition();
            if (pos == 1) type = "expense";
            if (pos == 2) type = "income";
            JSONObject r = db.queryBills(null, null, type, null, null, 80, 0);
            listSummary.setText("共 " + r.getInt("matched_count") + " 笔 · 支出 ¥"
                    + Ui.fmt(r.getDouble("matched_expense")) + " · 收入 ¥" + Ui.fmt(r.getDouble("matched_income")));
            renderBillRows(listBox, r.getJSONArray("bills"));
        } catch (Exception e) {
            Ui.toast(a, "读取失败：" + e.getMessage());
        }
    }

    private void renderBillRows(LinearLayout parent, JSONArray bills) throws Exception {
        parent.removeAllViews();
        if (bills.length() == 0) {
            parent.addView(Ui.tv(a, "还没有记录，回首页按住按钮说一句就行", 16, Ui.INK2, false));
            return;
        }
        for (int i = 0; i < bills.length(); i++) {
            JSONObject b = bills.getJSONObject(i);
            boolean expense = "expense".equals(b.getString("type"));

            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(a, 11), 0, Ui.dp(a, 11));

            TextView icon = Ui.tv(a, b.optString("icon", "•"), 18, Ui.INK, false);
            icon.setGravity(Gravity.CENTER);
            GradientDrawable ib = new GradientDrawable();
            ib.setColor(Ui.ICON_BG);
            ib.setCornerRadius(Ui.dp(a, 10));
            icon.setBackground(ib);
            row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(a, 36), Ui.dp(a, 36)));

            LinearLayout mid = Ui.column(a);
            mid.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 8), 0);
            String parentCat = b.optString("parent_category", "");
            String cat = b.getString("category");
            TextView catTv = Ui.tv(a, parentCat.isEmpty() || parentCat.equals(cat) ? cat : parentCat + " / " + cat,
                    17, Ui.INK, false);
            catTv.setSingleLine(true);
            catTv.setEllipsize(TextUtils.TruncateAt.END);
            mid.addView(catTv);

            StringBuilder meta = new StringBuilder(b.getString("occurred_at"));
            if (!b.optString("note", "").isEmpty()) meta.append(" · ").append(b.optString("note"));
            TextView metaTv = Ui.tv(a, meta.toString(), 14, Ui.INK2, false);
            metaTv.setSingleLine(true);
            metaTv.setEllipsize(TextUtils.TruncateAt.END);
            mid.addView(metaTv);
            row.addView(mid, Ui.lp(0, 1f, 0));

            TextView amt = Ui.tv(a, (expense ? "-" : "+") + "¥" + Ui.fmt(b.getDouble("amount")),
                    17, expense ? Ui.ACCENT : Ui.GREEN, true);
            amt.setSingleLine(true);
            amt.setGravity(Gravity.END);
            amt.setMinWidth(Ui.dp(a, 104));
            row.addView(amt);

            TextView del = Ui.tv(a, "✕", 16, Ui.DEL, false);
            del.setPadding(Ui.dp(a, 12), Ui.dp(a, 8), 0, Ui.dp(a, 8));
            final long id = b.getLong("id");
            del.setOnClickListener(v -> confirmDelete(id));
            row.addView(del);
            row.setOnClickListener(v -> nav.openDetail(id));
            row.setClickable(true);

            parent.addView(row);
            View divider = new View(a);
            divider.setBackgroundColor(Ui.LINE);
            parent.addView(divider, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
    }

    private void confirmDelete(final long id) {
        new AlertDialog.Builder(a)
                .setTitle("删除这笔账")
                .setMessage("删除后无法恢复，确定吗？")
                .setPositiveButton("删除", (d, w) -> {
                    try {
                        db.deleteBill(id);
                        refresh();
                        Ui.toast(a, "已删除");
                    } catch (Exception ex) {
                        Ui.toast(a, "删除失败：" + ex.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
