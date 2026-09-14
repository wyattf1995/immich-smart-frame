package com.wyattfleming.frametv;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Static atmospheric backdrop for the full-screen weather page. It never schedules animation. */
public final class WeatherSceneDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path terrain = new Path();
    private Integer code;
    private Boolean isDay;

    public void setCondition(Integer value, Boolean day) { code = value; isDay = day; invalidateSelf(); }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        int[] sky = colors();
        paint.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom, sky, null, Shader.TileMode.CLAMP));
        canvas.drawRect(b, paint);
        paint.setShader(null);
        float w = b.width(), h = b.height();
        if (code != null && code == 0 && Boolean.TRUE.equals(isDay)) {
            paint.setColor(0x45FFF0A8);
            canvas.drawCircle(b.left + w * .82f, b.top + h * .16f, w * .18f, paint);
        } else if (code != null && code == 0 && Boolean.FALSE.equals(isDay)) {
            paint.setColor(0x80F9E6A5);
            canvas.drawCircle(b.left + w * .82f, b.top + h * .16f, w * .038f, paint);
        }
        terrain.reset();
        terrain.moveTo(b.left, b.bottom);
        terrain.lineTo(b.left, b.top + h * .72f);
        terrain.cubicTo(b.left + w * .17f, b.top + h * .60f, b.left + w * .35f, b.top + h * .84f, b.left + w * .54f, b.top + h * .70f);
        terrain.cubicTo(b.left + w * .72f, b.top + h * .58f, b.left + w * .85f, b.top + h * .78f, b.right, b.top + h * .65f);
        terrain.lineTo(b.right, b.bottom); terrain.close();
        paint.setColor(0x7020342A); canvas.drawPath(terrain, paint);
        terrain.reset(); terrain.moveTo(b.left, b.bottom); terrain.lineTo(b.left, b.top + h * .87f);
        terrain.cubicTo(b.left + w * .25f, b.top + h * .77f, b.left + w * .54f, b.top + h * 1.02f, b.right, b.top + h * .82f);
        terrain.lineTo(b.right, b.bottom); terrain.close();
        paint.setColor(0xA0102019); canvas.drawPath(terrain, paint);
    }

    private int[] colors() {
        if (code != null && (code >= 71 && code <= 77 || code == 85 || code == 86)) return new int[]{0xFF25384A, 0xFF718597, 0xFFB9C7D1};
        if (code != null && (code >= 51 && code <= 67 || code >= 80)) return new int[]{0xFF172A35, 0xFF46656B, 0xFF768273};
        if (code != null && (code == 45 || code == 48 || code == 3)) return new int[]{0xFF26394A, 0xFF647A83, 0xFF8E9187};
        if (Boolean.FALSE.equals(isDay)) return new int[]{0xFF10182E, 0xFF293D66, 0xFF5A5269};
        return new int[]{0xFF175A69, 0xFF479C88, 0xFFC1B46E};
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
}
