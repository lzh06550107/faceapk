package com.punch.app.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;


public class FaceFrameView extends View {

    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scanPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    
    private float scanProgress = 0f;
    
    private boolean scanDown = true;
    
    private final Runnable animRunnable = this::tick;

    public FaceFrameView(Context ctx) {
        super(ctx);
        init();
    }

    public FaceFrameView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        init();
    }

    
    private void init() {
        cornerPaint.setColor(Color.parseColor("#00E5FF"));
        cornerPaint.setStrokeWidth(4f);
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeCap(Paint.Cap.ROUND);

        scanPaint.setColor(Color.parseColor("#8000E5FF"));
        scanPaint.setStrokeWidth(2f);
        scanPaint.setStyle(Paint.Style.STROKE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float halfW = w * 0.32f;
        float halfH = halfW * 1.25f;
        float cornerLen = 24f;

        float left = cx - halfW;
        float top = cy - halfH;
        float right = cx + halfW;
        float bottom = cy + halfH;

        drawCorner(canvas, left, top, cornerLen, cornerLen);
        drawCorner(canvas, right, top, -cornerLen, cornerLen);
        drawCorner(canvas, left, bottom, cornerLen, -cornerLen);
        drawCorner(canvas, right, bottom, -cornerLen, -cornerLen);

        float scanY = top + (bottom - top) * scanProgress;
        canvas.drawLine(left + 4, scanY, right - 4, scanY, scanPaint);
    }

    
    private void drawCorner(Canvas c, float x, float y, float dx, float dy) {
        c.drawLine(x, y, x + dx, y, cornerPaint);
        c.drawLine(x, y, x, y + dy, cornerPaint);
    }

    
    private void tick() {
        float step = 0.025f;
        if (scanDown) {
            scanProgress += step;
            if (scanProgress >= 1f) {
                scanProgress = 1f;
                scanDown = false;
            }
        } else {
            scanProgress -= step;
            if (scanProgress <= 0f) {
                scanProgress = 0f;
                scanDown = true;
            }
        }
        invalidate();
        postDelayed(animRunnable, 30);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(animRunnable);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(animRunnable);
    }
}
