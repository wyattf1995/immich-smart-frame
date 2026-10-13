package com.wyattfleming.frametv;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** One still, bounded bitmap. Remote navigation never decodes or animates the background. */
public final class HomeBackgroundDrawable extends Drawable {
    private final Bitmap forest;
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint shadePaint = new Paint();
    private final RectF destination = new RectF();
    private int alpha = 255;

    public HomeBackgroundDrawable(Resources resources) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        // The packaged photograph is fixed at 1672 × 941, about 3 MiB when decoded.
        forest = BitmapFactory.decodeResource(resources, R.drawable.forest_floor, options);
    }

    @Override protected void onBoundsChange(Rect bounds) {
        if (forest == null || bounds.isEmpty()) return;
        float scale = Math.max((float) bounds.width() / forest.getWidth(),
                (float) bounds.height() / forest.getHeight());
        float width = forest.getWidth() * scale;
        float height = forest.getHeight() * scale;
        float left = bounds.exactCenterX() - width / 2;
        float top = bounds.exactCenterY() - height / 2;
        destination.set(left, top, left + width, top + height);
        shadePaint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                new int[]{0x6B06100A, 0x1606100A, 0x3806100A, 0xB306100A},
                new float[]{0, 0.34f, 0.64f, 1}, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas canvas) {
        if (forest == null) { canvas.drawColor(TvUi.BG); return; }
        canvas.drawBitmap(forest, null, destination, imagePaint);
        canvas.drawRect(getBounds(), shadePaint);
    }

    @Override public void setAlpha(int value) {
        alpha = value;
        imagePaint.setAlpha(value);
        shadePaint.setAlpha(value);
        invalidateSelf();
    }
    @Override public void setColorFilter(ColorFilter filter) {
        imagePaint.setColorFilter(filter);
        invalidateSelf();
    }
    @Override public int getOpacity() {
        return alpha == 255 ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT;
    }
}
