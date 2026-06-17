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
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class OfflineEngineActivity extends AppCompatActivity {

    private static final String TAG = "OfflineEngineActivity";
    private TextView tvTitle;
    private ImageButton btnBack;
    private ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();
    private Handler uiHandler = new Handler(Looper.getMainLooper());

    private static class EngineInfo {
        String name, desc, size, downloadUrl, modelDir;
        EngineInfo(String n, String d, String s, String u, String m) {
            name = n; desc = d; size = s; downloadUrl = u; modelDir = m;
        }
    }

    private final EngineInfo[] engines = {
        new EngineInfo("Vosk 小模型(中文)", "alphacephei vosk-model-small-cn", "约42MB",
                "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip",
                "vosk-model-small-cn-0.22"),
        new EngineInfo("Vosk 大模型(中文)", "alphacephei vosk-model-cn", "约1.3GB",
                "https://alphacephei.com/vosk/models/vosk-model-cn-0.22.zip",
                "vosk-model-cn-0.22"),
        new EngineInfo("PocketSphinx(英文)", "CMU pocketsphinx-hmm-en", "约30MB",
                "https://sourceforge.net/projects/cmusphinx/files/Acoustic%20and%20Language%20Models/US%20English/cmusphinx-en-us-ptm-5.2.tar.gz",
                "pocketsphinx-en-us")
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

        File modelsDir = getExternalFilesDir("models");
        File modelDir = modelsDir != null ? new File(modelsDir, engine.modelDir) : null;
        boolean installed = modelDir != null && modelDir.exists() && modelDir.isDirectory();

        if (installed) {
            btnAction.setText("已安装(删除)");
            btnAction.setOnClickListener(v -> {
                deleteRecursive(modelDir);
                btnAction.setText("安装");
                Toast.makeText(this, engine.name + " 已删除", Toast.LENGTH_SHORT).show();
            });
        } else {
            btnAction.setText("安装");
            btnAction.setOnClickListener(v -> {
                btnAction.setEnabled(false);
                btnAction.setText("准备下载...");
                if (progressBar != null) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(0);
                }
                downloadModel(engine, btnAction, progressBar);
            });
        }

        LinearLayout container = findViewById(R.id.engine_container);
        if (container != null) container.addView(card);
    }

    private void downloadModel(EngineInfo engine, Button btn, ProgressBar progressBar) {
        downloadExecutor.execute(() -> {
            HttpURLConnection conn = null;
            InputStream is = null;
            FileOutputStream fos = null;
            try {
                File modelsDir = getExternalFilesDir("models");
                if (modelsDir == null) {
                    uiHandler.post(() -> {
                        btn.setEnabled(true);
                        btn.setText("安装");
                        if (progressBar != null) progressBar.setVisibility(View.GONE);
                        Toast.makeText(this, "无法访问存储", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                if (!modelsDir.exists()) modelsDir.mkdirs();

                String fileName = engine.downloadUrl.substring(engine.downloadUrl.lastIndexOf('/') + 1);
                File zipFile = new File(modelsDir, fileName);

                uiHandler.post(() -> btn.setText("连接中..."));
                URL url = new URL(engine.downloadUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(60000);
                conn.setInstanceFollowRedirects(true);

                int totalSize = conn.getContentLength();
                is = conn.getInputStream();
                fos = new FileOutputStream(zipFile);

                byte[] buffer = new byte[8192];
                int len;
                int downloaded = 0;
                long lastUpdate = System.currentTimeMillis();
                while ((len = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                    downloaded += len;
                    long now = System.currentTimeMillis();
                    if (now - lastUpdate > 500) {
                        lastUpdate = now;
                        int progress = totalSize > 0 ? (int) (downloaded * 100 / totalSize) : 0;
                        int fp = progress;
                        uiHandler.post(() -> {
                            btn.setText("下载: " + fp + "%");
                            if (progressBar != null) progressBar.setProgress(fp);
                        });
                    }
                }
                uiHandler.post(() -> btn.setText("下载完成，解压中..."));

                if (fileName.endsWith(".zip")) {
                    unzipFile(zipFile, modelsDir);
                }
                zipFile.delete();

                uiHandler.post(() -> {
                    btn.setEnabled(true);
                    btn.setText("已安装(删除)");
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    Toast.makeText(this, engine.name + " 安装完成", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "Download failed: " + e.getMessage());
                uiHandler.post(() -> {
                    btn.setEnabled(true);
                    btn.setText("安装(重试)");
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    Toast.makeText(this, "下载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            } finally {
                try { if (fos != null) fos.close(); } catch (Exception ignored) {}
                try { if (is != null) is.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
            }
        });
    }

    private void unzipFile(File zipFile, File destDir) throws Exception {
        ZipInputStream zis = new ZipInputStream(new java.io.FileInputStream(zipFile));
        ZipEntry entry;
        byte[] buffer = new byte[8192];
        while ((entry = zis.getNextEntry()) != null) {
            File outFile = new File(destDir, entry.getName());
            if (entry.isDirectory()) {
                outFile.mkdirs();
            } else {
                outFile.getParentFile().mkdirs();
                FileOutputStream fos = new FileOutputStream(outFile);
                int len;
                while ((len = zis.read(buffer)) != -1) fos.write(buffer, 0, len);
                fos.close();
            }
            zis.closeEntry();
        }
        zis.close();
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        file.delete();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (downloadExecutor != null) downloadExecutor.shutdownNow();
    }
}
