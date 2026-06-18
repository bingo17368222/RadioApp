package com.radio.app.activities;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.radio.app.R;
import com.radio.app.utils.ThemeManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class OfflineEngineActivity extends AppCompatActivity {

    private static final String TAG = "OfflineEngineActivity";
    private static final long MIN_INSTALL_SIZE = 1024 * 1024; // 1MB，低于此大小不认为已安装
    private TextView tvTitle;
    private ImageButton btnBack;
    private ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();
    private Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Long> downloadProgress = new HashMap<>(); // 记录每个引擎的已下载字节数
    private volatile boolean isDestroyed = false; // Activity 是否已销毁

    private static class EngineInfo {
        String name, desc, size, downloadUrl, modelDir;
        EngineInfo(String n, String d, String s, String u, String m) {
            name = n; desc = d; size = s; downloadUrl = u; modelDir = m;
        }
    }

    private final EngineInfo[] engines = {
        // ===== Whisper 语音识别模型（OpenAI开源） =====
        new EngineInfo("Whisper Tiny",
                "OpenAI Whisper tiny模型\n大小: 约75MB | 识别率: ~85% | 速度: 极快(实时)\n适用: 快速字幕生成，对准确率要求不高的场景\n支持: 中文、英文及99种语言",
                "约75MB",
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
                "whisper-tiny"),
        new EngineInfo("Whisper Base",
                "OpenAI Whisper base模型\n大小: 约142MB | 识别率: ~90% | 速度: 快(近实时)\n适用: 日常字幕生成，准确率和速度平衡\n支持: 中文、英文及99种语言",
                "约142MB",
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
                "whisper-base"),
        new EngineInfo("Whisper Small",
                "OpenAI Whisper small模型\n大小: 约466MB | 识别率: ~93% | 速度: 中等(约2x实时)\n适用: 高质量字幕，推荐日常使用\n支持: 中文、英文及99种语言",
                "约466MB",
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin",
                "whisper-small"),
        new EngineInfo("Whisper Medium",
                "OpenAI Whisper medium模型\n大小: 约1.5GB | 识别率: ~95% | 速度: 较慢(约4x实时)\n适用: 专业级字幕，对准确率要求极高\n支持: 中文、英文及99种语言",
                "约1.5GB",
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium.bin",
                "whisper-medium"),
        new EngineInfo("Whisper Large-v3",
                "OpenAI Whisper large-v3模型\n大小: 约2.9GB | 识别率: ~97% | 速度: 慢(约8x实时)\n适用: 最高精度，专业转录场景\n支持: 中文、英文及99种语言",
                "约2.9GB",
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3.bin",
                "whisper-large"),

        // ===== Vosk 离线语音识别（APK内置） =====
        new EngineInfo("Vosk 小模型 (中文)",
                "Vosk small-cn 中文模型\n大小: 约42MB | 识别率: ~88% | 速度: 快(实时)\n状态: APK内置，无需下载\n适用: 中文语音识别，低资源消耗",
                "约42MB (内置)",
                null,
                "vosk-model-small-cn-0.22"),
        new EngineInfo("Vosk 大模型 (中文)",
                "Vosk cn 中文大模型\n大小: 约1.3GB | 识别率: ~95% | 速度: 中等\n适用: 高精度中文语音识别",
                "约1.3GB",
                "https://alphacephei.com/vosk/models/vosk-model-cn-0.22.zip",
                "vosk-model-cn-0.22"),

        // ===== Google ML Kit (用户手动下载) =====
        new EngineInfo("Google ML Kit (设备端)",
                "Google ML Kit on-device audio\n大小: 约85MB | 识别率: ~92% | 速度: 快(实时)\n状态: 需手动下载安装\n适用: 音频活动检测、语音分段",
                "约85MB",
                "https://github.com/google/ml-kit/releases/download/audio-v16.1.0/mlkit-audio-model.zip",
                "mlkit-audio"),

        // ===== 阿里 MNN-LLM (APK内置) =====
        new EngineInfo("阿里 MNN-LLM (设备端)",
                "阿里巴巴 MNN 推理引擎\n大小: APK内置 | 推理速度: 快(Tensor加速)\n状态: 已集成，无需下载\n适用: 本地AI内容分析、干货/水分分类",
                "内置",
                null,
                "mnn-llm")
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_offline_engine);

        tvTitle = findViewById(R.id.tv_title);
        btnBack = findViewById(R.id.btn_back);
        if (tvTitle != null) tvTitle.setText("离线引擎管理");
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        for (EngineInfo engine : engines) {
            setupEngineCard(engine);
        }
    }

    private void setupEngineCard(EngineInfo engine) {
        View card = LayoutInflater.from(this).inflate(R.layout.item_engine_card, null, false);
        TextView tvName = card.findViewById(R.id.tv_engine_name);
        TextView tvDesc = card.findViewById(R.id.tv_engine_desc);
        TextView tvSize = card.findViewById(R.id.tv_engine_size);
        Button btnAction = card.findViewById(R.id.btn_engine_action);
        ProgressBar progressBar = card.findViewById(R.id.progress_engine);

        tvName.setText(engine.name);
        tvDesc.setText(engine.desc);
        tvSize.setText(engine.size);
        if (progressBar != null) progressBar.setVisibility(View.GONE);

        boolean isBuiltin = engine.downloadUrl == null;
        if (isBuiltin) {
            btnAction.setText("已内置");
            btnAction.setEnabled(false);
            btnAction.setAlpha(0.6f);
        } else {
            File modelsDir = getExternalFilesDir("models");
            File modelDir = modelsDir != null ? new File(modelsDir, engine.modelDir) : null;
            boolean installed = modelDir != null && modelDir.exists() && getDirTotalSize(modelDir) >= MIN_INSTALL_SIZE;
            if (installed) {
                btnAction.setText("已安装(删除)");
                btnAction.setOnClickListener(v -> {
                    deleteRecursive(modelDir);
                    btnAction.setText("安装");
                    Toast.makeText(this, engine.name + " 已删除", Toast.LENGTH_SHORT).show();
                });
            } else {
                // 如果目录存在但文件不完整，清理残留
                if (modelDir != null && modelDir.exists()) {
                    deleteRecursive(modelDir);
                }
                btnAction.setText("安装");
                btnAction.setOnClickListener(v -> {
                    btnAction.setEnabled(false);
                    btnAction.setText("准备下载...");
                    if (progressBar != null) { progressBar.setVisibility(View.VISIBLE); progressBar.setProgress(0); }
                    downloadModel(engine, btnAction, progressBar);
                });
            }
        }

        LinearLayout container = findViewById(R.id.engine_container);
        if (container != null) container.addView(card);
    }

    private void downloadModel(EngineInfo engine, Button btn, ProgressBar progressBar) {
        downloadExecutor.execute(() -> {
            HttpURLConnection conn = null; InputStream is = null; FileOutputStream fos = null;
            try {
                File modelsDir = getExternalFilesDir("models");
                if (modelsDir == null) { uiHandler.post(() -> { if (!isDestroyed) { btn.setEnabled(true); btn.setText("安装"); } }); return; }
                if (!modelsDir.exists()) modelsDir.mkdirs();
                String fileName = engine.downloadUrl.substring(engine.downloadUrl.lastIndexOf('/') + 1);
                File outFile = new File(modelsDir, engine.modelDir + "/" + fileName);
                outFile.getParentFile().mkdirs();
                uiHandler.post(() -> { if (!isDestroyed) btn.setText("连接中..."); });
                URL url = new URL(engine.downloadUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET"); conn.setConnectTimeout(30000); conn.setReadTimeout(120000); conn.setInstanceFollowRedirects(true);
                int rc = conn.getResponseCode();
                Log.d(TAG, "Download response code: " + rc + " for " + engine.name);
                if (rc != 200) {
                    throw new Exception("HTTP " + rc);
                }
                int totalSize = conn.getContentLength();
                Log.d(TAG, "Download total size: " + totalSize + " for " + engine.name);
                is = conn.getInputStream(); fos = new FileOutputStream(outFile);
                byte[] buffer = new byte[8192]; int len, downloaded = 0; long lastUpdate = System.currentTimeMillis();
                while ((len = is.read(buffer)) != -1) {
                    if (isDestroyed) { // Activity 已销毁，取消下载
                        throw new Exception("下载已取消");
                    }
                    fos.write(buffer, 0, len); downloaded += len;
                    long now = System.currentTimeMillis();
                    if (now - lastUpdate > 500) {
                        lastUpdate = now;
                        int progress;
                        String progressText;
                        if (totalSize > 0) {
                            progress = (int)(downloaded * 100 / totalSize);
                            progressText = "下载: " + progress + "%";
                        } else {
                            progress = (int)(downloaded / 1024); // 以KB为单位显示
                            progressText = "已下载: " + progress + " KB";
                        }
                        int fp = progress;
                        String fpt = progressText;
                        uiHandler.post(() -> { if (!isDestroyed) { btn.setText(fpt); if (progressBar != null) progressBar.setProgress(fp); } });
                    }
                }
                if (outFile.length() < 1024) {
                    throw new Exception("下载文件过小: " + outFile.length() + " bytes");
                }
                uiHandler.post(() -> { if (!isDestroyed) btn.setText("下载完成"); });
                if (fileName.endsWith(".zip")) { uiHandler.post(() -> { if (!isDestroyed) btn.setText("解压中..."); }); unzipFile(outFile, new File(modelsDir, engine.modelDir)); outFile.delete(); }
                uiHandler.post(() -> { if (!isDestroyed) { btn.setEnabled(true); btn.setText("已安装(删除)"); if (progressBar != null) progressBar.setVisibility(View.GONE); Toast.makeText(OfflineEngineActivity.this, engine.name + " 安装完成", Toast.LENGTH_SHORT).show(); } });
            } catch (Exception e) {
                Log.e(TAG, "Download failed: " + e.getMessage(), e);
                // 下载失败，删除残留文件
                try {
                    File modelsDir = getExternalFilesDir("models");
                    if (modelsDir != null) {
                        File modelDir = new File(modelsDir, engine.modelDir);
                        if (modelDir.exists()) deleteRecursive(modelDir);
                    }
                } catch (Exception ignored) {}
                uiHandler.post(() -> { if (!isDestroyed) { btn.setEnabled(true); btn.setText("安装(重试)"); if (progressBar != null) progressBar.setVisibility(View.GONE); Toast.makeText(OfflineEngineActivity.this, "下载失败: " + e.getMessage(), Toast.LENGTH_LONG).show(); } });
            } finally { try { if (fos != null) fos.close(); } catch (Exception ignored) {} try { if (is != null) is.close(); } catch (Exception ignored) {} if (conn != null) conn.disconnect(); }
        });
    }

    private void unzipFile(File zipFile, File destDir) throws Exception {
        ZipInputStream zis = new ZipInputStream(new java.io.FileInputStream(zipFile));
        ZipEntry entry; byte[] buffer = new byte[8192];
        while ((entry = zis.getNextEntry()) != null) {
            File outFile = new File(destDir, entry.getName());
            if (entry.isDirectory()) outFile.mkdirs();
            else { outFile.getParentFile().mkdirs(); FileOutputStream fos = new FileOutputStream(outFile); int len; while ((len = zis.read(buffer)) != -1) fos.write(buffer, 0, len); fos.close(); }
            zis.closeEntry();
        }
        zis.close();
    }

    private void deleteRecursive(File file) { if (file.isDirectory()) { File[] c = file.listFiles(); if (c != null) for (File ch : c) deleteRecursive(ch); } file.delete(); }

    private long getDirTotalSize(File dir) {
        if (dir == null || !dir.exists()) return 0;
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) size += getDirTotalSize(f);
                else size += f.length();
            }
        }
        return size;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isDestroyed = true;
        if (downloadExecutor != null) downloadExecutor.shutdownNow();
    }
}
