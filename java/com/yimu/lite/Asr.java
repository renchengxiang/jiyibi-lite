package com.yimu.lite;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.io.File;

/**
 * 离线语音识别（Vosk 中文小模型，随 APK 内置，不依赖 Google 服务）。
 * 首次启动会把 assets 里的模型解压到 files/model（一次性，约 30 秒）。
 */
public class Asr {

    public interface Listener {
        void onPartial(String text);
        void onFinal(String text);
        void onError(String message);
        void onReady();
    }

    private static final String TAG = "Asr";
    private static final String ASSET = "model-cn";
    private static final String DIR = "model-cn";

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Model model;
    private Recognizer recognizer;
    private SpeechService service;
    private boolean preparing = false;
    private Listener listener;

    public Asr(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    public boolean isReady() { return model != null; }

    public void setListener(Listener l) { this.listener = l; }

    /** 后台准备模型；已就绪直接回调 onReady */
    public void prepare() {
        if (model != null) {
            if (listener != null) main.post(listener::onReady);
            return;
        }
        if (preparing) return;
        preparing = true;

        // StorageService 会比对 uuid：已解压则直接加载，否则从 assets 复制（首次约 30 秒）
        StorageService.unpack(ctx, ASSET, DIR,
                m -> {
                    model = m;
                    preparing = false;
                    if (listener != null) main.post(listener::onReady);
                },
                e -> {
                    preparing = false;
                    Log.e(TAG, "unpack failed: " + e.getMessage());
                    if (listener != null) main.post(() -> listener.onError("语音包解压失败：" + e.getMessage()));
                });
    }

    public void start() {
        if (model == null) {
            if (listener != null) listener.onError("语音还在准备，请稍等几秒再试");
            return;
        }
        try {
            closeService();
            recognizer = new Recognizer(model, 16000.0f);
            recognizer.setWords(false);
            service = new SpeechService(recognizer, 16000.0f);
            // 更宽的解码束：小模型丢字的主要缓解手段，单句记账速度足够
            service.startListening(new RecognitionListener() {
                @Override public void onPartialResult(String hypothesis) {
                    String text = textOf(hypothesis, "partial");
                    if (text != null && listener != null && !text.isEmpty()) {
                        main.post(() -> listener.onPartial(text));
                    }
                }
                @Override public void onResult(String hypothesis) {
                    String text = textOf(hypothesis, "text");
                    if (text != null && listener != null && !text.isEmpty()) {
                        main.post(() -> listener.onPartial(text));
                    }
                }
                @Override public void onFinalResult(String hypothesis) {
                    String text = textOf(hypothesis, "text");
                    if (listener != null) {
                        final String t = text == null ? "" : text;
                        main.post(() -> listener.onFinal(t));
                    }
                }
                @Override public void onError(Exception e) {
                    if (listener != null) {
                        String msg = e == null ? "识别出错" : String.valueOf(e.getMessage());
                        main.post(() -> listener.onError(msg));
                    }
                }
                @Override public void onTimeout() {
                    if (listener != null) main.post(() -> listener.onError("没听清，请再说一次"));
                }
            });
        } catch (Exception e) {
            if (listener != null) listener.onError("启动麦克风失败：" + e.getMessage());
        }
    }

    /** 停止录音并触发最终结果 */
    public void stop() {
        if (service != null) {
            try { service.stop(); } catch (Exception ignored) { }
        }
    }

    private void closeService() {
        if (service != null) {
            try { service.shutdown(); } catch (Exception ignored) { }
            service = null;
        }
        if (recognizer != null) {
            try { recognizer.close(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    private static String textOf(String json, String key) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            String t = o.optString(key, "");
            return t.replace(" ", "");
        } catch (Exception e) {
            return null;
        }
    }
}
