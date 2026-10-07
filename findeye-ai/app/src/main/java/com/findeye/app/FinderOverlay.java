package com.findeye.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

public final class FinderOverlay extends View {
    private static final float MODEL_SIZE = 768f;

    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBackground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private RectF box;
    private String text = "";

    public FinderOverlay(Context context) {
        super(context);

        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(dp(4));
        outline.setColor(Color.rgb(211, 47, 47));

        labelBackground.setStyle(Paint.Style.FILL);
        labelBackground.setColor(Color.rgb(211, 47, 47));

        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(dp(13));
        labelPaint.setFakeBoldText(true);
    }

    public void update(OwlVitEngine.Detection detection, String query) {
        RectF next = new RectF(
                detection.left,
                detection.top,
                detection.right,
                detection.bottom
        );

        if (box == null) {
            box = next;
        } else {
            float a = 0.55f;
            box.left = box.left * a + next.left * (1f - a);
            box.top = box.top * a + next.top * (1f - a);
            box.right = box.right * a + next.right * (1f - a);
            box.bottom = box.bottom * a + next.bottom * (1f - a);
        }

        text = query + "  " + Math.round(detection.score * 100f) + "%";
        postInvalidate();
    }

    public void clearDetection() {
        box = null;
        text = "";
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (box == null) return;

        float sx = getWidth() / MODEL_SIZE;
        float sy = getHeight() / MODEL_SIZE;

        RectF drawBox = new RectF(
                box.left * sx,
                box.top * sy,
                box.right * sx,
                box.bottom * sy
        );

        float radius = dp(10);
        canvas.drawRoundRect(drawBox, radius, radius, outline);

        float paddingX = dp(9);
        float tagHeight = dp(30);
        float tagWidth = Math.min(
                labelPaint.measureText(text) + paddingX * 2,
                Math.max(dp(100), getWidth() - dp(16))
        );

        float tagLeft = Math.max(dp(8), Math.min(drawBox.left, getWidth() - tagWidth - dp(8)));
        float tagTop = drawBox.top - tagHeight - dp(6);
        if (tagTop < dp(8)) tagTop = drawBox.top + dp(6);

        RectF tag = new RectF(tagLeft, tagTop, tagLeft + tagWidth, tagTop + tagHeight);
        canvas.drawRoundRect(tag, dp(8), dp(8), labelBackground);
        canvas.drawText(text, tag.left + paddingX, tag.top + dp(20), labelPaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
