package com.findeye.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class AiModelManager {
    private static final String MODEL_URL =
            "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/owl_vit/releases/v0.63.0/owl_vit-onnx-w8a16.zip";

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean busy = false;

    public AiModelManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isReady() {
        File onnx = getModelFile();
        File data = new File(onnx.getParentFile(), "owl_vit.data");
        return onnx.exists() && onnx.length() > 100_000
                && data.exists() && data.length() > 500_000_000L;
    }

    public File getModelFile() {
        return new File(
                new File(context.getFilesDir(), "ai/owl_vit-onnx-w8a16"),
                "owl_vit.onnx"
        );
    }

    public boolean isBusy() {
        return busy;
    }

    public void download(Listener listener) {
        if (busy) return;
        if (isReady()) {
            main.post(listener::onReady);
            return;
        }

        StatFs fs = new StatFs(context.getFilesDir().getAbsolutePath());
        long free = fs.getAvailableBytes();
        if (free < 1_250_000_000L) {
            main.post(() -> listener.onError(
                    "צריך בערך 1.3GB פנוי בזמן ההתקנה של מודל ה-AI."
            ));
            return;
        }

        busy = true;
        executor.execute(() -> {
            File zip = new File(context.getExternalFilesDir(null), "FindEyeAI.zip");
            try {
                listenerProgress(listener, "מוריד מודל AI", 0);

                HttpURLConnection connection = (HttpURLConnection) new URL(MODEL_URL).openConnection();
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(60_000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "FindEye-Android/0.2");
                connection.connect();

                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("HTTP " + code);
                }

                long total = connection.getContentLengthLong();
                try (
                        BufferedInputStream input = new BufferedInputStream(connection.getInputStream(), 128 * 1024);
                        BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(zip), 128 * 1024)
                ) {
                    byte[] buffer = new byte[128 * 1024];
                    long downloaded = 0;
                    int n;
                    int lastPercent = -1;

                    while ((n = input.read(buffer)) >= 0) {
                        output.write(buffer, 0, n);
                        downloaded += n;

                        if (total > 0) {
                            int percent = (int) ((downloaded * 100L) / total);
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                listenerProgress(listener, "מוריד מודל AI", percent);
                            }
                        }
                    }
                } finally {
                    connection.disconnect();
                }

                listenerProgress(listener, "מכין את מודל ה-AI", 100);
                extract(zip);
                if (!isReady()) {
                    throw new IllegalStateException("קבצי המודל לא נמצאו אחרי החילוץ.");
                }

                if (zip.exists()) zip.delete();
                busy = false;
                main.post(listener::onReady);
            } catch (Throwable t) {
                busy = false;
                if (zip.exists()) zip.delete();
                main.post(() -> listener.onError(
                        "הורדת מודל ה-AI נכשלה. בדוק חיבור לאינטרנט ונסה שוב."
                ));
            }
        });
    }

    private void extract(File zipFile) throws Exception {
        File root = new File(context.getFilesDir(), "ai");
        deleteRecursively(root);
        if (!root.mkdirs() && !root.exists()) {
            throw new IllegalStateException("Cannot create AI directory");
        }

        String rootPath = root.getCanonicalPath() + File.separator;

        try (ZipInputStream zin = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(zipFile), 128 * 1024)
        )) {
            ZipEntry entry;
            byte[] buffer = new byte[128 * 1024];

            while ((entry = zin.getNextEntry()) != null) {
                File out = new File(root, entry.getName());
                String outPath = out.getCanonicalPath();
                if (!outPath.startsWith(rootPath)) {
                    throw new SecurityException("Unsafe ZIP path");
                }

                if (entry.isDirectory()) {
                    if (!out.mkdirs() && !out.exists()) {
                        throw new IllegalStateException("Cannot create model directory");
                    }
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IllegalStateException("Cannot create model parent directory");
                    }
                    try (BufferedOutputStream output =
                                 new BufferedOutputStream(new FileOutputStream(out), 128 * 1024)) {
                        int n;
                        while ((n = zin.read(buffer)) >= 0) {
                            output.write(buffer, 0, n);
                        }
                    }
                }
                zin.closeEntry();
            }
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        file.delete();
    }

    private void listenerProgress(Listener listener, String stage, int percent) {
        main.post(() -> listener.onProgress(stage, percent));
    }

    public interface Listener {
        void onProgress(String stage, int percent);
        void onReady();
        void onError(String message);
    }
}
