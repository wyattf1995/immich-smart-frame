package com.wyattfleming.frametv.probe4k;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageWriter;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One-frame YV12 experiment. It reports accepted/rejected formats and actual buffers, but never
 * claims direct hardware composition: SurfaceFlinger and a physical stripe inspection decide that.
 */
public final class YuvProbeActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "Frame4KProbe";
    private static final int MAX_QUEUED_IMAGES = 1;

    private final Object requestLock = new Object();
    private final Object writerLock = new Object();
    private final ExecutorService writerExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Frame4KProbe-YuvWriter");
        thread.setDaemon(true);
        return thread;
    });
    private SurfaceHolder holder;
    private Surface surface;
    private ImageWriter writer;
    private TextView overlay;
    private ProbeMode mode = ProbeMode.HD;
    private boolean alternatePattern;
    private volatile boolean resumed;
    private volatile long generation;
    private volatile boolean destroyed;
    private Request pending;
    private boolean workerScheduled;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        Log.i(TAG, "yuv_lifecycle onCreate");
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        FrameLayout root = new FrameLayout(this);
        SurfaceView yuvView = new SurfaceView(this);
        holder = yuvView.getHolder();
        holder.setFormat(ImageFormat.YV12);
        holder.setFixedSize(mode.width, mode.height);
        holder.addCallback(this);
        root.addView(yuvView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        overlay = new TextView(this);
        overlay.setTextColor(Color.rgb(238, 238, 238));
        overlay.setTextSize(16);
        overlay.setTypeface(android.graphics.Typeface.MONOSPACE);
        overlay.setPadding(18, 12, 18, 12);
        overlay.setBackgroundColor(0xB0000000);
        overlay.setGravity(Gravity.START);
        root.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START));
        setContentView(root);
        updateOverlay("YV12 request pending " + mode.width + "x" + mode.height);
    }

    @Override
    public void onResume() {
        super.onResume();
        resumed = true;
        Log.i(TAG, "yuv_lifecycle onResume");
        requestFrame();
    }

    @Override
    public void onPause() {
        resumed = false;
        synchronized (requestLock) {
            generation++;
            pending = null;
        }
        Log.i(TAG, "yuv_lifecycle onPause generation=" + generation);
        closeWriter();
        super.onPause();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        resumed = false;
        synchronized (requestLock) {
            generation++;
            pending = null;
        }
        closeWriter();
        writerExecutor.shutdown();
        Log.i(TAG, "yuv_lifecycle onDestroy");
        super.onDestroy();
    }

    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder) {
        surface = surfaceHolder.getSurface();
        Log.i(TAG, "yuv_surface_created valid=" + (surface != null && surface.isValid()));
        createWriterIfPossible();
        requestFrame();
    }

    @Override
    public void surfaceChanged(SurfaceHolder surfaceHolder, int format, int width, int height) {
        Log.i(TAG, "yuv_surface_changed format=" + format + " callback=" + width + "x" + height
                + " requested=" + mode.width + "x" + mode.height);
        createWriterIfPossible();
        requestFrame();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
        Log.i(TAG, "yuv_surface_destroyed");
        surface = null;
        synchronized (requestLock) {
            generation++;
            pending = null;
        }
        closeWriter();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) {
            return super.dispatchKeyEvent(event);
        }
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            mode = ProbeMode.fromRightSelection(key == KeyEvent.KEYCODE_DPAD_RIGHT);
            holder.setFixedSize(mode.width, mode.height);
            Log.i(TAG, "yuv_requested_buffer=" + mode.width + "x" + mode.height);
            updateOverlay("YV12 resize requested " + mode.width + "x" + mode.height);
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
            alternatePattern = !alternatePattern;
            requestFrame();
            return true;
        }
        if (key == KeyEvent.KEYCODE_MENU) {
            overlay.setVisibility(overlay.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            return true;
        }
        if (key == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void createWriterIfPossible() {
        Surface candidate = surface;
        if (candidate == null || !candidate.isValid()) return;
        synchronized (writerLock) {
            closeWriterLocked();
            try {
                // API 23 two-argument form: the Surface's YV12 format is the contract.
                writer = ImageWriter.newInstance(candidate, MAX_QUEUED_IMAGES);
                int negotiatedFormat = writer.getFormat();
                Log.i(TAG, "yuv_writer_created max_images=" + MAX_QUEUED_IMAGES
                        + " negotiated_format=" + negotiatedFormat);
                if (negotiatedFormat != ImageFormat.YV12) {
                    throw new IllegalStateException("ImageWriter negotiated format " + negotiatedFormat);
                }
            } catch (RuntimeException error) {
                closeWriterLocked();
                writer = null;
                reportFailure("ImageWriter rejected YV12 surface", error);
            }
        }
    }

    private void requestFrame() {
        if (destroyed) return;
        synchronized (requestLock) {
            generation++;
            pending = new Request(generation, mode, alternatePattern);
            if (workerScheduled) return;
            workerScheduled = true;
            writerExecutor.execute(this::drainLatestFrame);
        }
    }

    private void drainLatestFrame() {
        while (true) {
            Request request;
            synchronized (requestLock) {
                request = pending;
                pending = null;
                if (request == null) {
                    workerScheduled = false;
                    return;
                }
            }
            if (!resumed || request.generation != generation) continue;
            queueSingleFrame(request);
        }
    }

    private void queueSingleFrame(Request request) {
        synchronized (writerLock) {
            if (!resumed || request.generation != generation || writer == null) {
                reportStatus("YV12 surface unavailable; no frame queued");
                return;
            }
            Image image = null;
            try {
                image = writer.dequeueInputImage();
                if (image.getFormat() != ImageFormat.YV12) {
                    throw new IllegalStateException("unexpected ImageWriter format " + image.getFormat());
                }
                int width = image.getWidth();
                int height = image.getHeight();
                YuvPlaneFiller.validateImageShape(width, height);
                if (width != request.mode.width || height != request.mode.height) {
                    throw new IllegalStateException("actual YV12 dimensions " + width + "x" + height
                            + " differ from requested " + request.mode.width + "x" + request.mode.height);
                }
                Image.Plane[] planes = image.getPlanes();
                if (planes.length != 3) {
                    throw new IllegalStateException("expected 3 YV12 planes, got " + planes.length);
                }
                fillImage(planes, width, height, request.alternatePattern);
                if (!resumed || request.generation != generation) {
                    image.close();
                    return;
                }
                Log.i(TAG, "yuv_image format=" + image.getFormat() + " actual=" + width + "x" + height
                        + " plane_strides=" + describePlanes(planes));
                writer.queueInputImage(image);
                image = null;
                Log.i(TAG, "yuv_queued generation=" + request.generation + " requested="
                        + request.mode.width + "x" + request.mode.height);
                reportStatus("YV12 queued " + width + "x" + height + " — panel detail still unverified");
            } catch (RuntimeException error) {
                reportFailure("YV12 frame rejected", error);
            } finally {
                if (image != null) image.close();
            }
        }
    }

    private static void fillImage(Image.Plane[] planes, int width, int height, boolean alternate) {
        YuvPlaneFiller.fillLuma(planes[0].getBuffer(), width, height, planes[0].getRowStride(),
                planes[0].getPixelStride(), alternate);
        int chromaWidth = (width + 1) / 2;
        int chromaHeight = (height + 1) / 2;
        for (int index = 1; index < 3; index++) {
            YuvPlaneFiller.fillNeutralChroma(planes[index].getBuffer(), chromaWidth, chromaHeight,
                    planes[index].getRowStride(), planes[index].getPixelStride());
        }
    }

    private static String describePlanes(Image.Plane[] planes) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < planes.length; index++) {
            if (index > 0) result.append(',');
            result.append(planes[index].getRowStride()).append('/').append(planes[index].getPixelStride());
        }
        return result.toString();
    }

    private void closeWriter() {
        synchronized (writerLock) { closeWriterLocked(); }
    }

    private void closeWriterLocked() {
        if (writer != null) {
            writer.close();
            writer = null;
            Log.i(TAG, "yuv_writer_closed");
        }
    }

    private void reportFailure(String message, RuntimeException error) {
        Log.e(TAG, "failure " + message, error);
        reportStatus("ERROR: " + message + " (see Frame4KProbe log)");
    }

    private void reportStatus(String status) {
        runOnUiThread(() -> updateOverlay(status));
    }

    private void updateOverlay(String status) {
        overlay.setText("YV12 COMPOSITION EXPERIMENT — unproven\n" + status + "\n"
                + "Left: 1080p  Right: 4K  Center: pattern  Menu: overlay  Back: exit\n"
                + "A queued YUV frame does not prove direct hardware composition; inspect SF and stripes.");
    }

    private static final class Request {
        final long generation;
        final ProbeMode mode;
        final boolean alternatePattern;

        Request(long generation, ProbeMode mode, boolean alternatePattern) {
            this.generation = generation;
            this.mode = mode;
            this.alternatePattern = alternatePattern;
        }
    }
}
