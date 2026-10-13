package com.wyattfleming.frametv.probe4k;

import android.content.Context;
import android.opengl.EGL14;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.view.SurfaceHolder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** A no-animation GLSurfaceView. It redraws only for lifecycle, resize, or user input. */
final class ProbeSurfaceView extends GLSurfaceView {
    private static final String TAG = "Frame4KProbe";
    private final RendererImpl renderer;

    ProbeSurfaceView(Context context, RendererStatus status) {
        super(context);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(false);
        renderer = new RendererImpl(status);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        getHolder().setFixedSize(ProbeMode.HD.width, ProbeMode.HD.height);
    }

    void setProbeMode(ProbeMode mode, boolean alternatePattern) {
        // Publish intent before the asynchronous resize; diagnostics label it as the latest
        // request, separately from the measured EGL buffer which may still be transitioning.
        renderer.setRequestedMode(mode);
        queueEvent(() -> renderer.setAlternatePattern(alternatePattern));
        getHolder().setFixedSize(mode.width, mode.height);
        requestRender();
    }

    void setAlternatePattern(boolean alternatePattern) {
        queueEvent(() -> renderer.setAlternatePattern(alternatePattern));
        requestRender();
    }

    interface RendererStatus { void onStatus(String status); }

    private static final class RendererImpl implements GLSurfaceView.Renderer {
        private static final String VERTEX = "attribute vec2 aPosition;\n"
                + "void main() { gl_Position = vec4(aPosition, 0.0, 1.0); }";
        // 3840-pixel coordinates require highp: mediump can quantize 1-pixel stripes away.
        private static final String FRAGMENT = "precision highp float;\n"
                + "uniform vec2 uSurface;\n"
                + "uniform float uAlternate;\n"
                + "float stripe(float coordinate, float width) {\n"
                + "  return mod(floor(coordinate + uAlternate), width * 2.0) < width ? 0.68 : 0.20;\n"
                + "}\n"
                + "void main() {\n"
                + "  vec2 p = gl_FragCoord.xy;\n"
                + "  float row = floor(p.y / (uSurface.y / 4.0));\n"
                + "  float column = floor(p.x / (uSurface.x / 4.0));\n"
                + "  float v = 0.42;\n"
                + "  if (row < 1.0) { v = column < 2.0 ? stripe(p.x, 1.0) : stripe(p.y, 1.0); }\n"
                + "  else if (row < 2.0) { v = column < 2.0 ? stripe(p.x, 2.0) : stripe(p.y, 2.0); }\n"
                + "  else if (row < 3.0) { v = column < 2.0 ? stripe(p.x, 4.0) : stripe(p.y, 4.0); }\n"
                + "  else {\n"
                + "    if (column < 1.0) { v = 0.42; }\n"
                + "    else {\n"
                + "      float cell = column < 2.0 ? 1.0 : (column < 3.0 ? 2.0 : 4.0);\n"
                + "      v = mod(floor((p.x + uAlternate) / cell) + floor((p.y + uAlternate) / cell), 2.0) < 1.0 ? 0.68 : 0.20;\n"
                + "    }\n"
                + "  }\n"
                + "  gl_FragColor = vec4(vec3(v), 1.0);\n"
                + "}";
        private static final float[] QUAD = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};

        private final RendererStatus status;
        private final FloatBuffer vertices = ByteBuffer.allocateDirect(QUAD.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        private volatile ProbeMode requestedMode = ProbeMode.HD;
        private boolean alternatePattern;
        private int program;
        private int positionLocation;
        private int surfaceLocation;
        private int alternateLocation;
        private int width;
        private int height;
        private boolean failed;

        RendererImpl(RendererStatus status) {
            this.status = status;
            vertices.put(QUAD).position(0);
        }

        void setRequestedMode(ProbeMode mode) { requestedMode = mode; }

        void setAlternatePattern(boolean alternate) { alternatePattern = alternate; }

        @Override
        public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 unused,
                                     javax.microedition.khronos.egl.EGLConfig config) {
            failed = false;
            Log.i(TAG, "surface_created renderer=" + GLES20.glGetString(GLES20.GL_RENDERER)
                    + " vendor=" + GLES20.glGetString(GLES20.GL_VENDOR));
            int[] maxTexture = new int[1];
            int[] maxViewport = new int[2];
            int[] maxRenderbuffer = new int[1];
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTexture, 0);
            GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, maxViewport, 0);
            GLES20.glGetIntegerv(GLES20.GL_MAX_RENDERBUFFER_SIZE, maxRenderbuffer, 0);
            Log.i(TAG, "gl_limits max_texture=" + maxTexture[0] + " max_viewport="
                    + maxViewport[0] + "x" + maxViewport[1] + " max_renderbuffer=" + maxRenderbuffer[0]);
            program = buildProgram();
            if (program == 0) return;
            positionLocation = GLES20.glGetAttribLocation(program, "aPosition");
            surfaceLocation = GLES20.glGetUniformLocation(program, "uSurface");
            alternateLocation = GLES20.glGetUniformLocation(program, "uAlternate");
            if (positionLocation < 0 || surfaceLocation < 0 || alternateLocation < 0) {
                fail("missing shader location");
            }
        }

        @Override
        public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 unused, int newWidth, int newHeight) {
            width = newWidth;
            height = newHeight;
            GLES20.glViewport(0, 0, width, height);
            int[] viewport = new int[4];
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0);
            int[] eglWidth = new int[1];
            int[] eglHeight = new int[1];
            boolean widthOk = EGL14.eglQuerySurface(EGL14.eglGetCurrentDisplay(), EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW),
                    EGL14.EGL_WIDTH, eglWidth, 0);
            boolean heightOk = EGL14.eglQuerySurface(EGL14.eglGetCurrentDisplay(), EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW),
                    EGL14.EGL_HEIGHT, eglHeight, 0);
            int eglError = EGL14.eglGetError();
            Log.i(TAG, "surface_changed latest_requested=" + requestedMode.width + "x" + requestedMode.height
                    + " callback=" + width + "x" + height + " egl_surface=" + eglWidth[0] + "x" + eglHeight[0]
                    + " gl_viewport=" + viewport[2] + "x" + viewport[3]
                    + " query_ok=" + widthOk + "/" + heightOk + " egl_error=0x" + Integer.toHexString(eglError));
            if (failed) {
                return;
            }
            if (!widthOk || !heightOk || eglError != EGL14.EGL_SUCCESS) {
                fail("EGL surface query failed 0x" + Integer.toHexString(eglError));
            } else {
                status.onStatus("Latest request " + requestedMode.displayName + " " + requestedMode.width + "x" + requestedMode.height
                        + " | EGL " + eglWidth[0] + "x" + eglHeight[0]);
            }
        }

        @Override
        public void onDrawFrame(javax.microedition.khronos.opengles.GL10 unused) {
            if (failed || program == 0 || width <= 0 || height <= 0) return;
            GLES20.glUseProgram(program);
            GLES20.glEnableVertexAttribArray(positionLocation);
            GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, vertices);
            GLES20.glUniform2f(surfaceLocation, width, height);
            GLES20.glUniform1f(alternateLocation, alternatePattern ? 1f : 0f);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionLocation);
            int glError = GLES20.glGetError();
            if (glError != GLES20.GL_NO_ERROR) {
                fail("GL draw failed 0x" + Integer.toHexString(glError));
            } else {
                Log.i(TAG, "frame_drawn latest_requested=" + requestedMode.width + "x" + requestedMode.height
                        + " surface=" + width + "x" + height + " alternate=" + alternatePattern);
            }
        }

        // GLSurfaceView owns the EGL context; destroying it releases its GL objects.
        // Manual deletion during pause would require a separate resume rebuild path.

        private int buildProgram() {
            int vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX);
            int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT);
            if (vertex == 0 || fragment == 0) return 0;
            int candidate = GLES20.glCreateProgram();
            GLES20.glAttachShader(candidate, vertex);
            GLES20.glAttachShader(candidate, fragment);
            GLES20.glLinkProgram(candidate);
            int[] linked = new int[1];
            GLES20.glGetProgramiv(candidate, GLES20.GL_LINK_STATUS, linked, 0);
            GLES20.glDeleteShader(vertex);
            GLES20.glDeleteShader(fragment);
            if (linked[0] == 0) {
                fail("shader link: " + GLES20.glGetProgramInfoLog(candidate));
                GLES20.glDeleteProgram(candidate);
                return 0;
            }
            return candidate;
        }

        private int compileShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                fail("shader compile: " + GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                return 0;
            }
            return shader;
        }

        private void fail(String message) {
            failed = true;
            Log.e(TAG, "failure " + message);
            status.onStatus("ERROR: " + message);
        }
    }
}
