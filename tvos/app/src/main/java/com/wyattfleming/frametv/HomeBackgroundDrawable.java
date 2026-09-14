package com.wyattfleming.frametv;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * A deliberately still, low-overhead background for the native home. Geometry and shaders are
 * rebuilt only when bounds change, so drawing does not create work during remote navigation.
 */
public final class HomeBackgroundDrawable extends Drawable {
    private static final int SKY_TOP = 0xFF101A2D;
    private static final int SKY_MIDDLE = 0xFF223957;
    private static final int SKY_BOTTOM = 0xFF5D5265;
    private static final int REAR_TERRAIN = 0x4D111A24;
    private static final int FRONT_TERRAIN = 0x8F0D151D;

    private final Paint skyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frontPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path rearTerrain = new Path();
    private final Path frontTerrain = new Path();

    private Shader skyShader;
    private Shader glowShader;
    private int alpha = 255;

    public HomeBackgroundDrawable() {
        rearPaint.setColor(REAR_TERRAIN);
        frontPaint.setColor(FRONT_TERRAIN);
    }

    @Override public void draw(Canvas canvas) {
        if (skyShader == null) return;
        skyPaint.setShader(skyShader);
        canvas.drawRect(getBounds(), skyPaint);
        skyPaint.setShader(null);

        if (glowShader != null) {
            glowPaint.setShader(glowShader);
            canvas.drawRect(getBounds(), glowPaint);
            glowPaint.setShader(null);
        }
        canvas.drawPath(rearTerrain, rearPaint);
        canvas.drawPath(frontTerrain, frontPaint);
    }

    @Override protected void onBoundsChange(Rect bounds) {
        int width = bounds.width();
        int height = bounds.height();
        if (width <= 0 || height <= 0) {
            skyShader = null;
            glowShader = null;
            return;
        }
        float left = bounds.left;
        float top = bounds.top;
        float right = bounds.right;
        float bottom = bounds.bottom;
        skyShader = new LinearGradient(left, top, right, bottom,
                new int[]{SKY_TOP, SKY_MIDDLE, SKY_BOTTOM}, null, Shader.TileMode.CLAMP);
        glowShader = new RadialGradient(left + width * 0.84f, top + height * 0.16f,
                Math.max(width, height) * 0.34f,
                new int[]{0x18DCEBFF, 0x08DCEBFF, Color.TRANSPARENT},
                new float[]{0f, 0.48f, 1f}, Shader.TileMode.CLAMP);
        buildTerrain(left, top, right, bottom, width, height);
    }

    private void buildTerrain(float left, float top, float right, float bottom, int width, int height) {
        rearTerrain.reset();
        rearTerrain.moveTo(left, top + height * 0.79f);
        rearTerrain.lineTo(left + width * 0.13f, top + height * 0.71f);
        rearTerrain.lineTo(left + width * 0.28f, top + height * 0.76f);
        rearTerrain.lineTo(left + width * 0.43f, top + height * 0.67f);
        rearTerrain.lineTo(left + width * 0.59f, top + height * 0.74f);
        rearTerrain.lineTo(left + width * 0.73f, top + height * 0.64f);
        rearTerrain.lineTo(left + width * 0.88f, top + height * 0.72f);
        rearTerrain.lineTo(right, top + height * 0.67f);
        rearTerrain.lineTo(right, bottom);
        rearTerrain.lineTo(left, bottom);
        rearTerrain.close();

        frontTerrain.reset();
        frontTerrain.moveTo(left, top + height * 0.88f);
        frontTerrain.lineTo(left + width * 0.15f, top + height * 0.79f);
        frontTerrain.lineTo(left + width * 0.31f, top + height * 0.85f);
        frontTerrain.lineTo(left + width * 0.47f, top + height * 0.76f);
        frontTerrain.lineTo(left + width * 0.64f, top + height * 0.84f);
        frontTerrain.lineTo(left + width * 0.80f, top + height * 0.75f);
        frontTerrain.lineTo(right, top + height * 0.82f);
        frontTerrain.lineTo(right, bottom);
        frontTerrain.lineTo(left, bottom);
        frontTerrain.close();
    }

    @Override public void setAlpha(int value) {
        alpha = value;
        skyPaint.setAlpha(value);
        glowPaint.setAlpha(value);
        rearPaint.setAlpha(scaledAlpha(0x4D, value));
        frontPaint.setAlpha(scaledAlpha(0x8F, value));
        invalidateSelf();
    }

    private static int scaledAlpha(int original, int requested) {
        return (original * requested + 127) / 255;
    }

    @Override public void setColorFilter(ColorFilter filter) {
        skyPaint.setColorFilter(filter);
        glowPaint.setColorFilter(filter);
        rearPaint.setColorFilter(filter);
        frontPaint.setColorFilter(filter);
        invalidateSelf();
    }

    @Override public int getOpacity() {
        return alpha == 255 ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT;
    }
}
