package com.findeye.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

@SuppressWarnings("deprecation")
public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private static final int CAMERA_PERMISSION = 20;
    private static final int BLUE = Color.rgb(25, 103, 210);
    private static final int TEXT = Color.rgb(32, 33, 36);
    private static final int MUTED = Color.rgb(95, 99, 104);
    private static final int BORDER = Color.rgb(218, 220, 224);
    private static final int SURFACE = Color.rgb(248, 249, 250);

    private final Handler main = new Handler(Looper.getMainLooper());
    private Camera camera;
    private SurfaceView cameraView;
    private FinderOverlay overlay;
    private EditText query;
    private TextView status;
    private TextView lastSeen;
    private TextView planPill;
    private Button cameraButton;
    private Button flashButton;
    private Button findButton;
    private boolean flashOn = false;
    private boolean pro = false;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(SURFACE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        prefs = getSharedPreferences("findeye", MODE_PRIVATE);
        pro = prefs.getBoolean("pro", false);
        setContentView(buildUi());
        updatePlan();
        updateLastSeen();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.WHITE);

        LinearLayout root = column();
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(18), dp(16), dp(18), dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(-1, -2));

        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBox = column();
        TextView title = text("FindEye", 25, TEXT, true);
        TextView subtitle = text("חיפוש חפצים בעזרת המצלמה", 13, MUTED, false);
        titleBox.addView(title);
        titleBox.addView(subtitle);
        top.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1));

        planPill = text("FREE", 12, BLUE, true);
        planPill.setGravity(Gravity.CENTER);
        planPill.setPadding(dp(12), dp(7), dp(12), dp(7));
        top.addView(planPill);
        root.addView(top);

        TextView intro = text("כתוב מה אתה מחפש, סרוק את החדר, ו-FindEye יסמן התאמות על המסך.", 15, TEXT, false);
        intro.setPadding(0, dp(18), 0, dp(12));
        root.addView(intro);

        FrameLayout cameraCard = new FrameLayout(this);
        cameraCard.setBackground(round(SURFACE, 18, 1, BORDER));
        cameraCard.setClipToOutline(true);
        cameraCard.setOutlineProvider(ViewOutlineProvider.BACKGROUND);

        cameraView = new SurfaceView(this);
        cameraView.getHolder().addCallback(this);
        cameraCard.addView(cameraView, new FrameLayout.LayoutParams(-1, dp(390)));

        overlay = new FinderOverlay(this);
        cameraCard.addView(overlay, new FrameLayout.LayoutParams(-1, dp(390)));

        TextView privacy = text("עיבוד מקומי בפרוטוטייפ", 11, TEXT, true);
        privacy.setPadding(dp(10), dp(7), dp(10), dp(7));
        privacy.setBackground(round(Color.argb(235, 255, 255, 255), 99, 0, Color.TRANSPARENT));
        FrameLayout.LayoutParams privacyParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        privacyParams.setMargins(0, dp(12), dp(12), 0);
        cameraCard.addView(privacy, privacyParams);

        root.addView(cameraCard, new LinearLayout.LayoutParams(-1, dp(390)));

        LinearLayout searchRow = row();
        searchRow.setPadding(0, dp(14), 0, 0);

        query = new EditText(this);
        query.setHint("למשל: מפתחות כחולים");
        query.setSingleLine(true);
        query.setTextColor(TEXT);
        query.setHintTextColor(Color.rgb(128, 134, 139));
        query.setTextSize(15);
        query.setPadding(dp(14), 0, dp(14), 0);
        query.setBackground(round(Color.WHITE, 12, 1, BORDER));
        searchRow.addView(query, new LinearLayout.LayoutParams(0, dp(52), 1));

        addSpace(searchRow, 8);

        findButton = primaryButton("חפש");
        findButton.setOnClickListener(v -> startSearch());
        searchRow.addView(findButton, new LinearLayout.LayoutParams(dp(92), dp(52)));
        root.addView(searchRow);

        LinearLayout examples = row();
        examples.setPadding(0, dp(10), 0, 0);
        addChip(examples, "מפתחות כחולים");
        addChip(examples, "טלפון ורוד");
        addChip(examples, "משקפיים");
        root.addView(examples);

        LinearLayout actions = row();
        actions.setPadding(0, dp(10), 0, 0);

        cameraButton = secondaryButton("פתח מצלמה");
        cameraButton.setOnClickListener(v -> ensureCamera());
        flashButton = secondaryButton("פנס");
        flashButton.setOnClickListener(v -> toggleFlash());
        Button historyButton = secondaryButton("נראה לאחרונה");
        historyButton.setOnClickListener(v -> showHistory());

        actions.addView(cameraButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        addSpace(actions, 7);
        actions.addView(flashButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        addSpace(actions, 7);
        actions.addView(historyButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        root.addView(actions);

        status = text("מוכן. פתח את המצלמה כדי להתחיל.", 13, MUTED, false);
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setBackground(round(SURFACE, 12, 0, Color.TRANSPARENT));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.setMargins(0, dp(12), 0, 0);
        root.addView(status, statusParams);

        TextView seenTitle = sectionTitle("נראה לאחרונה");
        root.addView(seenTitle);
        lastSeen = text("עדיין אין חיפושים שמורים.", 14, MUTED, false);
        lastSeen.setPadding(dp(15), dp(14), dp(15), dp(14));
        lastSeen.setBackground(round(Color.WHITE, 14, 1, BORDER));
        root.addView(lastSeen);

        TextView proTitle = sectionTitle("FindEye Pro");
        root.addView(proTitle);

        LinearLayout plan = column();
        plan.setPadding(dp(16), dp(16), dp(16), dp(16));
        plan.setBackground(round(SURFACE, 16, 1, BORDER));

        TextView price = text("₪9.90 לחודש", 21, TEXT, true);
        TextView planText = text("חינם: 10 חיפושים ביום\nPro: חיפושים ללא הגבלה, היסטוריה מלאה וסריקה מהירה יותר.", 13, MUTED, false);
        planText.setLineSpacing(0, 1.25f);
        Button upgrade = primaryButton("נסה Pro בדמו");
        upgrade.setOnClickListener(v -> showPro());

        plan.addView(price);
        LinearLayout.LayoutParams planTextParams = new LinearLayout.LayoutParams(-1, -2);
        planTextParams.setMargins(0, dp(7), 0, dp(13));
        plan.addView(planText, planTextParams);
        plan.addView(upgrade, new LinearLayout.LayoutParams(-1, dp(48)));
        root.addView(plan);

        TextView note = text("גרסת בדיקה. המצלמה אמיתית, זיהוי ה-AI עדיין מדומה.", 11, MUTED, false);
        note.setPadding(0, dp(14), 0, 0);
        root.addView(note);

        return scroll;
    }

    private void ensureCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            return;
        }
        openCamera();
    }

    private void openCamera() {
        if (camera != null) return;
        try {
            camera = Camera.open();
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(cameraView.getHolder());
            camera.startPreview();
            cameraButton.setText("מצלמה פועלת");
            status.setText("המצלמה מוכנה. כתוב מה אתה מחפש.");
        } catch (Exception e) {
            releaseCamera();
            status.setText("לא הצלחתי לפתוח את המצלמה.");
        }
    }

    private void toggleFlash() {
        if (camera == null) {
            status.setText("פתח קודם את המצלמה.");
            return;
        }
        try {
            Camera.Parameters p = camera.getParameters();
            List<String> modes = p.getSupportedFlashModes();
            if (modes == null || !modes.contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                status.setText("הפנס לא זמין במצלמה הזאת.");
                return;
            }
            flashOn = !flashOn;
            p.setFlashMode(flashOn ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF);
            camera.setParameters(p);
            flashButton.setText(flashOn ? "כבה פנס" : "פנס");
        } catch (Exception e) {
            flashOn = false;
            status.setText("לא ניתן להפעיל את הפנס כרגע.");
        }
    }

    private void startSearch() {
        String q = query.getText().toString().trim();
        if (q.isEmpty()) {
            status.setText("כתוב קודם מה אתה מחפש.");
            return;
        }
        if (camera == null) {
            status.setText("פתח קודם את המצלמה.");
            return;
        }
        if (!pro && usageToday() >= 10) {
            showPro();
            return;
        }

        incrementUsage();
        overlay.clearTarget();
        findButton.setEnabled(false);
        status.setText("מחפש \"" + q + "\"... סריקה חסכונית פעילה.");

        main.postDelayed(() -> {
            findButton.setEnabled(true);
            if (Math.random() < 0.16) {
                status.setText("לא נמצאה התאמה מספיק טובה. נסה לסרוק אזור נוסף.");
                return;
            }

            overlay.showTarget(q);
            String when = DateFormat.getDateTimeInstance(
                    DateFormat.SHORT,
                    DateFormat.SHORT,
                    new Locale("he", "IL")
            ).format(new Date());

            prefs.edit().putString("last_item", q).putString("last_time", when).apply();
            updateLastSeen();
            status.setText("נמצאה התאמה ל-\"" + q + "\". היא מסומנת בכחול.");

            try {
                Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (vibrator != null) vibrator.vibrate(90);
            } catch (Exception ignored) {}
        }, 1300);
    }

    private int usageToday() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        if (!today.equals(prefs.getString("usage_day", ""))) return 0;
        return prefs.getInt("usage_count", 0);
    }

    private void incrementUsage() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        int count = today.equals(prefs.getString("usage_day", ""))
                ? prefs.getInt("usage_count", 0)
                : 0;
        prefs.edit().putString("usage_day", today).putInt("usage_count", count + 1).apply();
    }

    private void updateLastSeen() {
        if (lastSeen == null) return;
        String item = prefs.getString("last_item", "");
        String time = prefs.getString("last_time", "");
        lastSeen.setText(item.isEmpty() ? "עדיין אין חיפושים שמורים." : item + "\n" + time);
    }

    private void showHistory() {
        String item = prefs.getString("last_item", "");
        String time = prefs.getString("last_time", "");
        new AlertDialog.Builder(this)
                .setTitle("נראה לאחרונה")
                .setMessage(item.isEmpty() ? "עדיין אין חיפוש שמור." : item + "\n" + time)
                .setPositiveButton("סגור", null)
                .show();
    }

    private void showPro() {
        new AlertDialog.Builder(this)
                .setTitle("FindEye Pro")
                .setMessage("₪9.90 לחודש\n\nחיפושים ללא הגבלה\nהיסטוריה מלאה\nסריקה מהירה יותר\nללא פרסומות\n\nבדמו הזה לא מתבצע חיוב.")
                .setNegativeButton("לא עכשיו", null)
                .setPositiveButton("הפעל Pro בדמו", (dialog, which) -> {
                    pro = true;
                    prefs.edit().putBoolean("pro", true).apply();
                    updatePlan();
                    status.setText("Pro הופעל בדמו. אין מגבלת חיפושים.");
                })
                .show();
    }

    private void updatePlan() {
        if (planPill == null) return;
        planPill.setText(pro ? "PRO" : "FREE");
        planPill.setTextColor(pro ? Color.WHITE : BLUE);
        planPill.setBackground(round(pro ? BLUE : Color.rgb(232, 240, 254), 99, 0, Color.TRANSPARENT));
    }

    private void addChip(LinearLayout parent, String value) {
        TextView chip = text(value, 12, TEXT, false);
        chip.setPadding(dp(10), dp(8), dp(10), dp(8));
        chip.setBackground(round(SURFACE, 99, 1, BORDER));
        chip.setOnClickListener(v -> query.setText(value));
        parent.addView(chip);
        addSpace(parent, 6);
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private TextView sectionTitle(String value) {
        TextView t = text(value, 16, TEXT, true);
        t.setPadding(0, dp(22), 0, dp(9));
        return t;
    }

    private Button primaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setBackground(round(BLUE, 12, 0, Color.TRANSPARENT));
        return b;
    }

    private Button secondaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(TEXT);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setBackground(round(SURFACE, 12, 1, BORDER));
        return b;
    }

    private void addSpace(LinearLayout parent, int widthDp) {
        Space s = new Space(this);
        parent.addView(s, new LinearLayout.LayoutParams(dp(widthDp), 1));
    }

    private GradientDrawable round(int color, int radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(dp(strokeDp), strokeColor);
        return d;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void releaseCamera() {
        if (camera != null) {
            try {
                camera.stopPreview();
            } catch (Exception ignored) {}
            camera.release();
            camera = null;
        }
        flashOn = false;
        if (flashButton != null) flashButton.setText("פנס");
        if (cameraButton != null) cameraButton.setText("פתח מצלמה");
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {}

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (camera != null) {
            try {
                camera.stopPreview();
                camera.setPreviewDisplay(holder);
                camera.startPreview();
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCamera();
    }

    @Override
    protected void onPause() {
        releaseCamera();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            status.setText("צריך הרשאת מצלמה כדי לחפש חפצים.");
        }
    }

    public static class FinderOverlay extends View {
        private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tag = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Random random = new Random();
        private RectF rect;
        private String value = "";

        public FinderOverlay(Context context) {
            super(context);
            box.setColor(BLUE);
            box.setStyle(Paint.Style.STROKE);
            box.setStrokeWidth(6);
            tag.setColor(Color.argb(245, 255, 255, 255));
            label.setColor(TEXT);
            label.setTextSize(31);
            label.setTypeface(Typeface.DEFAULT_BOLD);
        }

        public void clearTarget() {
            rect = null;
            invalidate();
        }

        public void showTarget(String text) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            float bw = w * 0.43f;
            float bh = h * 0.20f;
            float left = 30 + random.nextFloat() * Math.max(1, w - bw - 60);
            float top = 95 + random.nextFloat() * Math.max(1, h - bh - 170);
            rect = new RectF(left, top, left + bw, top + bh);
            value = "מצאתי: " + text;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (rect == null) return;

            canvas.drawRoundRect(rect, 22, 22, box);
            float textWidth = label.measureText(value);
            float tagWidth = Math.min(textWidth + 34, getWidth() - 24);
            float right = Math.min(rect.right, getWidth() - 12);
            float left = Math.max(12, right - tagWidth);
            float top = Math.max(12, rect.top - 62);
            RectF tagRect = new RectF(left, top, right, top + 52);
            canvas.drawRoundRect(tagRect, 14, 14, tag);
            canvas.drawText(value, tagRect.left + 17, tagRect.top + 35, label);
        }
    }
}
