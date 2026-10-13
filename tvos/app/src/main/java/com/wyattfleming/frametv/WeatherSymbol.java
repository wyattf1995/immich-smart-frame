package com.wyattfleming.frametv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Small, static weather pictograms; no bitmaps, animation, or font-glyph dependencies. */
final class WeatherSymbol extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Integer code;
    private final Boolean day;

    WeatherSymbol(Context context, Integer code, Boolean day) {
        super(context);
        this.code = code;
        this.day = day;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.scale(getWidth() / 32f, getHeight() / 32f);
        drawSymbol(canvas, paint, path, code, day);
        canvas.restore();
    }

    static void drawSymbol(Canvas canvas, Paint paint, Path path, Integer code, Boolean day) {
        paint.setStyle(Paint.Style.FILL);
        if (code == null || code < 0) {
            paint.setColor(0xFFADB7C6);
            canvas.drawRoundRect(11, 15, 21, 17, 1, 1, paint);
        } else if (code == 0 && Boolean.FALSE.equals(day)) {
            paint.setColor(0xFFFFE78A);
            path.reset();
            path.moveTo(22, 5);
            path.cubicTo(8, 0, 2, 23, 18, 27);
            path.cubicTo(24, 28, 28, 24, 29, 20);
            path.cubicTo(17, 24, 12, 11, 22, 5);
            canvas.drawPath(path, paint);
        } else if (code == 0 && day == null) {
            paint.setColor(0xFFE5E9F0);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2);
            canvas.drawCircle(16, 16, 8, paint);
        } else if (code <= 2) {
            if (code == 0 || Boolean.TRUE.equals(day)) sun(canvas, paint, code == 0 ? 16 : 12, code == 0 ? 16 : 12);
            if (code > 0) cloud(canvas, paint);
        } else if (code == 3 || code == 45 || code == 48 || code >= 51 && code <= 99) {
            cloud(canvas, paint);
            paint.setColor(0xFF91C9FF);
            paint.setStrokeWidth(2);
            if (code == 45 || code == 48) {
                canvas.drawLine(5, 25, 27, 25, paint);
                canvas.drawLine(9, 29, 23, 29, paint);
            } else if (code >= 71 && code <= 77 || code == 85 || code == 86) {
                for (int x = 9; x <= 23; x += 7) canvas.drawCircle(x, 27, 1.8f, paint);
            } else if (code >= 95) {
                paint.setColor(0xFFFFDA59);
                path.reset(); path.moveTo(17, 19); path.lineTo(12, 26); path.lineTo(17, 25);
                path.lineTo(14, 32); path.lineTo(23, 22); path.lineTo(18, 23); path.close();
                canvas.drawPath(path, paint);
            } else if (code >= 51) {
                for (int x = 9; x <= 23; x += 7) canvas.drawLine(x, 24, x - 2, 29, paint);
            }
        }
    }

    private static void sun(Canvas canvas, Paint paint, float x, float y) {
        paint.setColor(0xFFFFDA59);
        canvas.drawCircle(x, y, 6, paint);
        paint.setStrokeWidth(2);
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            canvas.drawLine(x + (float)Math.cos(angle) * 8, y + (float)Math.sin(angle) * 8,
                    x + (float)Math.cos(angle) * 11, y + (float)Math.sin(angle) * 11, paint);
        }
    }

    private static void cloud(Canvas canvas, Paint paint) {
        paint.setColor(0xFFE5E9F0);
        canvas.drawCircle(9, 16, 5, paint);
        canvas.drawCircle(16, 12, 7, paint);
        canvas.drawCircle(24, 16, 5, paint);
        canvas.drawRoundRect(5, 15, 28, 22, 3, 3, paint);
    }
}
