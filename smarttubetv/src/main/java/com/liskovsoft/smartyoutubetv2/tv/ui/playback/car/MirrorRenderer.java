package com.liskovsoft.smartyoutubetv2.tv.ui.playback.car;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Car build: one video decoder, picture on two screens. The player renders into {@link #getInputSurface()},
 * every frame is then drawn (GPU copy) onto each output surface: the main screen and the second screen.
 */
public final class MirrorRenderer {
    private static final String VS =
            "attribute vec4 aPos; attribute vec4 aTex; uniform mat4 uTex; varying vec2 vTex;" +
            "void main(){ gl_Position = aPos; vTex = (uTex * aTex).xy; }";
    private static final String FS =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float; varying vec2 vTex; uniform samplerExternalOES sTex;" +
            "void main(){ gl_FragColor = texture2D(sTex, vTex); }";
    private static final float[] QUAD = { // x, y, u, v
            -1, -1, 0, 0,   1, -1, 1, 0,   -1, 1, 0, 1,   1, 1, 1, 1 };

    private final HandlerThread mThread = new HandlerThread("car-mirror");
    private final Handler mHandler;
    private EGLDisplay mDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig mConfig;
    private EGLSurface mPbuffer = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture mTexture;
    private Surface mInput;
    private int mProgram, mTexId, aPos, aTex, uTex;
    private final float[] mMatrix = new float[16];
    private FloatBuffer mQuad;
    private final Map<Surface, EGLSurface> mTargets = new LinkedHashMap<>();
    private volatile boolean mReleased;

    public MirrorRenderer() {
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        final Object lock = new Object();
        final boolean[] done = {false};
        mHandler.post(() -> {
            try {
                init();
            } catch (Throwable t) {
                mInput = null;
            }
            synchronized (lock) {
                done[0] = true;
                lock.notifyAll();
            }
        });
        synchronized (lock) {
            while (!done[0]) {
                try {
                    lock.wait(2000);
                    break;
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    /** Null when OpenGL couldn't start (then fall back to a single screen). */
    public Surface getInputSurface() {
        return mInput;
    }

    public void addTarget(Surface s) {
        addTarget(s, 0);
    }

    private void addTarget(Surface s, int attempt) {
        if (s == null || !s.isValid()) {
            return;
        }
        mHandler.postDelayed(() -> {
            if (mReleased || mTargets.containsKey(s) || !s.isValid()) {
                return;
            }
            EGLSurface es = EGL14.EGL_NO_SURFACE;
            try {
                es = EGL14.eglCreateWindowSurface(mDisplay, mConfig, s, new int[]{EGL14.EGL_NONE}, 0);
            } catch (Throwable ignored) {
            }
            if (es != null && es != EGL14.EGL_NO_SURFACE) {
                mTargets.put(s, es);
            } else if (attempt < 15) {
                // the decoder may still be attached to this surface for a moment
                addTarget(s, attempt + 1);
            }
        }, attempt == 0 ? 0 : 200);
    }

    public void removeTarget(Surface s) {
        // synchronous: the caller's surface is about to be destroyed
        runAndWait(() -> {
            EGLSurface es = mTargets.remove(s);
            if (es != null) {
                EGL14.eglMakeCurrent(mDisplay, mPbuffer, mPbuffer, mContext);
                EGL14.eglDestroySurface(mDisplay, es);
            }
        });
    }

    public void release() {
        if (mReleased) {
            return;
        }
        runAndWait(() -> {
            mReleased = true;
            for (EGLSurface es : mTargets.values()) {
                EGL14.eglDestroySurface(mDisplay, es);
            }
            mTargets.clear();
            if (mTexture != null) {
                mTexture.release();
            }
            if (mInput != null) {
                mInput.release();
            }
            if (mDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(mDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (mPbuffer != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(mDisplay, mPbuffer);
                }
                EGL14.eglDestroyContext(mDisplay, mContext);
                EGL14.eglTerminate(mDisplay);
            }
        });
        mThread.quitSafely();
    }

    private void runAndWait(Runnable r) {
        if (Thread.currentThread() == mThread) {
            r.run();
            return;
        }
        final Object lock = new Object();
        final boolean[] done = {false};
        boolean posted = mHandler.post(() -> {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
            synchronized (lock) {
                done[0] = true;
                lock.notifyAll();
            }
        });
        if (!posted) {
            return;
        }
        synchronized (lock) {
            if (!done[0]) {
                try {
                    lock.wait(1000);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private void init() {
        mDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] ver = new int[2];
        EGL14.eglInitialize(mDisplay, ver, 0, ver, 1);
        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        EGL14.eglChooseConfig(mDisplay, attribs, 0, configs, 0, 1, num, 0);
        mConfig = configs[0];
        mContext = EGL14.eglCreateContext(mDisplay, mConfig, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        mPbuffer = EGL14.eglCreatePbufferSurface(mDisplay, mConfig,
                new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        EGL14.eglMakeCurrent(mDisplay, mPbuffer, mPbuffer, mContext);

        mProgram = program(VS, FS);
        aPos = GLES20.glGetAttribLocation(mProgram, "aPos");
        aTex = GLES20.glGetAttribLocation(mProgram, "aTex");
        uTex = GLES20.glGetUniformLocation(mProgram, "uTex");
        mQuad = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mQuad.put(QUAD).position(0);

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        mTexId = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTexId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        mTexture = new SurfaceTexture(mTexId);
        mTexture.setOnFrameAvailableListener(st -> drawFrame(), mHandler);
        mInput = new Surface(mTexture);
    }

    private void drawFrame() {
        if (mReleased) {
            return;
        }
        try {
            EGL14.eglMakeCurrent(mDisplay, mPbuffer, mPbuffer, mContext);
            mTexture.updateTexImage();
            mTexture.getTransformMatrix(mMatrix);
            for (Map.Entry<Surface, EGLSurface> e : mTargets.entrySet()) {
                EGLSurface es = e.getValue();
                if (!e.getKey().isValid() || !EGL14.eglMakeCurrent(mDisplay, es, es, mContext)) {
                    continue;
                }
                int[] w = new int[1], h = new int[1];
                EGL14.eglQuerySurface(mDisplay, es, EGL14.EGL_WIDTH, w, 0);
                EGL14.eglQuerySurface(mDisplay, es, EGL14.EGL_HEIGHT, h, 0);
                GLES20.glViewport(0, 0, w[0], h[0]); // both surfaces already have the video's aspect ratio
                GLES20.glClearColor(0, 0, 0, 1);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                GLES20.glUseProgram(mProgram);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTexId);
                GLES20.glUniformMatrix4fv(uTex, 1, false, mMatrix, 0);
                mQuad.position(0);
                GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, mQuad);
                GLES20.glEnableVertexAttribArray(aPos);
                mQuad.position(2);
                GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, mQuad);
                GLES20.glEnableVertexAttribArray(aTex);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                EGL14.eglSwapBuffers(mDisplay, es);
            }
        } catch (Throwable ignored) {
        }
    }

    private static int program(String vs, String fs) {
        int v = shader(GLES20.GL_VERTEX_SHADER, vs);
        int f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }
}
