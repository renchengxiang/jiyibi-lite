package com.yimu.lite;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 离线语音识别：sherpa-onnx 2.0.1 + Paraformer-zh-small int8（78MB，随 APK 内置）。
 *
 * 采集策略：AudioRecord 录完整句 PCM → 能量 VAD 裁静音 + 峰值归一化 → 后台整句解码。
 * 不边录边解，解码耗时不受实时约束；Paraformer 在手机上 RTF 约 0.01~0.07。
 *
 * 模型从 assets/model-paraformer/ 复制到 files/paraformer/（首次约 78MB）。
 * 选择理由与备选见 docs/03-tech/adr/ADR-005-paraformer-small.md。
 */
public class Asr {

    public interface Listener {
        void onPartial(String text);
        void onFinal(String text);
        void onError(String message);
        void onReady();
    }

    private static final String TAG = "Asr";
    private static final int SAMPLE_RATE = 16000;
    private static final int MIN_PCM_BYTES = SAMPLE_RATE * 2 * 3 / 10;
    private static final String MODEL_DIR = "paraformer";
    private static final String ASSET_DIR = "model-paraformer";

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    private OfflineRecognizer recognizer;
    private boolean preparing = false;
    private Listener listener;

    private AudioRecord recorder;
    private Thread captureThread;
    private volatile boolean recording = false;
    private ByteArrayOutputStream pcm;

    public Asr(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    public boolean isReady() { return recognizer != null; }

    public void setListener(Listener l) { this.listener = l; }

    /** 后台准备模型并构造 recognizer；已就绪直接回调 onReady */
    public void prepare() {
        if (recognizer != null) {
            if (listener != null) main.post(listener::onReady);
            return;
        }
        if (preparing) return;
        preparing = true;

        new Thread(() -> {
            try {
                File dir = unpackModel();
                OfflineParaformerModelConfig pm = new OfflineParaformerModelConfig();
                pm.setModel(new File(dir, "model.int8.onnx").getAbsolutePath());

                OfflineModelConfig mc = new OfflineModelConfig();
                mc.setParaformer(pm);
                mc.setTokens(new File(dir, "tokens.txt").getAbsolutePath());
                mc.setNumThreads(2);
                mc.setDebug(false);

                OfflineRecognizerConfig rc = new OfflineRecognizerConfig();
                rc.setModelConfig(mc);
                rc.setDecodingMethod("greedy_search");

                // 构造器需要 AssetManager；模型路径已指向 files/，此处不实际读 assets
                // 绝对路径加载模型：AssetManager 必须传 null，否则 native 会去 assets 里找
                recognizer = new OfflineRecognizer(null, rc);
                preparing = false;
                if (listener != null) main.post(listener::onReady);
            } catch (Throwable e) {
                preparing = false;
                Log.e(TAG, "prepare failed: " + e);
                if (listener != null) {
                    final String msg = "语音包加载失败：" + e.getMessage();
                    main.post(() -> listener.onError(msg));
                }
            }
        }, "asr-prepare").start();
    }

    /** assets 里的模型复制到 files/paraformer（按文件大小判重） */
    private File unpackModel() throws Exception {
        File dir = new File(ctx.getFilesDir(), MODEL_DIR);
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建模型目录");
        File model = new File(dir, "model.int8.onnx");
        File tokens = new File(dir, "tokens.txt");
        // 用完整读取统计模型字节数判重（不依赖 openFd 的压缩限制）
        long expect = assetSize(ASSET_DIR + "/model.int8.onnx");
        if (model.length() != expect) copyAsset(ASSET_DIR + "/model.int8.onnx", model);
        if (tokens.length() < 1000) copyAsset(ASSET_DIR + "/tokens.txt", tokens);
        return dir;
    }

    private long assetSize(String path) throws Exception {
        try (InputStream in = ctx.getAssets().open(path)) {
            long n = 0;
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) n += r;
            return n;
        }
    }

    private void copyAsset(String from, File to) throws Exception {
        try (InputStream in = ctx.getAssets().open(from);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        }
    }

    /** 开始录音（只采集，不解码） */
    public void start() {
        if (recognizer == null) {
            if (listener != null) listener.onError("语音还在准备，请稍等几秒再试");
            return;
        }
        releaseRecorder();
        try {
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(min * 2, SAMPLE_RATE);
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

            final AudioRecord ar = recorder;
            captureThread = new Thread(() -> {
                byte[] chunk = new byte[3200];
                while (recording) {
                    int n;
                    try {
                        n = ar.read(chunk, 0, chunk.length);
                    } catch (Exception e) {
                        break;
                    }
                    if (n <= 0) {
                        if (n < 0) break;
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

    /** 停止录音，VAD 后在后台整句解码 */
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

    /** 整句离线解码：float[] 波形 → OfflineStream → result.text */
    private void decode(byte[] data) {
        final OfflineRecognizer r = recognizer;
        if (r == null) {
            if (listener != null) main.post(() -> listener.onError("语音还没准备好"));
            return;
        }
        new Thread(() -> {
            OfflineStream st = null;
            try {
                int n = data.length / 2;
                short[] s = new short[n];
                ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s);
                float[] samples = new float[n];
                for (int i = 0; i < n; i++) samples[i] = s[i] / 32768.0f;

                st = r.createStream();
                st.acceptWaveform(samples, SAMPLE_RATE);
                r.decode(st);
                String text = r.getResult(st).getText().trim();
                Log.i(TAG, "decode => " + text);
                appendEvalLog(text);
                final String out = text;
                if (listener != null) main.post(() -> listener.onFinal(out));
            } catch (Throwable e) {
                Log.e(TAG, "decode failed: " + e);
                if (listener != null) {
                    final String msg = "识别失败：" + e.getMessage();
                    main.post(() -> listener.onError(msg));
                }
            } finally {
                if (st != null) {
                    try { st.release(); } catch (Exception ignored) { }
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

    /** 能量 VAD + 峰值归一化（20ms 帧；阈值 = max(200, 峰值 10%)，前后留 200ms） */
    private static byte[] trimAndNormalize(byte[] data) {
        int n = data.length / 2;
        if (n < 320) return data;
        short[] s = new short[n];
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s);

        int win = 320;
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
        int pad = 10;
        first = Math.max(0, first - pad);
        last = Math.min(frames - 1, last + pad);
        if (last - first < 15) { first = 0; last = frames - 1; }

        double gain = Math.min(4.0, (0.75 * 32767) / peak);
        int count = (last - first + 1) * win;
        byte[] out = new byte[count * 2];
        ByteBuffer ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = first * win; i < (last + 1) * win; i++) {
            int v = (int) Math.round(s[i] * gain);
            ob.putShort((short) Math.max(-32768, Math.min(32767, v)));
        }
        Log.i(TAG, "VAD: frames " + frames + " -> [" + first + "," + last + "]"
                + ", peak " + peak + ", gain " + String.format("%.2f", gain));
        return out;
    }

    /** 诊断：debuggable 构建把每次识别结果追加到 eval.txt，避免 logcat 环形缓冲丢记录 */
    private void appendEvalLog(String text) {
        try {
            boolean debuggable = (ctx.getApplicationInfo().flags
                    & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            if (!debuggable) return;
            File dir = new File(ctx.getExternalFilesDir(null), "asr");
            if (!dir.exists() && !dir.mkdirs()) return;
            try (FileOutputStream fos = new FileOutputStream(new File(dir, "eval.txt"), true)) {
                fos.write((text + "\n").getBytes("UTF-8"));
            }
        } catch (Exception ignored) { }
    }

    private void dumpIfDebuggable(byte[] data, String name) {
        try {
            boolean debuggable = (ctx.getApplicationInfo().flags
                    & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            if (!debuggable) return;
            File dir = new File(ctx.getExternalFilesDir(null), "asr");
            if (!dir.exists() && !dir.mkdirs()) return;
            File f = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(data);
            }
            Log.i(TAG, "dumped " + data.length + " bytes to " + f.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "dump failed: " + e.getMessage());
        }
    }
}
