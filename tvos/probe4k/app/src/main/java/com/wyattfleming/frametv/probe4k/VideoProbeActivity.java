package com.wyattfleming.frametv.probe4k;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Decodes one frame from the bundled 4K HEVC sample to an opaque SurfaceView. It can demonstrate
 * a possible video-plane route only; it cannot establish native full-RGB photo rendering.
 */
public final class VideoProbeActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "Frame4KProbe";

    private final Object workLock = new Object();
    private final ExecutorService codecExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Frame4KProbe-VideoCodec");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Surface surface;
    private volatile boolean resumed;
    private volatile boolean destroyed;
    private volatile long generation;
    private boolean workerScheduled;
    private Work pending;
    // These are accessed by codecExecutor only and deliberately retained after the first render.
    private MediaCodec codec;
    private MediaExtractor extractor;
    private TextView overlay;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        Log.i(TAG, "video_lifecycle onCreate");
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        FrameLayout root = new FrameLayout(this);
        SurfaceView videoView = new SurfaceView(this);
        SurfaceHolder holder = videoView.getHolder();
        // The opaque layer is sized to the HEVC input. HWC/SF still determines the final route.
        holder.setFormat(PixelFormat.OPAQUE);
        holder.setFixedSize(VideoProbeSpec.WIDTH, VideoProbeSpec.HEIGHT);
        holder.addCallback(this);
        root.addView(videoView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        overlay = new TextView(this);
        overlay.setTextColor(Color.rgb(238, 238, 238));
        overlay.setTextSize(16);
        overlay.setTypeface(android.graphics.Typeface.MONOSPACE);
        overlay.setPadding(18, 12, 18, 12);
        overlay.setBackgroundColor(0xB0000000);
        root.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START));
        setContentView(root);
        updateOverlay("Awaiting opaque 4K HEVC surface");
    }

    @Override
    public void onResume() {
        super.onResume();
        resumed = true;
        Log.i(TAG, "video_lifecycle onResume");
        enqueue(true, false, false);
    }

    @Override
    public void onPause() {
        resumed = false;
        Log.i(TAG, "video_lifecycle onPause");
        enqueue(false, true, false);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        resumed = false;
        enqueue(false, true, true);
        codecExecutor.shutdown();
        try {
            if (!codecExecutor.awaitTermination(750, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "video_codec_shutdown_timeout; interrupting worker");
                codecExecutor.shutdownNow();
                codecExecutor.awaitTermination(250, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            codecExecutor.shutdownNow();
        }
        Log.i(TAG, "video_lifecycle onDestroy");
        super.onDestroy();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surface = holder.getSurface();
        Log.i(TAG, "video_surface_created valid=" + (surface != null && surface.isValid()));
        enqueue(true, false, false);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "video_surface_changed format=" + format + " callback=" + width + "x" + height
                + " requested=" + VideoProbeSpec.WIDTH + "x" + VideoProbeSpec.HEIGHT);
        enqueue(true, false, false);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surface = null;
        Log.i(TAG, "video_surface_destroyed");
        enqueue(false, true, false);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            overlay.setVisibility(overlay.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void enqueue(boolean decode, boolean release, boolean allowDestroyed) {
        if (destroyed && !allowDestroyed) return;
        synchronized (workLock) {
            generation++;
            boolean priorDecode = pending != null && pending.decode;
            boolean priorRelease = pending != null && pending.release;
            pending = new Work(generation, decode || priorDecode, release || priorRelease);
            if (workerScheduled) return;
            workerScheduled = true;
            try {
                codecExecutor.execute(this::drainWork);
            } catch (RejectedExecutionException rejected) {
                workerScheduled = false;
                Log.w(TAG, "video_worker_rejected during shutdown", rejected);
            }
        }
    }

    private void drainWork() {
        while (true) {
            Work work;
            synchronized (workLock) {
                work = pending;
                pending = null;
                if (work == null) {
                    workerScheduled = false;
                    return;
                }
            }
            if (work.release) releaseCodecOnWorker();
            if (work.decode && resumed && !destroyed) decodeOneFrame(work.generation);
        }
    }

    private void decodeOneFrame(long workGeneration) {
        if (isCancelled(workGeneration)) return;
        Surface outputSurface = surface;
        if (outputSurface == null || !outputSurface.isValid()) {
            reportStatus("Opaque video surface unavailable");
            return;
        }
        releaseCodecOnWorker();
        try (AssetFileDescriptor asset = getResources().openRawResourceFd(R.raw.probe4k_hevc)) {
            if (asset == null) throw new IOException("4K HEVC resource has no file descriptor");
            extractor = new MediaExtractor();
            extractor.setDataSource(asset.getFileDescriptor(), asset.getStartOffset(), asset.getLength());
            MediaFormat inputFormat = select4kVideoTrack(extractor);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            String decoderName = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(inputFormat);
            if (decoderName == null) throw new IllegalStateException("no decoder for " + mime);
            logDecoderCapabilities(decoderName, mime);
            codec = MediaCodec.createByCodecName(decoderName);
            codec.configure(inputFormat, outputSurface, null, 0);
            codec.start();
            Log.i(TAG, "video_codec_started decoder=" + decoderName + " input="
                    + VideoProbeSpec.WIDTH + "x" + VideoProbeSpec.HEIGHT);
            feedAndRenderFirstFrame(workGeneration);
        } catch (IOException | RuntimeException error) {
            releaseCodecOnWorker();
            reportFailure("4K HEVC decode rejected", error);
        }
    }

    private MediaFormat select4kVideoTrack(MediaExtractor mediaExtractor) {
        for (int track = 0; track < mediaExtractor.getTrackCount(); track++) {
            MediaFormat format = mediaExtractor.getTrackFormat(track);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                int width = format.getInteger(MediaFormat.KEY_WIDTH);
                int height = format.getInteger(MediaFormat.KEY_HEIGHT);
                VideoProbeSpec.requireExpectedDimensions(width, height);
                mediaExtractor.selectTrack(track);
                Log.i(TAG, "video_input mime=" + mime + " dimensions=" + width + "x" + height);
                return format;
            }
        }
        throw new IllegalStateException("4K HEVC asset has no video track");
    }

    private void logDecoderCapabilities(String decoderName, String mime) {
        MediaCodecInfo info = null;
        for (MediaCodecInfo candidate : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (candidate.getName().equals(decoderName)) {
                info = candidate;
                break;
            }
        }
        if (info == null) throw new IllegalStateException("decoder vanished from codec list: " + decoderName);
        MediaCodecInfo.VideoCapabilities video = info.getCapabilitiesForType(mime).getVideoCapabilities();
        Log.i(TAG, "video_decoder name=" + decoderName + " supports_3840x2160="
                + video.isSizeSupported(VideoProbeSpec.WIDTH, VideoProbeSpec.HEIGHT));
    }

    private void feedAndRenderFirstFrame(long workGeneration) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputEos = false;
        int samplesQueued = 0;
        for (int attempt = 0; attempt < VideoProbeSpec.MAX_DEQUEUE_ATTEMPTS && !isCancelled(workGeneration); attempt++) {
            if (!inputEos) {
                int inputIndex = codec.dequeueInputBuffer(VideoProbeSpec.DEQUEUE_TIMEOUT_US);
                if (inputIndex >= 0) {
                    ByteBuffer input = codec.getInputBuffer(inputIndex);
                    if (input == null) throw new IllegalStateException("decoder returned null input buffer");
                    if (samplesQueued >= VideoProbeSpec.MAX_INPUT_SAMPLES) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputEos = true;
                    } else {
                        input.clear();
                        int bytes = extractor.readSampleData(input, 0);
                        if (bytes < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, bytes, extractor.getSampleTime(), 0);
                            samplesQueued++;
                            extractor.advance();
                        }
                    }
                }
            }
            int outputIndex = codec.dequeueOutputBuffer(info, VideoProbeSpec.DEQUEUE_TIMEOUT_US);
            if (outputIndex >= 0) {
                boolean render = info.size > 0 && !isCancelled(workGeneration);
                if (render) logOutputFormat(codec.getOutputFormat());
                codec.releaseOutputBuffer(outputIndex, render);
                if (render) {
                    Log.i(TAG, "video_rendered_first_frame samples_queued=" + samplesQueued);
                    reportStatus("4K HEVC frame rendered; inspect HWC/SF and stripe detail");
                    return; // Keep codec/extractor retained until pause or Back; do not loop or feed more input.
                }
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                logOutputFormat(codec.getOutputFormat());
            }
        }
        if (!isCancelled(workGeneration)) {
            reportFailure("bounded decode produced no renderable frame", new IllegalStateException(
                    "attempts=" + VideoProbeSpec.MAX_DEQUEUE_ATTEMPTS + " samples=" + samplesQueued));
        }
    }

    private void logOutputFormat(MediaFormat format) {
        int width = format.getInteger(MediaFormat.KEY_WIDTH);
        int height = format.getInteger(MediaFormat.KEY_HEIGHT);
        Integer leftValue = format.containsKey(MediaFormat.KEY_CROP_LEFT) ? format.getInteger(MediaFormat.KEY_CROP_LEFT) : null;
        Integer topValue = format.containsKey(MediaFormat.KEY_CROP_TOP) ? format.getInteger(MediaFormat.KEY_CROP_TOP) : null;
        Integer rightValue = format.containsKey(MediaFormat.KEY_CROP_RIGHT) ? format.getInteger(MediaFormat.KEY_CROP_RIGHT) : null;
        Integer bottomValue = format.containsKey(MediaFormat.KEY_CROP_BOTTOM) ? format.getInteger(MediaFormat.KEY_CROP_BOTTOM) : null;
        int left = VideoProbeSpec.cropStartOrDefault(leftValue, width);
        int top = VideoProbeSpec.cropStartOrDefault(topValue, height);
        int right = VideoProbeSpec.cropEndOrDefault(rightValue, width);
        int bottom = VideoProbeSpec.cropEndOrDefault(bottomValue, height);
        Log.i(TAG, "video_output dimensions=" + width + "x" + height + " crop="
                + left + ',' + top + '-' + right + ',' + bottom + " visible="
                + VideoProbeSpec.cropSize(left, right) + 'x' + VideoProbeSpec.cropSize(top, bottom));
    }

    private void releaseCodecOnWorker() {
        if (codec != null) {
            try {
                codec.stop();
            } catch (RuntimeException error) {
                Log.w(TAG, "video_codec_stop_failed", error);
            }
            try {
                codec.release();
            } catch (RuntimeException error) {
                Log.w(TAG, "video_codec_release_failed", error);
            } finally {
                codec = null;
            }
        }
        if (extractor != null) {
            extractor.release();
            extractor = null;
        }
    }

    private boolean isCancelled(long workGeneration) {
        return destroyed || !resumed || generation != workGeneration || Thread.currentThread().isInterrupted();
    }

    private void reportFailure(String message, Exception error) {
        Log.e(TAG, "failure " + message, error);
        reportStatus("ERROR: " + message + " (see Frame4KProbe log)");
    }

    private void reportStatus(String status) {
        runOnUiThread(() -> updateOverlay(status));
    }

    private void updateOverlay(String status) {
        overlay.setText("OPAQUE 4K HEVC VIDEO EXPERIMENT — unproven\n" + status + "\n"
                + "Menu: hide overlay  Back: exit\n"
                + "A video-plane result cannot prove native full-RGB photo rendering.");
    }

    private static final class Work {
        final long generation;
        final boolean decode;
        final boolean release;

        Work(long generation, boolean decode, boolean release) {
            this.generation = generation;
            this.decode = decode;
            this.release = release;
        }
    }
}
