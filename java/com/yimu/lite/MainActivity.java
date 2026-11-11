package com.yimu.lite;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用骨架：顶栏 + 内容区 + 底部页签。
 * 页签：记账 / 明细 / 汇总；另有「用手输入」与「账单编辑」两个非页签页面。
 * 面向老人：大字号、大触控区、高对比。
 *
 * 具体页面实现见 VoicePage / ListPage / SummaryPage / AddPage / DetailPage，
 * 控件工厂见 Ui，页面↔骨架契约见 PageContract。
 */
public class MainActivity extends Activity implements PageContract {

    private static final int REQ_AUDIO = 1001;

    private final String[] NAV = {"记账", "明细", "汇总"};
    private final List<TextView> navItems = new ArrayList<>();

    private FrameLayout content;
    private LinearLayout nav;

    private Db db;
    private Asr asr;

    private VoicePage voicePage;
    private ListPage listPage;
    private SummaryPage summaryPage;
    private AddPage addPage;
    private DetailPage detailPage;

    private View vVoice, vList, vSummary, vAdd, vDetail;
    private long detailBillId = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        db = Db.get(this);

        asr = new Asr(this);
        voicePage = new VoicePage(this, this, db, asr);
        listPage = new ListPage(this, this, db);
        summaryPage = new SummaryPage(this, this, db);
        addPage = new AddPage(this, this, db);
        detailPage = new DetailPage(this, this, db);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        setContentView(buildRoot());
        goTo(PAGE_VOICE);

        asr.prepare();

        // 调试钩子：仅在 debuggable 构建中生效，正式包忽略，避免被其他应用伪造记账
        boolean debuggable = (getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        String demo = getIntent() == null ? null : getIntent().getStringExtra("demo");
        if (debuggable && demo != null && !demo.isEmpty()) {
            handler.postDelayed(() -> voicePage.applyDemoText(demo), 400);
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (asr != null) asr.stop();
        if (voicePage != null) voicePage.onStop();
        handler.removeCallbacksAndMessages(null);
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (code == REQ_AUDIO && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) {
            Ui.toast(this, "权限已给，请再按住按钮说话");
        } else if (code == REQ_AUDIO) {
            Ui.toast(this, "没有麦克风权限：请到系统设置里允许，或点「用手输入」");
        }
    }

    // ---------------- 骨架 ----------------
    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), 0);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView mark = Ui.tv(this, "记", 18, Color.parseColor("#F8F3E9"), true);
        mark.setGravity(Gravity.CENTER);
        GradientDrawable markBg = new GradientDrawable();
        markBg.setColor(Ui.INK);
        markBg.setCornerRadius(Ui.dp(this, 10));
        mark.setBackground(markBg);
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 38), Ui.dp(this, 38));
        markLp.rightMargin = Ui.dp(this, 10);
        header.addView(mark, markLp);

        LinearLayout titleBox = Ui.column(this);
        titleBox.addView(Ui.tv(this, "记一笔 Lite", 20, Ui.INK, true));
        titleBox.addView(Ui.tv(this, Db.today() + " · 本地数据", 13, Ui.INK2, false));
        header.addView(titleBox, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header);

        content = new FrameLayout(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        clp.topMargin = Ui.dp(this, 8);
        root.addView(content, clp);

        nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 12));
        for (int i = 0; i < NAV.length; i++) {
            final int idx = i;
            TextView item = Ui.tv(this, NAV[i], 16, Ui.INK2, false);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
            item.setOnClickListener(v -> goTo(idx));
            navItems.add(item);
            nav.addView(item, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(nav);

        // Android 15+ 强制 edge-to-edge：给状态栏/导航栏留出安全区
        final int basePadH = Ui.dp(this, 16);
        final int basePadTop = Ui.dp(this, 12);
        final int minBottom = Ui.dp(this, 8);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            v.setPadding(basePadH, basePadTop + top, basePadH, Math.max(bottom, minBottom));
            return insets;
        });
        root.requestApplyInsets();

        return root;
    }

    /** 导航高亮 + 内容区替换。公共入口，页面类通过 PageContract 调用。 */
    @Override public void goTo(int page) {
        for (int i = 0; i < navItems.size(); i++) {
            TextView t = navItems.get(i);
            boolean on = i == page;
            t.setTextColor(on ? Ui.INK : Ui.INK2);
            t.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
        content.removeAllViews();
        View v;
        switch (page) {
            case PAGE_VOICE:
                if (vVoice == null) vVoice = voicePage.build();
                v = vVoice;
                voicePage.refresh();
                break;
            case PAGE_LIST:
                if (vList == null) vList = listPage.build();
                v = vList;
                listPage.refresh();
                break;
            case PAGE_SUMMARY:
                if (vSummary == null) vSummary = summaryPage.build();
                v = vSummary;
                summaryPage.refresh();
                break;
            case PAGE_DETAIL:
                if (vDetail == null) vDetail = detailPage.build();
                v = vDetail;
                detailPage.refresh();
                break;
            default:
                if (vAdd == null) vAdd = addPage.build();
                v = vAdd;
                addPage.refresh();
                break;
        }
        content.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override public void openDetail(long billId) {
        detailBillId = billId;
        goTo(PAGE_DETAIL);
    }

    @Override public long currentDetailBillId() {
        return detailBillId;
    }

    @Override public boolean ensureMicPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) return true;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        return false;
    }

    @Override public void replayDemo(String text) {
        if (voicePage != null) voicePage.applyDemoText(text);
    }
}
