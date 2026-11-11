package com.yimu.lite;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 语音记账页：月度总览卡 + 大圆按住说话按钮 + 确认卡 + 最近一笔 + 手动入口。
 */
public class VoicePage implements Asr.Listener {

    private final Activity a;
    private final PageContract nav;
    private final Db db;
    private final Asr asr;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoStop = this::stopListening;

    private TextView voiceMonth, voiceBudget, voiceStatus, voiceTranscript, voiceRecent;
    private TextView bigBtnLabel;
    private LinearLayout bigBtn;
    private LinearLayout confirmCard;
    private TextView confirmAmount, confirmMeta, confirmRaw;
    private VoiceParser.Result pending;
    private boolean listening = false;

    public VoicePage(Activity a, PageContract nav, Db db, Asr asr) {
        this.a = a;
        this.nav = nav;
        this.db = db;
        this.asr = asr;
        asr.setListener(this);
    }

    public View build() {
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.setFillViewport(true);
        LinearLayout box = Ui.column(a);
        sv.addView(box);

        LinearLayout sum = Ui.card(a, Ui.CARD);
        sum.setPadding(Ui.dp(a, 18), Ui.dp(a, 14), Ui.dp(a, 18), Ui.dp(a, 14));
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = Ui.column(a);
        left.addView(Ui.tv(a, "本月支出", 16, Ui.INK2, false));
        voiceMonth = Ui.tv(a, "¥0.00", 34, Ui.INK, true);
        left.addView(voiceMonth);
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        voiceBudget = Ui.tv(a, "", 16, Ui.INK2, false);
        voiceBudget.setGravity(Gravity.END);
        row.addView(voiceBudget);
        sum.addView(row);
        box.addView(sum);

        box.addView(Ui.space(a, 16));

        FrameLayout wrap = new FrameLayout(a);
        bigBtn = new LinearLayout(a);
        bigBtn.setOrientation(LinearLayout.VERTICAL);
        bigBtn.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Ui.INK);
        bigBtn.setBackground(circle);
        bigBtn.setClickable(true);

        TextView mic = Ui.tv(a, "🎤", 46, Color.WHITE, false);
        mic.setGravity(Gravity.CENTER);
        bigBtn.addView(mic);
        bigBtnLabel = Ui.tv(a, "按住 说话", 26, Color.WHITE, true);
        bigBtnLabel.setGravity(Gravity.CENTER);
        bigBtnLabel.setPadding(0, Ui.dp(a, 6), 0, 0);
        bigBtn.addView(bigBtnLabel);

        int size = Ui.dp(a, 230);
        wrap.addView(bigBtn, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        box.addView(wrap);

        bigBtn.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    onPressStart();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    onPressEnd();
                    return true;
                default:
                    return false;
            }
        });

        box.addView(Ui.space(a, 14));

        voiceStatus = Ui.tv(a, "按住按钮说话，比如「买菜三十块五」", 17, Ui.INK2, false);
        voiceStatus.setGravity(Gravity.CENTER);
        box.addView(voiceStatus);

        voiceTranscript = Ui.tv(a, "", 22, Ui.INK, true);
        voiceTranscript.setGravity(Gravity.CENTER);
        voiceTranscript.setPadding(0, Ui.dp(a, 10), 0, 0);
        box.addView(voiceTranscript);

        confirmCard = Ui.card(a, Ui.CARD);
        confirmCard.setPadding(Ui.dp(a, 18), Ui.dp(a, 16), Ui.dp(a, 18), Ui.dp(a, 16));
        confirmCard.setVisibility(View.GONE);
        TextView tip = Ui.tv(a, "请确认这笔账", 15, Ui.INK2, false);
        tip.setGravity(Gravity.CENTER);
        confirmCard.addView(tip);
        confirmAmount = Ui.tv(a, "", 42, Ui.ACCENT, true);
        confirmAmount.setGravity(Gravity.CENTER);
        confirmCard.addView(confirmAmount);
        confirmMeta = Ui.tv(a, "", 19, Ui.INK, false);
        confirmMeta.setGravity(Gravity.CENTER);
        confirmCard.addView(confirmMeta);
        confirmRaw = Ui.tv(a, "", 15, Ui.INK3, false);
        confirmRaw.setGravity(Gravity.CENTER);
        confirmRaw.setPadding(0, Ui.dp(a, 6), 0, 0);
        confirmCard.addView(confirmRaw);
        confirmCard.addView(Ui.space(a, 14));

        LinearLayout btns = new LinearLayout(a);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView ok = Ui.bigAction(a, "确认记账", Ui.INK, Color.WHITE);
        ok.setOnClickListener(v -> commitVoice());
        TextView again = Ui.bigAction(a, "重说", Ui.SEG_OFF, Ui.INK);
        again.setOnClickListener(v -> resetVoice());
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, Ui.dp(a, 62), 1f);
        p1.rightMargin = Ui.dp(a, 10);
        btns.addView(ok, p1);
        btns.addView(again, new LinearLayout.LayoutParams(0, Ui.dp(a, 62), 1f));
        confirmCard.addView(btns);

        LinearLayout.LayoutParams cclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cclp.topMargin = Ui.dp(a, 14);
        box.addView(confirmCard, cclp);

        box.addView(Ui.space(a, 18));

        LinearLayout recent = Ui.card(a, Ui.CARD);
        recent.setPadding(Ui.dp(a, 18), Ui.dp(a, 14), Ui.dp(a, 18), Ui.dp(a, 14));
        recent.addView(Ui.tv(a, "最近一笔", 15, Ui.INK2, false));
        voiceRecent = Ui.tv(a, "还没有记录", 19, Ui.INK, true);
        voiceRecent.setPadding(0, Ui.dp(a, 6), 0, 0);
        recent.addView(voiceRecent);
        box.addView(recent);

        box.addView(Ui.space(a, 16));

        TextView manual = Ui.bigAction(a, "用手输入（不用说话）", Ui.CARD, Ui.INK);
        manual.setOnClickListener(v -> nav.goTo(PageContract.PAGE_ADD));
        box.addView(manual, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 56)));

        box.addView(Ui.space(a, 20));
        return sv;
    }

    public void refresh() {
        try {
            JSONObject o = db.overview();
            JSONObject m = o.getJSONObject("month");
            voiceMonth.setText("¥" + Ui.fmt(m.getDouble("expense")));
            if (o.isNull("budget")) {
                voiceBudget.setText("本月\n" + m.getInt("count") + " 笔");
            } else {
                JSONObject bg = o.getJSONObject("budget");
                double remain = bg.getDouble("remaining");
                voiceBudget.setText("预算剩\n¥" + Ui.fmt(remain));
                voiceBudget.setTextColor(remain < 0 ? Ui.ACCENT : Ui.GREEN);
            }
            JSONObject bills = db.queryBills(null, null, null, null, null, 1, 0);
            JSONArray arr = bills.getJSONArray("bills");
            if (arr.length() > 0) {
                JSONObject b = arr.getJSONObject(0);
                boolean exp = "expense".equals(b.getString("type"));
                String cat = b.optString("parent_category", "");
                if (cat.isEmpty() || cat.equals(b.getString("category"))) cat = b.getString("category");
                voiceRecent.setText((exp ? "-" : "+") + "¥" + Ui.fmt(b.getDouble("amount"))
                        + "  " + cat + " · " + b.getString("occurred_at"));
                voiceRecent.setTextColor(exp ? Ui.ACCENT : Ui.GREEN);
            }
        } catch (Exception e) {
            Ui.toast(a, "读取失败：" + e.getMessage());
        }
    }

    // ---------------- 按住说话 ----------------
    private void onPressStart() {
        if (!asr.isReady()) {
            setStatus("正在准备语音包（首次约 30 秒），准备好后再按住说话", Ui.ACCENT);
            asr.prepare();
            return;
        }
        if (!nav.ensureMicPermission()) return;
        confirmCard.setVisibility(View.GONE);
        voiceTranscript.setText("");
        setStatus("正在听…松手结束", Ui.ACCENT);
        bigBtnLabel.setText("松手 结束");
        scale(true);
        listening = true;
        asr.start();
        handler.postDelayed(autoStop, 15000);
    }

    private void onPressEnd() {
        if (!listening) return;
        bigBtnLabel.setText("识别中…");
        scale(false);
        handler.removeCallbacks(autoStop);
        asr.stop();
    }

    private void stopListening() {
        if (!listening) return;
        handler.removeCallbacks(autoStop);
        asr.stop();
        bigBtnLabel.setText("识别中…");
    }

    public void onStop() {
        if (listening) {
            listening = false;
            bigBtnLabel.setText("按住 说话");
        }
        handler.removeCallbacks(autoStop);
    }

    private void scale(boolean pressed) {
        float from = pressed ? 1f : 0.94f;
        float to = pressed ? 0.94f : 1f;
        ScaleAnimation an = new ScaleAnimation(from, to, from, to,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        an.setDuration(120);
        an.setFillAfter(true);
        bigBtn.startAnimation(an);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(pressed ? Ui.ACCENT : Ui.INK);
        bigBtn.setBackground(d);
    }

    private void setStatus(String text, int color) {
        voiceStatus.setText(text);
        voiceStatus.setTextColor(color);
    }

    /** 把识别文本解析成账单并展示确认卡片 */
    private void applyText(String text) {
        voiceTranscript.setText("「" + text + "」");
        VoiceParser.Result r = VoiceParser.parse(text);
        if (!r.ok()) {
            setStatus("没听到金额，请说清楚多少钱，比如「买菜三十块五」", Ui.ACCENT);
            return;
        }
        pending = r;
        confirmAmount.setText((r.type.equals("expense") ? "-¥" : "+¥") + Ui.fmt(r.amount));
        confirmAmount.setTextColor(r.type.equals("expense") ? Ui.ACCENT : Ui.GREEN);
        confirmMeta.setText(r.type.equals("expense") ? "支出 · " + r.category : "收入 · " + r.category);
        confirmRaw.setText("听到：" + text);
        confirmCard.setVisibility(View.VISIBLE);
        setStatus("对吗？不对就点「重说」", Ui.INK2);
    }

    private void commitVoice() {
        if (pending == null) return;
        try {
            db.addBill(pending.type, pending.amount, pending.category, null, pending.note, Db.today());
            confirmCard.setVisibility(View.GONE);
            voiceTranscript.setText("已记好：" + (pending.type.equals("expense") ? "支出 " : "收入 ")
                    + "¥" + Ui.fmt(pending.amount) + " · " + pending.category);
            setStatus("已记好，可以继续按住说话", Ui.GREEN);
            pending = null;
            refresh();
        } catch (Exception e) {
            setStatus("保存失败：" + e.getMessage(), Ui.ACCENT);
        }
    }

    private void resetVoice() {
        pending = null;
        confirmCard.setVisibility(View.GONE);
        voiceTranscript.setText("");
        setStatus("请按住按钮重新说", Ui.INK2);
    }

    // ---------------- Asr.Listener ----------------
    @Override public void onPartial(String text) {
        if (!text.isEmpty()) voiceTranscript.setText(text);
    }

    @Override public void onFinal(String text) {
        listening = false;
        bigBtnLabel.setText("按住 说话");
        scale(false);
        if (text == null || text.isEmpty()) {
            setStatus("没听清，请再说一次", Ui.ACCENT);
            return;
        }
        applyText(text);
    }

    @Override public void onError(String message) {
        listening = false;
        bigBtnLabel.setText("按住 说话");
        scale(false);
        setStatus(message, Ui.ACCENT);
    }

    @Override public void onReady() {
        setStatus("可以按住说话了", Ui.INK2);
    }

    /** 评测/调试钩子：把文本直接送入解析链路 */
    public void applyDemoText(String text) {
        applyText(text);
    }
}
