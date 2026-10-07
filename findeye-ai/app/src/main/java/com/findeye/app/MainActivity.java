package com.findeye.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("deprecation")
public final class MainActivity extends Activity
        implements SurfaceHolder.Callback, Camera.PreviewCallback {

    private static final int CAMERA_PERMISSION = 20;
    private static final int BLUE = Color.rgb(26, 115, 232);
    private static final int TEXT = Color.rgb(32, 33, 36);
    private static final int MUTED = Color.rgb(95, 99, 104);
    private static final int BORDER = Color.rgb(218, 220, 224);
    private static final int SURFACE = Color.rgb(248, 249, 250);
    private static final float DETECTION_THRESHOLD = 0.10f;
    private static final long SCAN_INTERVAL_MS = 300L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean inferenceRunning = new AtomicBoolean(false);

    private SurfaceView cameraView;
    private FinderOverlay overlay;
    private EditText queryInput;
    private Button searchButton;
    private Button flashButton;
    private Button modelButton;
    private TextView statusText;
    private TextView modelText;

    private Camera camera;
    private int previewWidth = 640;
    private int previewHeight = 480;
    private int frameRotation = 90;
    private boolean flashOn = false;

    private AiModelManager modelManager;
    private volatile OwlVitEngine engine;
    private volatile boolean engineLoading = false;

    private volatile boolean scanning = false;
    private String currentQuery = "";
    private long lastScanAt = 0L;
    private int misses = 0;
    private boolean wasFound = false;

    private ToneGenerator toneGenerator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(SURFACE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        toneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70);
        modelManager = new AiModelManager(this);

        setContentView(buildUi());
        updateModelUi();

        if (modelManager.isReady()) {
            initializeEngine();
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        }
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(SURFACE);

        LinearLayout root = column();
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(root, new ViewGroup.LayoutParams(-1, -2));

        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView logo = text("F", 20, Color.WHITE, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(round(BLUE, 12, 0, Color.TRANSPARENT));
        top.addView(logo, new LinearLayout.LayoutParams(dp(42), dp(42)));

        addSpace(top, 11);

        LinearLayout titles = column();
        TextView title = text("FindEye", 23, TEXT, true);
        TextView sub = text("AI object finder", 12, MUTED, false);
        titles.addView(title);
        titles.addView(sub);
        top.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));

        root.addView(top);

        TextView intro = text(
                "כתוב מה אתה מחפש והעבר את המצלמה בחדר. החיפוש ממשיך עד שאתה עוצר אותו.",
                14, TEXT, false
        );
        intro.setPadding(0, dp(15), 0, dp(13));
        root.addView(intro);

        FrameLayout cameraCard = new FrameLayout(this);
        cameraCard.setBackground(round(Color.BLACK, 18, 0, Color.TRANSPARENT));
        cameraCard.setClipToOutline(true);
        cameraCard.setOutlineProvider(ViewOutlineProvider.BACKGROUND);

        cameraView = new SurfaceView(this);
        cameraView.getHolder().addCallback(this);
        cameraCard.addView(cameraView, new FrameLayout.LayoutParams(-1, dp(445)));

        overlay = new FinderOverlay(this);
        cameraCard.addView(overlay, new FrameLayout.LayoutParams(-1, dp(445)));

        TextView live = text("LIVE AI", 11, TEXT, true);
        live.setPadding(dp(10), dp(7), dp(10), dp(7));
        live.setBackground(round(Color.argb(238, 255, 255, 255), 99, 0, Color.TRANSPARENT));
        FrameLayout.LayoutParams liveParams =
                new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        liveParams.setMargins(0, dp(12), dp(12), 0);
        cameraCard.addView(live, liveParams);

        root.addView(cameraCard, new LinearLayout.LayoutParams(-1, dp(445)));

        LinearLayout searchRow = row();
        searchRow.setPadding(0, dp(13), 0, 0);

        queryInput = new EditText(this);
        queryInput.setHint("לדוגמה: blue keys");
        queryInput.setSingleLine(true);
        queryInput.setTextSize(15);
        queryInput.setTextColor(TEXT);
        queryInput.setHintTextColor(Color.rgb(128, 134, 139));
        queryInput.setPadding(dp(14), 0, dp(14), 0);
        queryInput.setBackground(round(Color.WHITE, 12, 1, BORDER));
        searchRow.addView(queryInput, new LinearLayout.LayoutParams(0, dp(52), 1));

        addSpace(searchRow, 8);

        searchButton = primaryButton("חפש");
        searchButton.setOnClickListener(v -> toggleSearch());
        searchRow.addView(searchButton, new LinearLayout.LayoutParams(dp(94), dp(52)));

        root.addView(searchRow);

        LinearLayout actions = row();
        actions.setPadding(0, dp(9), 0, 0);

        flashButton = secondaryButton("פנס");
        flashButton.setOnClickListener(v -> toggleFlash());

        Button clearButton = secondaryButton("נקה");
        clearButton.setOnClickListener(v -> {
            queryInput.setText("");
            stopSearch();
        });

        actions.addView(flashButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        addSpace(actions, 8);
        actions.addView(clearButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        root.addView(actions);

        statusText = text("מכין מצלמה...", 13, MUTED, false);
        statusText.setPadding(dp(14), dp(12), dp(14), dp(12));
        statusText.setBackground(round(Color.WHITE, 12, 1, BORDER));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.setMargins(0, dp(12), 0, 0);
        root.addView(statusText, statusParams);

        LinearLayout modelCard = column();
        modelCard.setPadding(dp(15), dp(14), dp(15), dp(14));
        modelCard.setBackground(round(Color.WHITE, 14, 1, BORDER));

        TextView modelTitle = text("AI model", 15, TEXT, true);
        modelText = text("", 13, MUTED, false);
        modelText.setPadding(0, dp(5), 0, dp(10));

        modelButton = secondaryButton("הורד מודל AI");
        modelButton.setOnClickListener(v -> downloadModel());

        modelCard.addView(modelTitle);
        modelCard.addView(modelText);
        modelCard.addView(modelButton, new LinearLayout.LayoutParams(-1, dp(46)));

        LinearLayout.LayoutParams modelParams = new LinearLayout.LayoutParams(-1, -2);
        modelParams.setMargins(0, dp(12), 0, 0);
        root.addView(modelCard, modelParams);

        TextView note = text(
                "ה-AI עובד על הטלפון. אחרי הורדה חד פעמית של המודל, תמונות המצלמה לא נשלחות לשרת.",
                11, MUTED, false
        );
        note.setPadding(dp(2), dp(11), dp(2), 0);
        root.addView(note);

        return scroll;
    }

    private void downloadModel() {
        if (modelManager.isBusy()) return;

        modelButton.setEnabled(false);
        modelManager.download(new AiModelManager.Listener() {
            @Override
            public void onProgress(String stage, int percent) {
                modelText.setText(stage + "  " + percent + "%");
                modelButton.setText(percent < 100 ? percent + "%" : "מכין...");
            }

            @Override
            public void onReady() {
                modelButton.setEnabled(true);
                updateModelUi();
                initializeEngine();
            }

            @Override
            public void onError(String message) {
                modelButton.setEnabled(true);
                modelButton.setText("נסה שוב");
                modelText.setText(message);
            }
        });
    }

    private void updateModelUi() {
        if (modelManager.isReady()) {
            modelText.setText(engine == null ? "המודל הורד. טוען AI..." : "מוכן לחיפוש רציף.");
            modelButton.setVisibility(View.GONE);
        } else {
            modelText.setText("נדרשת הורדה חד פעמית של כ-523MB כדי שהזיהוי יעבוד באמת.");
            modelButton.setVisibility(View.VISIBLE);
            modelButton.setText("הורד מודל AI  523MB");
        }
    }

    private void initializeEngine() {
        if (engine != null || engineLoading || !modelManager.isReady()) return;

        engineLoading = true;
        modelText.setText("טוען את מודל ה-AI לזיכרון...");
        aiExecutor.execute(() -> {
            try {
                OwlVitEngine loaded = new OwlVitEngine(
                        this,
                        modelManager.getModelFile().getAbsolutePath()
                );
                engine = loaded;
                main.post(() -> {
                    engineLoading = false;
                    modelText.setText("מוכן לחיפוש רציף.");
                    statusText.setText("AI מוכן. כתוב מה אתה מחפש ולחץ חפש.");
                });
            } catch (Throwable t) {
                main.post(() -> {
                    engineLoading = false;
                    modelText.setText("לא הצלחתי לטעון את מודל ה-AI.");
                    statusText.setText("טעינת ה-AI נכשלה. נסה לפתוח את האפליקציה מחדש.");
                });
            }
        });
    }

    private void toggleSearch() {
        if (scanning) {
            stopSearch();
            return;
        }

        String q = queryInput.getText().toString().trim();
        if (q.isEmpty()) {
            statusText.setText("כתוב קודם מה אתה מחפש.");
            return;
        }

        if (engine == null) {
            if (!modelManager.isReady()) {
                statusText.setText("צריך להוריד קודם את מודל ה-AI.");
            } else {
                statusText.setText("ה-AI עדיין נטען לזיכרון.");
                initializeEngine();
            }
            return;
        }

        currentQuery = q;
        searchButton.setEnabled(false);
        statusText.setText("מכין חיפוש ל-\"" + q + "\"...");

        aiExecutor.execute(() -> {
            try {
                engine.setQuery(q);
                main.post(() -> {
                    scanning = true;
                    misses = 0;
                    wasFound = false;
                    lastScanAt = 0L;
                    overlay.clearDetection();
                    searchButton.setEnabled(true);
                    searchButton.setText("עצור");
                    queryInput.setEnabled(false);
                    statusText.setText("מחפש \"" + q + "\"... העבר את המצלמה בחדר.");
                });
            } catch (Throwable t) {
                main.post(() -> {
                    searchButton.setEnabled(true);
                    statusText.setText("לא הצלחתי להכין את החיפוש.");
                });
            }
        });
    }

    private void stopSearch() {
        scanning = false;
        misses = 0;
        wasFound = false;
        if (overlay != null) overlay.clearDetection();
        if (searchButton != null) {
            searchButton.setEnabled(true);
            searchButton.setText("חפש");
        }
        if (queryInput != null) queryInput.setEnabled(true);
        if (statusText != null) statusText.setText("החיפוש נעצר.");
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        if (!scanning || engine == null || data == null) return;
        if (inferenceRunning.get()) return;

        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_INTERVAL_MS) return;
        lastScanAt = now;

        if (!inferenceRunning.compareAndSet(false, true)) return;

        final byte[] frame = Arrays.copyOf(data, data.length);
        final int w = previewWidth;
        final int h = previewHeight;
        final int rotation = frameRotation;
        final String q = currentQuery;

        aiExecutor.execute(() -> {
            try {
                OwlVitEngine.Detection detection = engine.detect(frame, w, h, rotation);
                main.post(() -> handleDetection(detection, q));
            } catch (Throwable t) {
                main.post(() -> {
                    if (scanning) {
                        statusText.setText("ה-AI נתקל בשגיאה בפריים הזה. ממשיך לחפש...");
                    }
                });
            } finally {
                inferenceRunning.set(false);
            }
        });
    }

    private void handleDetection(OwlVitEngine.Detection detection, String query) {
        if (!scanning || !query.equals(currentQuery)) return;

        if (detection != null && detection.score >= DETECTION_THRESHOLD) {
            misses = 0;
            overlay.update(detection, query);
            statusText.setText(
                    "מצאתי התאמה ל-\"" + query + "\"  " +
                            Math.round(detection.score * 100f) + "%"
            );

            if (!wasFound) {
                playFoundFeedback();
            }
            wasFound = true;
        } else {
            misses++;
            if (misses >= 2) {
                overlay.clearDetection();
                wasFound = false;
            }
            statusText.setText("מחפש \"" + query + "\"...");
        }
    }

    private void playFoundFeedback() {
        try {
            toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
        } catch (Throwable ignored) {}

        try {
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null) {
                if (Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(VibrationEffect.createOneShot(
                            90, VibrationEffect.DEFAULT_AMPLITUDE
                    ));
                } else {
                    vibrator.vibrate(90);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void toggleFlash() {
        if (camera == null) {
            statusText.setText("המצלמה עדיין לא מוכנה.");
            return;
        }

        try {
            Camera.Parameters params = camera.getParameters();
            List<String> modes = params.getSupportedFlashModes();
            if (modes == null || !modes.contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                statusText.setText("אין פנס זמין במכשיר הזה.");
                return;
            }

            flashOn = !flashOn;
            params.setFlashMode(
                    flashOn ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF
            );
            camera.setParameters(params);
            flashButton.setText(flashOn ? "כבה פנס" : "פנס");
        } catch (Throwable t) {
            flashOn = false;
            flashButton.setText("פנס");
        }
    }

    private void openCamera() {
        if (camera != null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        if (cameraView.getHolder().getSurface() == null) return;

        try {
            camera = Camera.open();

            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(0, info);

            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            int degrees;
            if (rotation == Surface.ROTATION_90) degrees = 90;
            else if (rotation == Surface.ROTATION_180) degrees = 180;
            else if (rotation == Surface.ROTATION_270) degrees = 270;
            else degrees = 0;

            frameRotation = (info.orientation - degrees + 360) % 360;
            camera.setDisplayOrientation(frameRotation);

            Camera.Parameters params = camera.getParameters();
            params.setPreviewFormat(ImageFormat.NV21);

            Camera.Size best = choosePreviewSize(params.getSupportedPreviewSizes());
            if (best != null) {
                params.setPreviewSize(best.width, best.height);
                previewWidth = best.width;
                previewHeight = best.height;
            }

            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes != null
                    && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }

            camera.setParameters(params);
            Camera.Size actual = camera.getParameters().getPreviewSize();
            previewWidth = actual.width;
            previewHeight = actual.height;

            camera.setPreviewDisplay(cameraView.getHolder());
            camera.setPreviewCallback(this);
            camera.startPreview();

            if (engine != null) {
                statusText.setText("AI מוכן. כתוב מה אתה מחפש ולחץ חפש.");
            } else if (!modelManager.isReady()) {
                statusText.setText("המצלמה מוכנה. הורד את מודל ה-AI כדי להתחיל.");
            } else {
                statusText.setText("המצלמה מוכנה. ה-AI עדיין נטען.");
            }
        } catch (Throwable t) {
            releaseCamera();
            statusText.setText("לא הצלחתי לפתוח את המצלמה.");
        }
    }

    private Camera.Size choosePreviewSize(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) return null;

        Camera.Size best = sizes.get(0);
        long target = 640L * 480L;
        long bestDiff = Long.MAX_VALUE;

        for (Camera.Size size : sizes) {
            long area = (long) size.width * size.height;
            long diff = Math.abs(area - target);

            float ratio = size.width / (float) size.height;
            if (ratio < 1.2f || ratio > 2.0f) diff += target;

            if (diff < bestDiff) {
                bestDiff = diff;
                best = size;
            }
        }
        return best;
    }

    private void releaseCamera() {
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
                camera.stopPreview();
            } catch (Throwable ignored) {}
            try {
                camera.release();
            } catch (Throwable ignored) {}
            camera = null;
        }
        flashOn = false;
        if (flashButton != null) flashButton.setText("פנס");
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        openCamera();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (camera != null) {
            try {
                camera.stopPreview();
                camera.setPreviewDisplay(holder);
                camera.startPreview();
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (cameraView != null && cameraView.getHolder().getSurface() != null) {
            openCamera();
        }
    }

    @Override
    protected void onPause() {
        stopSearch();
        releaseCamera();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        releaseCamera();
        aiExecutor.execute(() -> {
            try {
                if (engine != null) engine.close();
            } catch (Throwable ignored) {}
        });
        aiExecutor.shutdown();
        try {
            toneGenerator.release();
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            statusText.setText("בלי הרשאת מצלמה אי אפשר לחפש חפצים.");
        }
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setTextDirection(View.TEXT_DIRECTION_RTL);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private Button primaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackground(round(BLUE, 12, 0, Color.TRANSPARENT));
        return button;
    }

    private Button secondaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(TEXT);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setBackground(round(Color.WHITE, 12, 1, BORDER));
        return button;
    }

    private void addSpace(LinearLayout parent, int widthDp) {
        Space space = new Space(this);
        parent.addView(space, new LinearLayout.LayoutParams(dp(widthDp), 1));
    }

    private GradientDrawable round(int color, int radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
