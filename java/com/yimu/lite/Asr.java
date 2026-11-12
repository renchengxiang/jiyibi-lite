package com.yimu.lite;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.ByteArrayOutputStream;


/**
 * 离线语音识别（Vosk 中文小模型，随 APK 内置，不依赖 Google 服务）。
 *
 * 采集策略：**先录完整句 PCM，松手后再一次性解码**（不是边录边解码）。
 * 原因：边录边解要求解码速度 ≥ 实时；手机 CPU 一旦跟不上（大 beam / 后台抢占 / 发热降频），
 * 音频帧会被丢弃，识别结果退化成几个字的碎片。整句离线解码没有这个约束。
 *
 * 已知机型问题（PHB110 / ColorOS，2026-11）：VOICE_RECOGNITION 与 MIC 两种音源下，
 * 部分真人录音的主频集中在 16~359Hz，缺少 1k~4kHz 语音谐波，Vosk 与 SenseVoice 均无法还原。
 * 桌面 TTS 用同一模型 7~10/10 全对，说明模型不是瓶颈；疑为按住大圆按钮时手掌遮挡底部麦克风
 * 或系统 DSP 过度降噪。见 docs/asr-diagnosis.md。
 */
public class Asr {

    public interface Listener {
        /** 阶段提示（现在只用于「正在识别」这类状态，不再吐中间结果） */
        void onPartial(String text);
        void onFinal(String text);
        void onError(String message);
        void onReady();
    }

    private static final String TAG = "Asr";
    private static final String ASSET = "model-cn";
    private static final String DIR = "model-cn";

    private static final int SAMPLE_RATE = 16000;
    /** 少于 0.3 秒（约半句话）判为误触 */
    private static final int MIN_PCM_BYTES = SAMPLE_RATE * 2 * 3 / 10;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Model model;
    private boolean preparing = false;
    private Listener listener;

    private AudioRecord recorder;
    private Thread captureThread;
    private volatile boolean recording = false;
    private ByteArrayOutputStream pcm;

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
        org.vosk.android.StorageService.unpack(ctx, ASSET, DIR,
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

    /** 开始录音（只采集，不解码） */
    public void start() {
        if (model == null) {
            if (listener != null) listener.onError("语音还在准备，请稍等几秒再试");
            return;
        }
        releaseRecorder();
        try {
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(min * 2, SAMPLE_RATE); // 至少 0.5 秒
            // 用 MIC 而非 VOICE_RECOGNITION：部分 ColorOS / OEM 机型对后者做激进降噪，
            // 会把起始人声一并削掉（实测：前 1s rms≈83，TTS 对照 3870，差 46 倍）。
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                releaseRecorder();
                if (listener != null) listener.onError("麦克风打不开，请检查权限");
                return;
            }
            pcm = new ByteArrayOutputStream(SAMPLE_RATE * 2 * 5);
            recorder.startRecording();
            recording = true;

            // 线程内持有 AudioRecord 的本地引用：主线程 stop() 时会把字段置空，
            // 若这里继续读字段会 NPE（线程可能正阻塞在 read() 里）
            final AudioRecord ar = recorder;
            captureThread = new Thread(() -> {
                byte[] chunk = new byte[3200]; // 100ms
                while (recording) {
                    int n;
                    try {
                        n = ar.read(chunk, 0, chunk.length);
                    } catch (Exception e) {
                        break;                    // 已被 release
                    }
                    if (n <= 0) {
                        if (n < 0) break;         // 读失败（如被系统抢占），结束采集
                        continue;
                    }
                    synchronized (Asr.this) {
                        if (pcm != null) pcm.write(chunk, 0, n);
                    }
                }
            }, "asr-capture");
            captureThread.start();
        } catch (Exception e) {
            releaseRecorder();
            if (listener != null) listener.onError("启动麦克风失败：" + e.getMessage());
        }
    }

    /** 停止录音，并在后台整句解码后回调 onFinal */
    public void stop() {
        if (!recording) return;
        recording = false;
        Thread t = captureThread;
        captureThread = null;
        if (t != null) {
            try { t.join(300); } catch (InterruptedException ignored) { }
        }
        releaseRecorder();

        byte[] data;
        synchronized (this) {
            data = pcm == null ? new byte[0] : pcm.toByteArray();
            pcm = null;
        }
        if (data.length < MIN_PCM_BYTES) {
            if (listener != null) main.post(() -> listener.onError("说话时间太短，请按住说完再松手"));
            return;
        }
        dumpIfDebuggable(data, "last-raw.pcm");
        byte[] speech = trimAndNormalize(data);
        dumpIfDebuggable(speech, "last.pcm");
        if (speech.length < MIN_PCM_BYTES) {
            if (listener != null) main.post(() -> listener.onError("没听到说话声，请靠近手机再说一次"));
            return;
        }
        decode(speech);
    }

    /**
     * 能量 VAD + 峰值归一化。
     * 手机 AudioRecord 启动后前 ~1 秒常是近静音噪声（实测 rms≈83，正常语音 3870），
     * 模型会把这段当成整句去解，输出「韭菜凉快令」这类碎片。
     * 做法：20ms 一帧算 rms，按「峰值 10% 且不低于 200」定阈值，裁掉头尾静音，
     * 前后各留 200ms 缓冲，再把峰值补到 0.75 满幅（增益上限 4 倍）。
     */
    private static byte[] trimAndNormalize(byte[] data) {
        int n = data.length / 2;
        if (n < 320) return data;
        short[] s = new short[n];
        java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer().get(s);

        int win = 320;                    // 20ms @16k
        int frames = n / win;
        double[] rms = new double[frames];
        int peak = 0;
        for (int f = 0; f < frames; f++) {
            long sum = 0;
            for (int i = 0; i < win; i++) {
                int v = s[f * win + i];
                sum += (long) v * v;
                if (Math.abs(v) > peak) peak = Math.abs(v);
            }
            rms[f] = Math.sqrt(sum / (double) win);
        }
        if (peak == 0) return data;

        double thr = Math.max(200.0, peak * 0.10);
        int first = 0, last = frames - 1;
        while (first < frames && rms[first] < thr) first++;
        while (last > first && rms[last] < thr) last--;
        int pad = 10;                     // 前后各 200ms
        first = Math.max(0, first - pad);
        last = Math.min(frames - 1, last + pad);
        if (last - first < 15) { first = 0; last = frames - 1; }   // 太短就不裁

        double gain = Math.min(4.0, (0.75 * 32767) / peak);
        int count = (last - first + 1) * win;
        byte[] out = new byte[count * 2];
        java.nio.ByteBuffer ob = java.nio.ByteBuffer.wrap(out)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = first * win; i < (last + 1) * win; i++) {
            int v = (int) Math.round(s[i] * gain);
            ob.putShort((short) Math.max(-32768, Math.min(32767, v)));
        }
        Log.i(TAG, "VAD: frames " + frames + " -> [" + first + "," + last + "]"
                + ", peak " + peak + ", gain " + String.format("%.2f", gain));
        return out;
    }

    /**
     * 诊断用：debuggable 构建里把最近一次录音存成 16kHz/单声道/16bit PCM。
     * 真机识别不佳时把文件拉出来（adb pull）在电脑上用同一模型复解码，
     * 就能区分「是模型听错」还是「采集/链路有问题」。正式包不落盘。
     */
    private void dumpIfDebuggable(byte[] data, String name) {
        try {
            boolean debuggable = (ctx.getApplicationInfo().flags
                    & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            if (!debuggable) return;
            java.io.File dir = new java.io.File(ctx.getExternalFilesDir(null), "asr");
            if (!dir.exists() && !dir.mkdirs()) return;
            java.io.File f = new java.io.File(dir, name);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                fos.write(data);
            }
            Log.i(TAG, "dumped " + data.length + " bytes to " + f.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "dump failed: " + e.getMessage());
        }
    }

    public void release() {
        recording = false;
        Thread t = captureThread;
        captureThread = null;
        if (t != null) {
            try { t.join(300); } catch (InterruptedException ignored) { }
        }
        releaseRecorder();
        synchronized (this) { pcm = null; }
    }

    /** 整句离线解码：解码耗时与音频时长无关，不会丢帧 */
    private void decode(byte[] data) {
        final Model m = model;
        if (m == null) {
            if (listener != null) main.post(() -> listener.onError("语音还没准备好"));
            return;
        }
        new Thread(() -> {
            Recognizer r = null;
            try {
                r = new Recognizer(m, SAMPLE_RATE);
                r.setWords(false);
                r.acceptWaveForm(data, data.length);
                String json = r.getFinalResult();
                String text = textOf(json, "text");
                final String out = text == null ? "" : text;
                if (listener != null) main.post(() -> listener.onFinal(out));
            } catch (Throwable e) {
                Log.e(TAG, "decode failed: " + e);
                if (listener != null) {
                    final String msg = "识别失败：" + e.getMessage();
                    main.post(() -> listener.onError(msg));
                }
            } finally {
                if (r != null) {
                    try { r.close(); } catch (Exception ignored) { }
                }
            }
        }, "asr-decode").start();
    }

    private void releaseRecorder() {
        AudioRecord ar = recorder;
        recorder = null;
        if (ar == null) return;
        try { ar.stop(); } catch (Exception ignored) { }
        try { ar.release(); } catch (Exception ignored) { }
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
