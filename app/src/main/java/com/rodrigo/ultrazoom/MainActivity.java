package com.rodrigo.ultrazoom;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Toast;

import com.rodrigo.ultrazoom.diagnostic.AfStateMachine;
import com.rodrigo.ultrazoom.diagnostic.DiagnosticSessionStore;
import com.rodrigo.ultrazoom.diagnostic.GeometryMath;
import com.rodrigo.ultrazoom.diagnostic.MemoryPolicy;
import com.rodrigo.ultrazoom.diagnostic.ScaleEstimator;
import com.rodrigo.ultrazoom.diagnostic.ZoomConfidenceEngine;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

public class MainActivity extends Activity {
    private static final int REQ = 44;
    private UltraCameraView camera;

    @Override public void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        immersive();
        camera = new UltraCameraView(this);
        setContentView(camera);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ);
        } else {
            camera.start();
        }
    }

    private void immersive() {
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override protected void onResume() {
        super.onResume();
        immersive();
        if (camera != null && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) camera.start();
    }

    @Override protected void onPause() {
        if (camera != null) camera.stop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (camera != null) camera.shutdown();
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r, p, g);
        if (r == REQ && g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) camera.start();
        else Toast.makeText(this, "A câmera é necessária para o UltraZoom.", Toast.LENGTH_LONG).show();
    }
}

class UltraCameraView extends ViewGroup {
    private enum Mode { AUTO, PRO, ULTRA, MAX, NIGHT }
    private static final String BUILD_ID = "UZ-124-AUTODIAGNOSTIC";
    private static final int ZOOM_PROBE_FRAMES = 4;
    private static final int ZOOM_UNRELIABLE_FRAMES = 12;
    private static final int WHITE_92 = 0xEBFFFFFF;
    private static final int WHITE_70 = 0xB3FFFFFF;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextureView preview;
    private CameraDevice device;
    private CameraCaptureSession session;
    private Surface previewSurface;
    private CameraCharacteristics chars;
    private String cameraId;
    private String manualCameraId;
    private String mainCameraId;
    private String wideCameraId;
    private String physicalWideId;
    private String activePhysicalId;
    private CameraCharacteristics deviceChars;
    private Size jpegSize;
    private Size previewSize;
    private Rect sensor;
    private float maxHardware = 1f;
    private float minHardware = 1f;
    private float zoom = 1f;
    private boolean flashAvailable;
    private boolean lowLightBoostSupported;
    private boolean wideSupported;
    private int backCameraCount;
    private String cameraDiagnostics = "—";
    private boolean showInfo;
    private boolean showModeMenu;
    private int flashMode = 0;
    private ImageReader reader;
    private ImageReader miniReader;
    private HandlerThread cameraThread;
    private HandlerThread workThread;
    private Handler cameraHandler;
    private Handler workHandler;
    private Executor cameraExecutor;
    private Mode mode = Mode.AUTO;
    private volatile String status = "INICIANDO CÂMERA…";
    private float focusX = -1f, focusY = -1f;
    private long focusUntil;
    private float pinchStart = 1f, pinchDistance;
    private boolean pinchWideActive;
    private int targetFrames;
    private volatile int completedCaptures;
    private volatile boolean busy;
    private boolean finalizeScheduled;
    private final List<Bitmap> frames = Collections.synchronizedList(new ArrayList<Bitmap>());
    private Bitmap miniBitmap;
    private long lastMiniCapture;
    private boolean miniCapturePending;
    private boolean miniInSession;
    private Rect focusRegion;
    private final AfStateMachine afStateMachine = AfStateMachine.create();
    private int afSequenceId = 0;
    private final UiLayout ui = new UiLayout();
    private boolean layoutValid = true;
    private String layoutDiagnostic = "OK";
    private String cameraSelectionReason = "—";
    private String publicCameraDiagnostic = "—";
    private String detailedDiagnostics = "—";
    private String outputDiagnostics = "—";
    private String orientationDiagnostics = "—";
    private String stabilizationDiagnostics = "—";
    private String focusDiagnostics = "—";
    private String capabilitiesDiagnostics = "—";
    private String physicalIdsDiagnostics = "—";
    private String zoomDiagnostics = "—";
    private String previewSelectionDiagnostic = "—";
    private Size largestJpegSize;
    private boolean aeZoomLockedByGesture;
    private long aeUnlockAt;
    private static final long AE_ZOOM_UNLOCK_DELAY_MS = 260L;
    private long lastAeResultTime;
    private long lastExposureTimeNs;
    private long lastFrameDurationNs;
    private int lastSensitivity = -1;
    private int lastAeState = -1;
    private boolean aeLockSupported;
    private int lastZoomDx;
    private int lastZoomDy;
    private volatile String imageEngineDiagnostics = "FUSÃO OFF • 1 FRAME";
    private final List<CameraRecord> publicCameraRecords = new ArrayList<CameraRecord>();
    private boolean showCameraMenu;
    private float lastRequestedZoom = 1f;
    private float lastResultZoom = 1f;
    private Rect lastResultCrop;
    private String lastResultPhysicalId = "—";
    private String lastFocalResult = "—";
    private String streamGeometryDiagnostics = "—";
    private float lastGeometricZoom = 1f;
    private enum ZoomConfidence { UNKNOWN, PROBING, TRUSTED_RATIO, TRUSTED_CROP_ONLY, UNRELIABLE }
    private ZoomConfidence zoomConfidence = ZoomConfidence.UNKNOWN;
    private int zoomProbeFrames;
    private int ratioConsistentFrames;
    private int cropConsistentFrames;
    private int contradictionFrames;
    private float confidenceRequestZoom = 1f;

    private final DiagnosticSessionStore diagnosticStore = new DiagnosticSessionStore();
    private boolean diagnosticPending;
    private float diagnosticPendingZoom = 1f;
    private float diagnosticLastSampleZoom = 1f;
    private byte[] diagnosticLastGray;
    private static final int MAX_SAMPLES_PER_LEVEL = DiagnosticSessionStore.MAX_SAMPLES_PER_LEVEL;
    private static final int MAX_TOTAL_DIAGNOSTIC_SAMPLES = DiagnosticSessionStore.MAX_TOTAL_SAMPLES;
    private static final float MIN_OVERLAP_RATIO = DiagnosticSessionStore.MIN_OVERLAP_RATIO;
    private static final long DIAGNOSTIC_DELAY_MS = 350L;

    private static final String[] MODES = {"AUTO", "PRO", "ULTRA", "MAX", "NIGHT"};
    private static final String[] DESCS = {
            "Equilíbrio automático", "Controle manual e estabilidade", "Processamento multi-frame", "Máxima ampliação disponível", "Visão noturna • baixa luz + múltiplos frames"
    };

    private static class UiLayout {
        final RectF mini = new RectF();
        final RectF zoomArea = new RectF();
        final RectF flash = new RectF();
        final RectF shutter = new RectF();
        final RectF mode = new RectF();
        final RectF info = new RectF();
        final RectF logExport = new RectF();
        final RectF camera = new RectF();
        final RectF[] lens = new RectF[]{new RectF(), new RectF(), new RectF(), new RectF(), new RectF()};
        float scale = 1f;
        float lensGap = 0f;
        float lensWidth = 0f;

        void compute(int w, int h) {
            scale = Math.max(0.88f, Math.min(1.24f, w / 900f));
            mini.set(w - 132 * scale, 138 * scale, w - 28 * scale, 282 * scale);
            float y = 202 * scale;
            lensGap = 8 * scale;
            lensWidth = (w - 56 * scale - 4 * lensGap) / 5f;
            for (int i = 0; i < lens.length; i++) {
                float x = 28 * scale + i * (lensWidth + lensGap);
                lens[i].set(x, y, x + lensWidth, y + 58 * scale);
            }
            float zy = h - 358 * scale;
            zoomArea.set(28 * scale, zy - 8 * scale, w - 28 * scale, zy + 128 * scale);
            float cy = h - 118 * scale;
            flash.set(28 * scale, cy - 50 * scale, 136 * scale, cy + 50 * scale);
            shutter.set(w / 2f - 62 * scale, cy - 62 * scale, w / 2f + 62 * scale, cy + 62 * scale);
            mode.set(w - 136 * scale, cy - 50 * scale, w - 28 * scale, cy + 50 * scale);
            camera.set(w - 168 * scale, h - 62 * scale, w - 92 * scale, h - 4 * scale);
            info.set(w - 84 * scale, h - 62 * scale, w - 18 * scale, h - 4 * scale);
            logExport.set(150 * scale, h - 62 * scale, 246 * scale, h - 4 * scale);
        }
    }

    UltraCameraView(Context c) {
        super(c);
        setWillNotDraw(false);
        setBackgroundColor(Color.BLACK);
        text.setTypeface(Typeface.create("sans", Typeface.BOLD));
        startThreads();
        loadDiagnosticState();
        preview = new TextureView(c);
        preview.setOpaque(true);
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { open(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { transform(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { close(); return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {
                captureMiniIfNeeded();
                if (diagnosticLastGray == null && !diagnosticPending && pinchDistance == 0f) {
                    scheduleAutoDiagnostic(zoom);
                }
            }
        });
        addView(preview);
    }

    private void startThreads() {
        if (cameraThread == null) {
            cameraThread = new HandlerThread("UZ-Camera");
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
            cameraExecutor = command -> cameraHandler.post(command);
        }
        if (workThread == null) {
            workThread = new HandlerThread("UZ-Work");
            workThread.start();
            workHandler = new Handler(workThread.getLooper());
        }
    }

    private void updateStatusOnUi(final String newStatus) {
        status = newStatus;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            postInvalidate();
        } else {
            post(this::postInvalidate);
        }
    }

    @Override protected void onMeasure(int ws, int hs) {
        int w = Math.max(1, MeasureSpec.getSize(ws));
        int h = Math.max(1, MeasureSpec.getSize(hs));
        setMeasuredDimension(w, h);
        preview.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int top, int r, int b) {
        preview.layout(0, 0, getWidth(), getHeight());
        ui.compute(getWidth(), getHeight());
        validateLayout();
        transform();
    }

    @Override protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        drawHud(c);
    }

    private void rounded(Canvas c, float l, float top, float r, float b, float radius, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawRoundRect(l, top, r, b, radius, radius, p);
    }

    private void strokeRound(Canvas c, float l, float top, float r, float b, float radius, float stroke, int color) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setColor(color);
        c.drawRoundRect(l, top, r, b, radius, radius, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void txt(Canvas c, String s, float x, float y, float size, int color, boolean bold) {
        text.setTypeface(Typeface.create("sans", bold ? Typeface.BOLD : Typeface.NORMAL));
        text.setTextSize(size);
        text.setColor(color);
        c.drawText(s, x, y, text);
    }

    private void center(Canvas c, String s, float x, float y, float size, int color, boolean bold) {
        text.setTypeface(Typeface.create("sans", bold ? Typeface.BOLD : Typeface.NORMAL));
        text.setTextSize(size);
        text.setColor(color);
        c.drawText(s, x - text.measureText(s) / 2f, y, text);
    }

    private void drawHud(Canvas c) {
        int w = getWidth(), h = getHeight();
        ui.compute(w, h);
        float scale = ui.scale;

        rounded(c, 0, 0, w, 150 * scale, 0, 0xB5000000);
        rounded(c, 0, h - 360 * scale, w, h, 0, 0xD9070709);

        txt(c, "ULTRAZOOM", 28 * scale, 44 * scale, 30 * scale, Color.WHITE, true);
        txt(c, "CÂMERA COMPUTACIONAL", 30 * scale, 70 * scale, 15 * scale, WHITE_70, true);
        txt(c, "● " + status, 30 * scale, 96 * scale, 16 * scale, 0xD9FFFFFF, false);

        center(c, zoomHudString(), w - 105 * scale, 54 * scale, 50 * scale, Color.WHITE, true);
        center(c, MODES[mode.ordinal()], w - 105 * scale, 80 * scale, 16 * scale, WHITE_92, true);
        center(c, zoomDomain(), w - 105 * scale, 102 * scale, 13 * scale, WHITE_70, false);

        drawMiniMap(c, w, scale);
        drawLensPills(c, w, scale);
        drawZoomBar(c, w, h, scale);
        drawBottom(c, w, h, scale);

        if (focusUntil > System.currentTimeMillis()) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3 * scale);
            p.setColor(Color.WHITE);
            c.drawRoundRect(focusX - 34 * scale, focusY - 34 * scale, focusX + 34 * scale, focusY + 34 * scale, 8, 8, p);
            p.setStyle(Paint.Style.FILL);
            postInvalidateDelayed(80);
        }
        if (showModeMenu) drawModeSheet(c, scale);
        if (showInfo) drawInfo(c, scale);
        else if (showCameraMenu) drawCameraMenu(c, scale);
    }

    private String zoomString() {
        float clamped = Math.max(wideSupported ? 0.5f : 1.0f, Math.min(Math.max(1f, maxHardware), zoom));
        if (clamped < 10f) return String.format(Locale.US, "%.1f×", clamped);
        return String.format(Locale.US, "%.0f×", clamped);
    }

    private String zoomHudString() {
        String base = zoomString();
        return zoomConfidence == ZoomConfidence.UNKNOWN || zoomConfidence == ZoomConfidence.PROBING || zoomConfidence == ZoomConfidence.UNRELIABLE
                ? base + " (est.)" : base;
    }

    private String zoomConfidenceLabel() {
        switch (zoomConfidence) {
            case TRUSTED_RATIO: return "GEO CONFIRMADA • RATIO";
            case TRUSTED_CROP_ONLY: return "GEO CONFIRMADA • CROP";
            case PROBING: return "GEO PROBING";
            case UNRELIABLE: return "GEO NÃO CONFIÁVEL";
            default: return "GEO —";
        }
    }

    private ZoomConfidenceEngine.State mapLayerAState() {
        switch (zoomConfidence) {
            case TRUSTED_RATIO:
            case TRUSTED_CROP_ONLY:
                return ZoomConfidenceEngine.State.CONFIRMED;
            case PROBING:
                return ZoomConfidenceEngine.State.PARTIAL;
            case UNRELIABLE:
                return ZoomConfidenceEngine.State.FAIL;
            default:
                return ZoomConfidenceEngine.State.UNKNOWN;
        }
    }

    private String zoomDomain() {
        if (zoom < 0.99f && wideSupported) return "ULTRAWIDE REAL";
        return String.format(Locale.US, "CAMERA2 %.1f–%.1f×", Math.min(1f, minHardware), Math.max(1f, maxHardware));
    }

    private void drawMiniMap(Canvas c, int w, float s) {
        RectF box = ui.mini;
        rounded(c, box.left, box.top, box.right, box.bottom, 14 * s, 0xD0000000);
        strokeRound(c, box.left, box.top, box.right, box.bottom, 14 * s, 1.5f * s, 0xCCFFFFFF);
        RectF image = new RectF(box.left + 4 * s, box.top + 4 * s, box.right - 4 * s, box.bottom - 4 * s);
        if (miniBitmap != null && !miniBitmap.isRecycled()) {
            Rect src = new Rect(0, 0, miniBitmap.getWidth(), miniBitmap.getHeight());
            c.drawBitmap(miniBitmap, src, image, p);
        }
        RectF view = zoomViewRect(image.left, image.top, image.right, image.bottom);
        strokeRound(c, view.left, view.top, view.right, view.bottom, 3 * s, 2 * s, Color.WHITE);
        p.setColor(0xFFFFFFFF); p.setStrokeWidth(1.2f * s);
        c.drawLine(image.centerX() - 7 * s, image.centerY(), image.centerX() + 7 * s, image.centerY(), p);
        c.drawLine(image.centerX(), image.centerY() - 7 * s, image.centerX(), image.centerY() + 7 * s, p);
        txt(c, miniInSession ? "CAMPO REAL • " + (zoomConfidence == ZoomConfidence.TRUSTED_RATIO || zoomConfidence == ZoomConfidence.TRUSTED_CROP_ONLY ? zoomString() : "FOV NOMINAL") : "MAPA OFF", box.left + 9 * s, box.bottom + 17 * s, 10 * s, WHITE_92, true);
    }

    private RectF zoomViewRect(float l, float t, float r, float b) {
        float mapW = Math.max(1f, r - l);
        float mapH = Math.max(1f, b - t);
        float z = Math.max(0.5f, Math.min(Math.max(1f, maxHardware), zoom));
        if (z < 1f) return new RectF(l, t, r, b);

        Rect crop = effectiveMapCrop();
        if (crop == null || sensor == null) {
            float fraction = 1f / z;
            float rw = mapW * fraction, rh = mapH * fraction;
            float cx = (l + r) / 2f, cy = (t + b) / 2f;
            return new RectF(cx - rw / 2f, cy - rh / 2f, cx + rw / 2f, cy + rh / 2f);
        }
        float sx = mapW / Math.max(1f, sensor.width());
        float sy = mapH / Math.max(1f, sensor.height());
        return new RectF(l + crop.left * sx, t + crop.top * sy, l + crop.right * sx, t + crop.bottom * sy);
    }

    private Rect effectiveMapCrop() {
        if (sensor == null) return null;
        Rect crop = lastResultCrop;
        boolean full = crop == null || (crop.left == sensor.left && crop.top == sensor.top &&
                crop.right == sensor.right && crop.bottom == sensor.bottom);
        if (zoomConfidence == ZoomConfidence.UNKNOWN || zoomConfidence == ZoomConfidence.PROBING || zoomConfidence == ZoomConfidence.UNRELIABLE) {
            return crop == null ? new Rect(sensor) : new Rect(crop);
        }
        if (!full || Math.abs(zoom - 1f) < 0.01f) return crop == null ? new Rect(sensor) : new Rect(crop);
        if (zoomConfidence == ZoomConfidence.TRUSTED_CROP_ONLY || zoomConfidence == ZoomConfidence.TRUSTED_RATIO) {
            return cropForZoom(Math.max(1f, Math.min(Math.max(1f, maxHardware), zoom)));
        }
        return crop == null ? new Rect(sensor) : new Rect(crop);
    }

    private float[] lensTargets() {
        float maxZ = Math.max(1f, maxHardware);
        return new float[]{0.5f, 1.0f, Math.min(2.0f, maxZ), Math.min(6.0f, maxZ), maxZ};
    }

    private void drawLensPills(Canvas c, int w, float s) {
        float[] targets = lensTargets();
        String[] lens = new String[targets.length];
        lens[0] = "0,5×";
        for (int i = 1; i < targets.length; i++) {
            float v = targets[i];
            lens[i] = (Math.abs(v - Math.round(v)) < 0.05f)
                    ? String.format(Locale.US, "%d×", Math.round(v))
                    : String.format(Locale.US, "%.1f×", v).replace('.', ',');
        }
        for (int i = 0; i < lens.length; i++) {
            RectF r = ui.lens[i];
            boolean enabled = (i == 0) ? wideSupported : (targets[i] <= maxHardware + 0.01f);
            boolean active = enabled && Math.abs(zoom - targets[i]) < (targets[i] < 1.5f ? 0.15f : 0.45f);
            int bg = active ? 0xFFF7F7F7 : 0xB8141417;
            if (!enabled) bg = 0x66141417;
            rounded(c, r.left, r.top, r.right, r.bottom, 30 * s, bg);
            center(c, lens[i], r.centerX(), r.top + 38 * s, 21 * s, active ? Color.BLACK : (enabled ? Color.WHITE : 0x88FFFFFF), true);
        }
        if (!wideSupported) {
            RectF r = new RectF(28 * s, ui.lens[0].bottom + 8 * s, Math.min(w - 28 * s, 330 * s), ui.lens[0].bottom + 40 * s);
            rounded(c, r.left, r.top, r.right, r.bottom, 16 * s, 0xB0000000);
            txt(c, "0,5× não disponível neste aparelho", r.left + 13 * s, r.top + 22 * s, 12 * s, 0xDDFFFFFF, true);
        }
    }

    private void drawZoomBar(Canvas c, int w, int h, float s) {
        float y = h - 354 * s;
        txt(c, "ZOOM", 28 * s, y, 18 * s, Color.WHITE, true);
        txt(c, zoomHudString(), w - 210 * s, y, 16 * s, WHITE_92, true);
        // Never advertise 20x, 50x, or 100x without hardware declaration.
        float maxZ = Math.max(1f, maxHardware);
        float[] candidateTicks = wideSupported
                ? new float[]{0.5f, 1f, 2f, 4f, 6f, 8f, 10f}
                : new float[]{1f, 2f, 3f, 4f, 6f, 8f, 10f};
        List<Float> ticks = new ArrayList<Float>();
        for (float t : candidateTicks) {
            if (t <= maxZ + 0.01f) ticks.add(t);
        }
        if (ticks.isEmpty() || Math.abs(ticks.get(ticks.size() - 1) - maxZ) > 0.2f) {
            ticks.add(maxZ);
        }
        float step = ticks.size() > 1 ? (w - 56 * s) / (float) (ticks.size() - 1) : 0f;
        for (int i = 0; i < ticks.size(); i++) {
            float v = ticks.get(i);
            String label = (v < 1f) ? "0,5×" : (Math.abs(v - Math.round(v)) < 0.05f ? Math.round(v) + "×" : String.format(Locale.US, "%.1f×", v));
            boolean active = Math.abs(zoom - v) < (v < 2f ? 0.08f : 0.45f);
            center(c, label, 28 * s + i * step, y + 32 * s, (active ? 17 : 15) * s, active ? Color.WHITE : WHITE_70, active);
        }
        p.setColor(0xCCFFFFFF);
        c.drawRoundRect(28 * s, y + 55 * s, w - 28 * s, y + 62 * s, 4 * s, 4 * s, p);
        double minSlider = wideSupported ? 0.5 : 1.0;
        double lo = Math.log(minSlider), hi = Math.log(Math.max(minSlider + 0.01, maxZ));
        double clampedZoom = Math.max(minSlider, Math.min(Math.max(minSlider + 0.01, maxZ), zoom));
        double q = (Math.log(clampedZoom) - lo) / (hi - lo);
        p.setColor(Color.WHITE);
        c.drawCircle(28 * s + (float) q * (w - 56 * s), y + 58 * s, 11 * s, p);
        txt(c, wideSupported ? "0,5×" : "1×", 28 * s, y + 92 * s, 13 * s, WHITE_70, false);
        txt(c, String.format(Locale.US, "%.1f×", maxZ), w - 70 * s, y + 92 * s, 13 * s, WHITE_70, false);
        center(c, "A/B/C: " + diagnosticGlobalState(), w / 2f, y + 120 * s, 13 * s, 0xCCFFFFFF, false);
    }

    private void drawBottom(Canvas c, int w, int h, float s) {
        float cy = ui.shutter.centerY();
        drawFlash(c, ui.flash.centerX(), cy, s);
        drawShutter(c, ui.shutter.centerX(), cy, s);
        drawMode(c, ui.mode.centerX(), cy, s);
        center(c, flashLabel(), ui.flash.centerX(), ui.flash.bottom + 18 * s, 14 * s, Color.WHITE, true);
        center(c, "FOTO", ui.shutter.centerX(), ui.shutter.bottom + 18 * s, 16 * s, Color.WHITE, true);
        center(c, MODES[mode.ordinal()], ui.mode.centerX(), ui.mode.bottom + 18 * s, 14 * s, Color.WHITE, true);
        if (mode == Mode.NIGHT) {
            rounded(c, w / 2f - 70 * s, cy - 88 * s, w / 2f + 70 * s, cy - 56 * s, 16 * s, 0xCCFFFFFF);
            center(c, "VISÃO NOTURNA", w / 2f, cy - 65 * s, 12 * s, Color.BLACK, true);
        }
        txt(c, "Toque para focar", 28 * s, h - 24 * s, 13 * s, WHITE_70, false);
        rounded(c, ui.logExport.left, ui.logExport.top, ui.logExport.right, ui.logExport.bottom, 22 * s, 0xAA000000);
        center(c, "LOG", ui.logExport.centerX(), ui.logExport.bottom - 16 * s, 13 * s, Color.WHITE, true);
        rounded(c, ui.camera.left, ui.camera.top, ui.camera.right, ui.camera.bottom, 22 * s, 0xAA000000);
        center(c, "CAM", ui.camera.centerX(), ui.camera.bottom - 16 * s, 14 * s, Color.WHITE, true);
        rounded(c, ui.info.left, ui.info.top, ui.info.right, ui.info.bottom, 25 * s, 0xAA000000);
        center(c, "i", ui.info.centerX(), ui.info.bottom - 14 * s, 24 * s, Color.WHITE, true);
    }

    private String flashLabel() {
        return flashMode == 0 ? "FLASH OFF" : flashMode == 1 ? "FLASH AUTO" : "FLASH ON";
    }

    private void drawFlash(Canvas c, float x, float y, float s) {
        rounded(c, x - 52 * s, y - 48 * s, x + 52 * s, y + 48 * s, 26 * s, flashMode == 0 ? 0x70000000 : 0xFFF2F2F2);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4 * s);
        p.setColor(flashMode == 0 ? Color.WHITE : Color.BLACK);
        Path q = new Path();
        q.moveTo(x + 7 * s, y - 30 * s); q.lineTo(x - 13 * s, y + 3 * s); q.lineTo(x + 2 * s, y + 3 * s);
        q.lineTo(x - 7 * s, y + 30 * s); q.lineTo(x + 19 * s, y - 7 * s); q.lineTo(x + 4 * s, y - 7 * s); q.close();
        c.drawPath(q, p); p.setStyle(Paint.Style.FILL);
    }

    private void drawShutter(Canvas c, float x, float y, float s) {
        p.setColor(Color.WHITE); c.drawCircle(x, y, 54 * s, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4 * s); p.setColor(0xFF111111); c.drawCircle(x, y, 42 * s, p); p.setStyle(Paint.Style.FILL);
        if (busy) { p.setColor(0xFF111111); c.drawRoundRect(x - 13 * s, y - 13 * s, x + 13 * s, y + 13 * s, 4 * s, 4 * s, p); }
    }

    private void drawMode(Canvas c, float x, float y, float s) {
        rounded(c, x - 52 * s, y - 48 * s, x + 52 * s, y + 48 * s, 26 * s, 0x85000000);
        center(c, "MODO", x, y - 3 * s, 12 * s, Color.WHITE, true);
        center(c, MODES[mode.ordinal()], x, y + 24 * s, 13 * s, Color.WHITE, true);
    }

    private void drawModeSheet(Canvas c, float s) {
        int w = getWidth(), h = getHeight();
        rounded(c, 18 * s, 108 * s, w - 18 * s, h - 52 * s, 30 * s, 0xF20B0B0D);
        txt(c, "MODO DE CAPTURA", 38 * s, 151 * s, 27 * s, Color.WHITE, true);
        float y = 174 * s;
        for (int i = 0; i < MODES.length; i++) {
            boolean active = i == mode.ordinal();
            rounded(c, 30 * s, y, w - 30 * s, y + 72 * s, 22 * s, active ? 0xFFF4F4F4 : 0x331F1F22);
            txt(c, MODES[i], 52 * s, y + 31 * s, 21 * s, active ? Color.BLACK : Color.WHITE, true);
            txt(c, DESCS[i], 52 * s, y + 55 * s, 13 * s, active ? 0xFF333333 : 0xCCFFFFFF, false);
            y += 79 * s;
        }
        txt(c, "Toque fora para fechar", 38 * s, h - 76 * s, 12 * s, WHITE_70, false);
    }

    private void drawCameraMenu(Canvas c, float s) {
        int w = getWidth(), h = getHeight();
        rounded(c, 14 * s, 48 * s, w - 14 * s, h - 18 * s, 26 * s, 0xF20B0B0D);
        txt(c, "CÂMERAS EXPOSTAS", 32 * s, 88 * s, 25 * s, Color.WHITE, true);
        txt(c, "Toque em uma câmera para abrir diretamente", 34 * s, 112 * s, 12 * s, WHITE_70, false);
        float y = 132 * s;
        int shown = Math.min(8, publicCameraRecords.size());
        if (shown == 0) { txt(c, "Nenhuma câmera pública encontrada", 34 * s, y + 25 * s, 14 * s, WHITE_70, false); return; }
        for (int i = 0; i < shown; i++) {
            CameraRecord r = publicCameraRecords.get(i);
            boolean active = r.id != null && r.id.equals(cameraId);
            rounded(c, 28 * s, y, w - 28 * s, y + 58 * s, 18 * s, active ? 0xFFF4F4F4 : 0x331F1F22);
            String face = r.facing == CameraCharacteristics.LENS_FACING_BACK ? "TRASEIRA" :
                    r.facing == CameraCharacteristics.LENS_FACING_FRONT ? "FRONTAL" : "EXTERNA";
            txt(c, "ID " + r.id, 46 * s, y + 25 * s, 19 * s, active ? Color.BLACK : Color.WHITE, true);
            txt(c, face + (r.logical ? " • LÓGICA" : ""), 128 * s, y + 23 * s, 11 * s, active ? 0xFF333333 : WHITE_70, true);
            txt(c, r.pixels == null ? "res ?" : r.pixels.getWidth() + "×" + r.pixels.getHeight(), 128 * s, y + 42 * s, 10 * s, active ? 0xFF555555 : WHITE_70, false);
            String fov = r.fov > 0 ? String.format(Locale.US, "FOV %.1f°", Math.toDegrees(r.fov)) : "FOV ?";
            txt(c, fov + " • " + String.format(Locale.US, "zoom %.1f–%.1fx", r.range.min, r.range.max), 285 * s, y + 32 * s, 10 * s, active ? 0xFF333333 : WHITE_70, false);
            y += 66 * s;
        }
        if (publicCameraRecords.size() > shown) txt(c, "+ " + (publicCameraRecords.size() - shown) + " câmera(s) não exibida(s)", 34 * s, y + 10 * s, 11 * s, WHITE_70, false);
        txt(c, "i = diagnóstico detalhado", 34 * s, h - 54 * s, 11 * s, WHITE_70, false);
    }

    private void drawInfo(Canvas c, float s) {
        int w = getWidth(), h = getHeight();
        rounded(c, 10 * s, 42 * s, w - 10 * s, h - 18 * s, 24 * s, 0xF20B0B0D);
        txt(c, "ULTRAZOOM • IMAGE ENGINE 12.4", 26 * s, 78 * s, 21 * s, Color.WHITE, true);
        txt(c, "PIPELINE CAMERA2 + PROCESSAMENTO DE IMAGEM", 28 * s, 101 * s, 11 * s, WHITE_70, true);
        float y = 130 * s;
        infoRow(c, "Câmera ativa", cameraId == null ? "—" : cameraId, y, s); y += 25 * s;
        infoRow(c, "Principal", mainCameraId == null ? "—" : mainCameraId, y, s); y += 25 * s;
        infoRow(c, "Seleção", cameraSelectionReason, y, s); y += 25 * s;
        infoRow(c, "Zoom", zoomDiagnostics, y, s); y += 25 * s;
        infoRow(c, "JPEG", sizeString(jpegSize) + " • max " + sizeString(largestJpegSize), y, s); y += 25 * s;
        infoRow(c, "Preview", sizeString(previewSize), y, s); y += 25 * s;
        infoRow(c, "Zoom", String.format(Locale.US, "REQ %.2f× • CAM %.2f× • GEO %s • %s", lastRequestedZoom, lastResultZoom,
                zoomConfidence == ZoomConfidence.TRUSTED_CROP_ONLY ? String.format(Locale.US, "%.2f×", lastGeometricZoom) :
                (zoomConfidence == ZoomConfidence.TRUSTED_RATIO ? String.format(Locale.US, "%.2f×", lastResultZoom) : "—"), zoomConfidenceLabel()), y, s); y += 25 * s;
        infoRow(c, "Geometria", streamGeometryDiagnostics, y, s); y += 25 * s;
        infoRow(c, "Modo preview", "FRAME NATIVO 4:3 • SEM DISTORÇÃO", y, s); y += 25 * s;
        infoRow(c, "0,5×", wideSupported ? "EVIDÊNCIA DE ULTRAWIDE" : "NÃO EXPOSTO PELA CAMERA2", y, s); y += 25 * s;
        infoRow(c, "Layout", layoutValid ? "SEM SOBREPOSIÇÃO" : "FALHA", y, s); y += 25 * s;
        infoRow(c, "Imagem", imageEngineDiagnostics, y, s); y += 26 * s;
        infoRow(c, "Auto-diagnóstico", diagnosticSummary(), y, s); y += 26 * s;
        txt(c, "ORIENTAÇÃO", 26 * s, y, 11 * s, 0xAAFFFFFF, true); y += 17 * s;
        drawDiagnosticText(c, orientationDiagnostics + " • " + previewSelectionDiagnostic, 26 * s, y, w - 52 * s, 10 * s); y += 34 * s;
        drawDiagnosticText(c, "Resultado: REQ=" + String.format(Locale.US, "%.3f×", lastRequestedZoom) + " • CAM=" + String.format(Locale.US, "%.3f×", lastResultZoom) + " • GEO=" + (zoomConfidence == ZoomConfidence.TRUSTED_RATIO || zoomConfidence == ZoomConfidence.TRUSTED_CROP_ONLY ? String.format(Locale.US, "%.3f×", lastGeometricZoom) : "—") + " • estado=" + zoomConfidenceLabel() + " • crop=" + cropString(lastResultCrop) + " • physical=" + lastResultPhysicalId + " • focal=" + lastFocalResult, 26 * s, y, w - 52 * s, 9.5f * s); y += 27 * s;
        txt(c, "DISPOSITIVO / CAPACIDADES", 26 * s, y, 11 * s, 0xAAFFFFFF, true); y += 17 * s;
        drawDiagnosticText(c, detailedDiagnostics, 26 * s, y, w - 52 * s, 9.5f * s);
        y = h - 155 * s;
        txt(c, "OIS / EIS", 26 * s, y, 11 * s, 0xAAFFFFFF, true); y += 16 * s;
        drawDiagnosticText(c, stabilizationDiagnostics, 26 * s, y, w - 52 * s, 9.5f * s); y += 29 * s;
        txt(c, "FOCO / EXPOSIÇÃO", 26 * s, y, 11 * s, 0xAAFFFFFF, true); y += 16 * s;
        drawDiagnosticText(c, focusDiagnostics, 26 * s, y, w - 52 * s, 9.5f * s);
        rounded(c, 26 * s, h - 78 * s, 246 * s, h - 43 * s, 16 * s, 0x66333333);
        center(c, "FRAME NATIVO • 4:3", 136 * s, h - 57 * s, 11 * s, Color.WHITE, true);
        txt(c, "BUILD " + BUILD_ID + " • " + layoutDiagnostic, 26 * s, h - 25 * s, 8.8f * s, WHITE_70, false);
        txt(c, "Toque fora para fechar", w - 165 * s, h - 25 * s, 9.5f * s, WHITE_70, false);
    }

    private void drawDiagnosticText(Canvas c, String value, float x, float y, float width, float size) {
        String[] lines = value == null ? new String[]{"—"} : value.split("\\n");
        int count = Math.min(lines.length, 12);
        float lineH = Math.max(13f, size * 1.55f);
        for (int i = 0; i < count; i++) txt(c, ellipsize(lines[i], 105), x, y + i * lineH, size, WHITE_70, false);
    }

    private String ellipsize(String s, int max) {
        if (s == null) return "—";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    private void infoRow(Canvas c, String a, String b, float y, float s) {
        txt(c, a, 38 * s, y, 15 * s, 0xAAFFFFFF, false);
        txt(c, b, 270 * s, y, 18 * s, Color.WHITE, true);
    }

    private boolean overlaps(RectF a, RectF b) {
        return RectF.intersects(a, b);
    }

    private void validateLayout() {
        layoutValid = true;
        layoutDiagnostic = "OK";
        for (int i = 0; i < ui.lens.length; i++) {
            if (ui.lens[i].left < 0 || ui.lens[i].right > getWidth()) { layoutValid = false; layoutDiagnostic = "LENTE FORA DA TELA"; }
            for (int j = i + 1; j < ui.lens.length; j++) if (overlaps(ui.lens[i], ui.lens[j])) { layoutValid = false; layoutDiagnostic = "LENTES SOBREPOSTAS"; }
        }
        if (overlaps(ui.mini, ui.lens[0]) || overlaps(ui.mini, ui.lens[1]) || overlaps(ui.mini, ui.lens[2]) || overlaps(ui.mini, ui.lens[3]) || overlaps(ui.mini, ui.lens[4])) {
            layoutValid = false; layoutDiagnostic = "MINIMAPA SOBRE LENTES";
        }
        if (overlaps(ui.zoomArea, ui.shutter) || overlaps(ui.zoomArea, ui.flash) || overlaps(ui.zoomArea, ui.mode)) {
            layoutValid = false; layoutDiagnostic = "ZOOM SOBRE CONTROLES";
        }
        if (overlaps(ui.flash, ui.shutter) || overlaps(ui.shutter, ui.mode) || overlaps(ui.flash, ui.mode) ||
                overlaps(ui.logExport, ui.flash) || overlaps(ui.logExport, ui.shutter) || overlaps(ui.logExport, ui.mode)) {
            layoutValid = false; layoutDiagnostic = "CONTROLES INFERIORES SOBREPOSTOS";
        }
    }

    private static class CameraRecord {
        String id;
        boolean logical;
        int facing;
        float fov;
        RangeZ range;
        Size pixels;
        long area;
    }

    private boolean isBetterMain(CameraRecord candidate, CameraRecord current) {
        if (current == null) return true;
        if (candidate.area != current.area) return candidate.area > current.area;
        return compareCameraIds(candidate.id, current.id) < 0;
    }

    private int compareCameraIds(String a, String b) {
        try { return Integer.compare(Integer.parseInt(a), Integer.parseInt(b)); }
        catch (Exception ignored) { return a.compareTo(b); }
    }

    private void sortCameraRecords() {
        Collections.sort(publicCameraRecords, (a, b) -> compareCameraIds(a.id, b.id));
    }

    private String formatRecord(CameraRecord r) {
        String resolution = r.pixels == null ? "—" : r.pixels.getWidth() + "×" + r.pixels.getHeight();
        return "ID " + r.id + (r.logical ? " LOGICAL" : "") + " • " + resolution + " • " +
                (r.fov > 0 ? String.format(Locale.US, "FOV %.1f°", Math.toDegrees(r.fov)) : "FOV ?") + " • " +
                String.format(Locale.US, "zoom %.2f–%.1f×", r.range.min, r.range.max);
    }

    private String rectString(Rect r) {
        if (r == null) return "?";
        return r.width() + "x" + r.height() + " [" + r.left + "," + r.top + "-" + r.right + "," + r.bottom + "]";
    }

    private String sizeString(Size s) {
        return s == null ? "—" : s.getWidth() + "×" + s.getHeight();
    }

    private String capabilityNames(int[] caps) {
        if (caps == null || caps.length == 0) return "—";
        ArrayList<String> out = new ArrayList<String>();
        for (int c : caps) {
            switch (c) {
                case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE: out.add("BACKWARD"); break;
                case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR: out.add("MANUAL_SENSOR"); break;
                case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING: out.add("MANUAL_PP"); break;
                case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW: out.add("RAW"); break;
                case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING: out.add("PRIVATE_REPROCESS"); break;
                case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS: out.add("READ_SENSOR"); break;
                case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE: out.add("BURST"); break;
                case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING: out.add("YUV_REPROCESS"); break;
                case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT: out.add("DEPTH"); break;
                case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO: out.add("HIGH_SPEED"); break;
                case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA: out.add("LOGICAL_MULTI"); break;
                case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING: out.add("MOTION_TRACKING"); break;
                case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR: out.add("ULTRA_HIGH_RES"); break;
                default: out.add("CAP_" + c); break;
            }
        }
        return join(out, ", ");
    }

    private String join(List<String> values, String sep) {
        StringBuilder b = new StringBuilder();
        for (String v : values) {
            if (b.length() > 0) b.append(sep);
            b.append(v);
        }
        return b.toString();
    }

    private String arrayString(int[] values) {
        if (values == null || values.length == 0) return "—";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) b.append(",");
            b.append(values[i]);
        }
        return b.toString();
    }

    private String floatArrayString(float[] values) {
        if (values == null || values.length == 0) return "—";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) b.append(",");
            b.append(String.format(Locale.US, "%.3f", values[i]));
        }
        return b.toString();
    }

    private String rangeString(Range<Float> r) {
        if (r == null) return "—";
        return String.format(Locale.US, "%.2f–%.2f×", r.getLower(), r.getUpper());
    }

    private String orientationString(CameraCharacteristics cc) {
        int sensorOrientation = -1;
        if (cc != null) {
            Integer so = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (so != null) sensorOrientation = so.intValue();
        }
        int display = 0;
        try {
            int r = getContext().getDisplay().getRotation();
            if (r == Surface.ROTATION_90) display = 90;
            else if (r == Surface.ROTATION_180) display = 180;
            else if (r == Surface.ROTATION_270) display = 270;
        } catch (Exception ignored) { }
        int relative = -1;
        if (sensorOrientation >= 0) relative = (sensorOrientation - display + 360) % 360;
        return "display=" + display + "° sensor=" + sensorOrientation + "° relative=" + relative + "°";
    }

    private void collectDetailedDiagnostics(CameraManager cm, String[] ids, CameraCharacteristics main) {
        StringBuilder d = new StringBuilder();
        d.append("DEVICE ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
         .append(" • Android ").append(Build.VERSION.SDK_INT).append(" • SDK ").append(Build.VERSION.RELEASE).append("\n");
        d.append("Display ").append(orientationString(main)).append("\n");
        Integer mainSo = main.get(CameraCharacteristics.SENSOR_ORIENTATION);
        d.append("Main sensor orientation=").append(mainSo == null ? "?" : String.valueOf(mainSo)).append("°\n");
        Rect mainActive = main.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        d.append("Main active array=").append(rectString(mainActive)).append("\n");
        Size mainPixels = main.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        d.append("Main pixel array=").append(sizeString(mainPixels)).append("\n");
        float mainFov = horizontalFov(main);
        d.append("Main FOV=").append(mainFov > 0 ? String.format(Locale.US, "%.2f°", Math.toDegrees(mainFov)) : "?").append("\n");
        float[] mainFocal = main.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        d.append("Main focal=").append(floatArrayString(mainFocal)).append("mm\n");
        SizeF ps = main.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        d.append("Sensor physical=").append(ps == null ? "?" : String.format(Locale.US, "%.2f×%.2fmm", ps.getWidth(), ps.getHeight())).append("\n");
        Integer mainHardwareLevel = main.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        d.append("Hardware level=").append(mainHardwareLevel).append("\n");
        int[] mainCaps = main.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        d.append("Capabilities=").append(capabilityNames(mainCaps)).append("\n");
        Range<Float> zr = main.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        Float dz = main.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        d.append("CONTROL_ZOOM_RATIO_RANGE=").append(rangeString(zr)).append("\n");
        d.append("SCALER_MAX_DIGITAL_ZOOM=").append(dz == null ? "?" : String.format(Locale.US, "%.2f×", dz)).append("\n");
        d.append("Public rear count=").append(backCameraCount).append("\n");
        d.append("Camera IDs=").append(Arrays.toString(ids)).append("\n");
        d.append("JPEG selected=").append(sizeString(jpegSize)).append(" • largest=").append(sizeString(largestJpegSize)).append("\n");
        d.append("Preview selected=").append(sizeString(previewSize)).append(" • ").append(previewSelectionDiagnostic).append("\n");
        d.append("OIS/EIS=").append(stabilizationDiagnostics).append("\n");
        d.append("AF=").append(focusDiagnostics).append("\n");
        d.append("Physical IDs=").append(physicalIdsDiagnostics).append("\n");
        d.append("Outputs=").append(outputDiagnostics);
        detailedDiagnostics = d.toString();
    }

    private void collectCapabilityDiagnostics(CameraCharacteristics cc) {
        if (cc == null) return;
        int[] caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        capabilitiesDiagnostics = capabilityNames(caps);
        int[] afModes = cc.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        int[] aeModes = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
        int[] awbModes = cc.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        Boolean aeLock = cc.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE);
        aeLockSupported = Boolean.TRUE.equals(aeLock);
        Integer maxAfRegions = cc.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
        Integer maxAeRegions = cc.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        focusDiagnostics = "AF modes=" + arrayString(afModes) + " • maxAF=" +
                String.valueOf(maxAfRegions) +
                " • maxAE=" + String.valueOf(maxAeRegions) +
                " • AE=" + arrayString(aeModes) + " • AWB=" + arrayString(awbModes);
        int[] ois = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        if (ois == null) ois = new int[0];
        int[] eis = cc.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        stabilizationDiagnostics = "OIS=" + (ois.length != 0 ? Arrays.toString(ois) : "?") + " • EIS=" + arrayString(eis);
        Range<Float> zr = cc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        Float dz = cc.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        zoomDiagnostics = "ratio=" + rangeString(zr) + " • maxDigital=" + (dz == null ? "?" : String.format(Locale.US, "%.2f×", dz));
    }

    private void collectOutputDiagnostics(CameraCharacteristics cc) {
        StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) { outputDiagnostics = "StreamConfigurationMap indisponível"; return; }
        StringBuilder b = new StringBuilder();
        Size[] js = map.getOutputSizes(android.graphics.ImageFormat.JPEG);
        if (js != null) {
            for (Size s : js) {
                if (largestJpegSize == null || (long) s.getWidth() * s.getHeight() > (long) largestJpegSize.getWidth() * largestJpegSize.getHeight()) largestJpegSize = s;
            }
        }
        b.append("JPEG count=").append(js == null ? 0 : js.length).append(" max=").append(sizeString(largestJpegSize));
        Size[] y = map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888);
        b.append(" • YUV count=").append(y == null ? 0 : y.length);
        if (Build.VERSION.SDK_INT >= 23) {
            Size[] p = map.getOutputSizes(SurfaceTexture.class);
            b.append(" • PREVIEW count=").append(p == null ? 0 : p.length);
        }
        outputDiagnostics = b.toString();
    }

    private void open() {
        if (device != null) return;
        try {
            CameraManager cm = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
            String[] ids = cm.getCameraIdList();
            mainCameraId = null; wideCameraId = null; physicalWideId = null; activePhysicalId = null;
            wideSupported = false; backCameraCount = 0;
            StringBuilder publicDiag = new StringBuilder();
            StringBuilder physicalDiag = new StringBuilder();
            CameraRecord best = null;
            publicCameraRecords.clear();

            for (String id : ids) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                Integer facingValue = cc.get(CameraCharacteristics.LENS_FACING);
                if (facingValue == null) continue;
                CameraRecord publicRecord = new CameraRecord();
                publicRecord.id = id;
                publicRecord.facing = facingValue;
                publicRecord.fov = horizontalFov(cc);
                publicRecord.range = range(cc);
                int[] publicCaps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                publicRecord.logical = hasCapability(publicCaps, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA);
                publicRecord.pixels = cc.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                publicRecord.area = publicRecord.pixels == null ? 0L : (long) publicRecord.pixels.getWidth() * publicRecord.pixels.getHeight();
                publicCameraRecords.add(publicRecord);
                if (facingValue != CameraCharacteristics.LENS_FACING_BACK) continue;
                backCameraCount++;
                publicDiag.append(formatRecord(publicRecord)).append("\n");
                if (isBetterMain(publicRecord, best)) best = publicRecord;
            }

            sortCameraRecords();
            publicCameraDiagnostic = publicDiag.length() == 0 ? "Nenhuma câmera traseira pública." : publicDiag.toString().trim();
            if (best == null) {
                cameraDiagnostics = "Nenhuma câmera traseira";
                updateStatusOnUi("NENHUMA CÂMERA TRASEIRA");
                return;
            }
            mainCameraId = best.id;
            cameraSelectionReason = "MAIOR RESOLUÇÃO " + (best.pixels == null ? "DESCONHECIDA" : best.pixels.getWidth() + "×" + best.pixels.getHeight()) + "; empate → menor ID";
            if (manualCameraId != null) {
                boolean foundManual = false;
                for (String id : ids) if (id.equals(manualCameraId)) { foundManual = true; break; }
                if (foundManual) cameraId = manualCameraId; else manualCameraId = null;
            }

            CameraCharacteristics main = cm.getCameraCharacteristics(mainCameraId);
            collectCapabilityDiagnostics(main);
            collectOutputDiagnostics(main);
            float mainFov = horizontalFov(main);
            RangeZ mainRange = range(main);
            if (mainRange.min <= 0.55f) {
                wideSupported = true;
                wideCameraId = mainCameraId;
            }

            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    java.util.Set<String> physicalIds = main.getPhysicalCameraIds();
                    if (physicalIds == null || physicalIds.isEmpty()) {
                        physicalDiag.append("getPhysicalCameraIds(): VAZIO\n");
                    } else {
                        physicalDiag.append("getPhysicalCameraIds(): ").append(physicalIds).append("\n");
                        float widest = 0f;
                        for (String pid : physicalIds) {
                            try {
                                CameraCharacteristics pc = cm.getCameraCharacteristics(pid);
                                float pfov = horizontalFov(pc);
                                RangeZ pr = range(pc);
                                Size pp = pc.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                                String ppText = pp == null ? "res ?" : pp.getWidth() + "×" + pp.getHeight();
                                String pfovText = pfov > 0 ? String.format(Locale.US, "FOV %.1f°", Math.toDegrees(pfov)) : "FOV ?";
                                physicalDiag.append("PHYS ").append(pid).append(" • ")
                                        .append(ppText)
                                        .append(" • ").append(pfovText)
                                        .append(" • ").append(String.format(Locale.US, "zoom %.2f–%.1f×", pr.min, pr.max)).append("\n");
                                if (pfov > widest) {
                                    widest = pfov;
                                    if (mainFov > 0 && pfov > mainFov * 1.15f) physicalWideId = pid;
                                }
                            } catch (Exception ex) {
                                physicalDiag.append("PHYS ").append(pid).append(" • características não públicas para CameraManager\n");
                            }
                        }
                        if (physicalWideId != null) {
                            wideSupported = true;
                            wideCameraId = mainCameraId;
                        }
                    }
                } catch (Exception ex) {
                    physicalDiag.append("getPhysicalCameraIds(): erro ").append(ex.getClass().getSimpleName()).append("\n");
                }
            } else {
                physicalDiag.append("API < 28: physical IDs não suportados pelo caminho usado.\n");
            }

            if (!wideSupported) {
                float widestFov = 0f;
                String widestId = null;
                for (String id : ids) {
                    if (id.equals(mainCameraId)) continue;
                    try {
                        CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                        Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                        if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
                        float fov = horizontalFov(cc);
                        if (fov > widestFov) { widestFov = fov; widestId = id; }
                    } catch (Exception ignored) { }
                }
                if (widestId != null && mainFov > 0 && widestFov > mainFov * 1.15f) {
                    wideCameraId = widestId;
                    wideSupported = true;
                }
            }

            physicalIdsDiagnostics = physicalDiag.length() == 0 ? "não coletado" : physicalDiag.toString().replace("\n", " | ").trim();
            collectOutputDiagnostics(main);
            collectDetailedDiagnostics(cm, ids, main);
            if (manualCameraId == null && (cameraId == null || (!cameraId.equals(mainCameraId) && !cameraId.equals(wideCameraId)))) cameraId = mainCameraId;
            if (zoom < 1f && !wideSupported) zoom = 1f;

            boolean manualSelection = manualCameraId != null && manualCameraId.equals(cameraId);
            boolean usingSeparateWide = !manualSelection && wideSupported && wideCameraId != null && !wideCameraId.equals(mainCameraId) && cameraId.equals(wideCameraId);
            boolean usingPhysicalWide = !manualSelection && wideSupported && wideCameraId != null && wideCameraId.equals(mainCameraId) && zoom < 1f && physicalWideId != null;

            if (usingPhysicalWide) {
                activePhysicalId = physicalWideId;
                deviceChars = main;
                try { chars = cm.getCameraCharacteristics(physicalWideId); }
                catch (Exception ex) { chars = main; }
            } else if (usingSeparateWide) {
                activePhysicalId = null;
                deviceChars = cm.getCameraCharacteristics(cameraId);
                chars = deviceChars;
            } else if (manualSelection) {
                activePhysicalId = null;
                deviceChars = cm.getCameraCharacteristics(cameraId);
                chars = deviceChars;
                if (zoom < 1f) zoom = 1f;
            } else {
                activePhysicalId = null;
                deviceChars = main;
                chars = main;
                cameraId = mainCameraId;
                if (zoom < 1f && !wideSupported) zoom = 1f;
            }

            sensor = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            RangeZ rr = range(chars);
            minHardware = rr.min;
            maxHardware = rr.max;
            if (usingPhysicalWide || usingSeparateWide) {
                minHardware = Math.max(1f, minHardware);
                if (zoom < 1f) zoom = 0.5f;
            }
            zoom = Math.max(wideSupported ? 0.5f : 1.0f, Math.min(Math.max(1f, maxHardware), zoom));

            prepareReader(chars);
            collectOutputDiagnostics(chars);
            collectCapabilityDiagnostics(chars);
            flashAvailable = Boolean.TRUE.equals(chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
            collectDetailedDiagnostics(cm, ids, main);
            lowLightBoostSupported = supportsLowLightBoost(chars);
            cameraDiagnostics = publicCameraDiagnostic;
            updateStatusOnUi(wideSupported ? "CÂMERA PRONTA • 0,5× DISPONÍVEL" : "CÂMERA PRONTA • 0,5× NÃO DISPONÍVEL");

            if (getContext().checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
            cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { device = d; createSession(); }
                @Override public void onDisconnected(CameraDevice d) { d.close(); device = null; }
                @Override public void onError(CameraDevice d, int e) { d.close(); device = null; updateStatusOnUi("ERRO NA CÂMERA"); }
            }, cameraHandler);
            postInvalidate();
        } catch (Exception e) {
            cameraDiagnostics = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            updateStatusOnUi("CÂMERA INDISPONÍVEL");
        }
    }

    private boolean supportsLowLightBoost(CameraCharacteristics cc) {
        if (Build.VERSION.SDK_INT < 35) return false;
        int[] modes = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
        if (modes == null) return false;
        for (int m : modes) if (m == CaptureRequest.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY) return true;
        return false;
    }

    private boolean hasCapability(int[] caps, int wanted) {
        if (caps == null) return false;
        for (int c : caps) if (c == wanted) return true;
        return false;
    }

    private void prepareReader(CameraCharacteristics cc) {
        if (reader != null) { reader.close(); reader = null; }
        if (miniReader != null) { miniReader.close(); miniReader = null; }
        StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        jpegSize = new Size(1920, 1080);
        Size miniSize = new Size(640, 480);
        if (map != null) {
            Size[] sizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG);
            if (sizes != null && sizes.length > 0) {
                Size bestUnder12 = null;
                Size largest = sizes[0];
                Size bestMini = null;
                for (Size candidate : sizes) {
                    long area = (long) candidate.getWidth() * candidate.getHeight();
                    long largestArea = (long) largest.getWidth() * largest.getHeight();
                    if (area > largestArea) largest = candidate;
                    if (area <= 12_000_000L && (bestUnder12 == null || area > (long) bestUnder12.getWidth() * bestUnder12.getHeight())) bestUnder12 = candidate;
                    if (area >= 250_000L && area <= 1_000_000L) {
                        if (bestMini == null || area > (long) bestMini.getWidth() * bestMini.getHeight()) bestMini = candidate;
                    }
                }
                jpegSize = bestUnder12 != null ? bestUnder12 : largest;
                if (bestMini != null) miniSize = bestMini;
                else {
                    float target = 4f / 3f;
                    double best = Double.MAX_VALUE;
                    for (Size candidate : sizes) {
                        long area = (long) candidate.getWidth() * candidate.getHeight();
                        if (area > 1_500_000L) continue;
                        double score = Math.abs(((double) candidate.getWidth() / candidate.getHeight()) - target) * 10 + Math.abs(area - 500_000L) / 500_000d;
                        if (score < best) { best = score; miniSize = candidate; }
                    }
                }
            }
        }
        if (map != null) {
            Size[] yuvSizes = map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888);
            if (yuvSizes != null && yuvSizes.length > 0) {
                Size best = null;
                double bestScore = Double.MAX_VALUE;
                for (Size candidate : yuvSizes) {
                    long area = (long) candidate.getWidth() * candidate.getHeight();
                    if (area < 100_000L || area > 1_500_000L) continue;
                    double ratio = (double) candidate.getWidth() / Math.max(1, candidate.getHeight());
                    double score = Math.abs(ratio - 4d / 3d) * 10d + Math.abs(area - 460_800d) / 460_800d;
                    if (score < bestScore) { bestScore = score; best = candidate; }
                }
                if (best != null) miniSize = best;
            }
        }

        reader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), android.graphics.ImageFormat.JPEG, 6);
        reader.setOnImageAvailableListener(rdr -> {
            Image im = null;
            try {
                im = rdr.acquireNextImage();
                if (im != null && busy) {
                    synchronized (frames) {
                        if (frames.size() >= targetFrames) return;
                    }
                    ByteBuffer buf = im.getPlanes()[0].getBuffer();
                    byte[] data = new byte[buf.remaining()];
                    buf.get(data);
                    BitmapFactory.Options opt = new BitmapFactory.Options();
                    opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    int sample = targetFrames > 1
                            ? Math.max(2, MemoryPolicy.recommendedInSampleSize(jpegSize.getWidth(), jpegSize.getHeight(), targetFrames, MemoryPolicy.DEFAULT_FRAME_BUDGET_BYTES))
                            : MemoryPolicy.recommendedInSampleSize(jpegSize.getWidth(), jpegSize.getHeight(), 1, MemoryPolicy.DEFAULT_FRAME_BUDGET_BYTES);
                    opt.inSampleSize = sample;
                    Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length, opt);
                    if (bm != null) finishPhoto(bm);
                }
            } catch (Exception ignored) {
            } finally {
                if (im != null) im.close();
            }
        }, workHandler);

        miniReader = ImageReader.newInstance(miniSize.getWidth(), miniSize.getHeight(), android.graphics.ImageFormat.YUV_420_888, 2);
        miniReader.setOnImageAvailableListener(rdr -> {
            Image im = null;
            try {
                im = rdr.acquireLatestImage();
                if (im != null) {
                    Bitmap bm = yuvToBitmap(im);
                    if (bm != null) {
                        Bitmap old = miniBitmap;
                        miniBitmap = bm;
                        if (old != null && old != bm && !old.isRecycled()) old.recycle();
                        postInvalidate();
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (im != null) im.close();
                miniCapturePending = false;
            }
        }, workHandler);
    }

    private Bitmap yuvToBitmap(Image image) {
        int w = image.getWidth(), h = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length < 3) return null;
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int yRow = planes[0].getRowStride(), uRow = planes[1].getRowStride(), vRow = planes[2].getRowStride();
        int yPix = planes[0].getPixelStride(), uPix = planes[1].getPixelStride(), vPix = planes[2].getPixelStride();
        int[] pixels = new int[w * h];
        for (int yy = 0; yy < h; yy++) {
            int yBase = yy * yRow;
            int uvY = yy >> 1;
            for (int xx = 0; xx < w; xx++) {
                int yIndex = yBase + xx * yPix;
                int uvX = xx >> 1;
                int uIndex = uvY * uRow + uvX * uPix;
                int vIndex = uvY * vRow + uvX * vPix;
                int Y = (yBuf.get(yIndex) & 0xFF) - 16;
                int U = (uBuf.get(uIndex) & 0xFF) - 128;
                int V = (vBuf.get(vIndex) & 0xFF) - 128;
                int r = clamp255((298 * Y + 409 * V + 128) >> 8);
                int g = clamp255((298 * Y - 100 * U - 208 * V + 128) >> 8);
                int b = clamp255((298 * Y + 516 * U + 128) >> 8);
                pixels[yy * w + xx] = Color.rgb(r, g, b);
            }
        }
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bm.setPixels(pixels, 0, w, 0, 0, w, h);
        int degrees = relativeDisplayDegrees();
        if (degrees != 0) {
            Matrix m = new Matrix();
            m.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            if (rotated != bm && !bm.isRecycled()) bm.recycle();
            bm = rotated;
        }
        return bm;
    }

    private int clamp255(int v) { return Math.max(0, Math.min(255, v)); }

    private float horizontalFov(CameraCharacteristics cc) {
        try {
            float[] fs = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            SizeF physical = cc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            if (fs != null && fs.length > 0 && physical != null && fs[0] > 0) {
                return (float) (2d * Math.atan(physical.getWidth() / (2d * fs[0])));
            }
        } catch (Exception ignored) { }
        return 0f;
    }

    private static class RangeZ {
        final float min, max;
        RangeZ(float min, float max) { this.min = min; this.max = max; }
    }

    private RangeZ range(CameraCharacteristics cc) {
        if (Build.VERSION.SDK_INT >= 30) {
            Range<Float> r = cc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (r != null) return new RangeZ(r.getLower(), r.getUpper());
        }
        Float z = cc.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        float max = 1f;
        if (z != null) max = Math.max(1f, z.floatValue());
        return new RangeZ(1f, max);
    }

    private void createSession() {
        try {
            SurfaceTexture st = preview.getSurfaceTexture();
            if (st == null || device == null || reader == null || miniReader == null) return;
            previewSize = choosePreviewSize(chars);
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            if (previewSurface != null) { try { previewSurface.release(); } catch (Exception ignored) { } }
            previewSurface = new Surface(st);
            Surface ps = previewSurface;
            miniInSession = false;

            if (Build.VERSION.SDK_INT >= 28 && activePhysicalId != null) {
                OutputConfiguration po = new OutputConfiguration(ps);
                OutputConfiguration jo = new OutputConfiguration(reader.getSurface());
                OutputConfiguration mo = new OutputConfiguration(miniReader.getSurface());
                po.setPhysicalCameraId(activePhysicalId);
                jo.setPhysicalCameraId(activePhysicalId);
                mo.setPhysicalCameraId(activePhysicalId);
                List<OutputConfiguration> outputs = new ArrayList<OutputConfiguration>();
                outputs.add(po);
                outputs.add(jo);
                outputs.add(mo);
                SessionConfiguration config = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, cameraExecutor, new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession s) {
                        session = s;
                        miniInSession = true;
                        applyPreview();
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession s) {
                        createPhysicalFallbackSession(ps);
                    }
                });
                device.createCaptureSession(config);
            } else {
                List<Surface> surfaces = new ArrayList<Surface>();
                surfaces.add(ps);
                surfaces.add(reader.getSurface());
                surfaces.add(miniReader.getSurface());
                device.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession s) {
                        session = s;
                        miniInSession = true;
                        applyPreview();
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession s) {
                        createSimpleSession(ps, "FALHA AO CONFIGURAR CÂMERA");
                    }
                }, cameraHandler);
            }
        } catch (Exception e) {
            updateStatusOnUi("FALHA NA SESSÃO");
        }
    }

    private void createPhysicalFallbackSession(Surface ps) {
        try {
            OutputConfiguration po = new OutputConfiguration(ps);
            OutputConfiguration jo = new OutputConfiguration(reader.getSurface());
            po.setPhysicalCameraId(activePhysicalId);
            jo.setPhysicalCameraId(activePhysicalId);
            List<OutputConfiguration> outputs = new ArrayList<OutputConfiguration>();
            outputs.add(po);
            outputs.add(jo);
            SessionConfiguration config = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, cameraExecutor, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    miniInSession = false;
                    updateStatusOnUi("0,5× • ULTRAWIDE REAL • MAPA OFF");
                    applyPreview();
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    updateStatusOnUi("ULTRAWIDE FÍSICA NÃO ACEITA ESTA SESSÃO");
                }
            });
            device.createCaptureSession(config);
        } catch (Exception e) {
            updateStatusOnUi("ULTRAWIDE FÍSICA INDISPONÍVEL");
        }
    }

    private void createSimpleSession(Surface ps, String failStatus) {
        try {
            List<Surface> surfaces = new ArrayList<Surface>();
            surfaces.add(ps);
            surfaces.add(reader.getSurface());
            device.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    miniInSession = false;
                    updateStatusOnUi("CÂMERA PRONTA • MAPA OFF");
                    applyPreview();
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    updateStatusOnUi(failStatus);
                }
            }, cameraHandler);
        } catch (Exception e) {
            updateStatusOnUi(failStatus);
        }
    }

    private Size choosePreviewSize(CameraCharacteristics cc) {
        StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return new Size(1600, 1200);
        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
        if (sizes == null || sizes.length == 0) return new Size(1600, 1200);

        final float target = 4f / 3f;
        Size best = null;
        double bestScore = Double.MAX_VALUE;
        for (Size candidate : sizes) {
            float ratio = (float) candidate.getWidth() / Math.max(1f, candidate.getHeight());
            float diff = Math.abs(ratio - target);
            long area = (long) candidate.getWidth() * candidate.getHeight();
            if (diff < 0.015f) {
                double sizePenalty = area > 1920L * 1440L ? 0.35 : 0.0;
                double score = diff * 1000.0 + sizePenalty - Math.min(area, 1920L * 1440L) / 100000000.0;
                if (score < bestScore) { bestScore = score; best = candidate; }
            }
        }
        if (best == null) {
            best = sizes[0];
            for (Size candidate : sizes) {
                float ratio = (float) candidate.getWidth() / Math.max(1f, candidate.getHeight());
                if (Math.abs(ratio - target) < Math.abs(((float) best.getWidth() / Math.max(1f, best.getHeight())) - target)) best = candidate;
            }
        }
        previewSelectionDiagnostic = String.format(Locale.US,
                "FRAME NATIVO 4:3 • alvo %.3f • buffer=%s • aspect=%.3f",
                target, sizeString(best), (double) best.getWidth() / Math.max(1, best.getHeight()));
        return best;
    }

    private CaptureRequest.Builder request(int template) throws Exception {
        CaptureRequest.Builder b = device.createCaptureRequest(template);
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

        int ae = CaptureRequest.CONTROL_AE_MODE_ON;
        if (mode == Mode.NIGHT && lowLightBoostSupported) {
            ae = CaptureRequest.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY;
        } else if (template == CameraDevice.TEMPLATE_STILL_CAPTURE && flashAvailable) {
            if (flashMode == 1) ae = CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH;
            else if (flashMode == 2) ae = CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH;
        }
        b.set(CaptureRequest.CONTROL_AE_MODE, ae);
        if (template == CameraDevice.TEMPLATE_STILL_CAPTURE) {
            try { b.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE); } catch (Exception ignored) { }
            try { b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY); } catch (Exception ignored) { }
            try { b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY); } catch (Exception ignored) { }
            try { b.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY); } catch (Exception ignored) { }
        }
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                b.set(CaptureRequest.CONTROL_ZOOM_METHOD,
                        zoomConfidence == ZoomConfidence.TRUSTED_CROP_ONLY
                                ? CaptureRequest.CONTROL_ZOOM_METHOD_AUTO
                                : CaptureRequest.CONTROL_ZOOM_METHOD_ZOOM_RATIO);
            } catch (Exception ignored) { }
        }
        if (aeZoomLockedByGesture && template == CameraDevice.TEMPLATE_PREVIEW && aeLockSupported) {
            b.set(CaptureRequest.CONTROL_AE_LOCK, true);
        }
        if (template == CameraDevice.TEMPLATE_STILL_CAPTURE && targetFrames > 1 && mode != Mode.NIGHT && aeLockSupported &&
                lastAeResultTime > 0 && System.currentTimeMillis() - lastAeResultTime < 700 &&
                (lastAeState == CaptureResult.CONTROL_AE_STATE_CONVERGED || lastAeState == CaptureResult.CONTROL_AE_STATE_LOCKED)) {
            b.set(CaptureRequest.CONTROL_AE_LOCK, true);
        }
        if (flashAvailable) {
            if (template == CameraDevice.TEMPLATE_PREVIEW && flashMode == 2) b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            else b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
        }

        applyHardwareZoom(b, zoom);
        if (focusRegion != null) {
            Integer maxAf = null;
            Integer maxAe = null;
            if (chars != null) {
                maxAf = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
                maxAe = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
            }
            if (maxAf != null && maxAf > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, new android.hardware.camera2.params.MeteringRectangle[]{
                    new android.hardware.camera2.params.MeteringRectangle(focusRegion, 800)
            });
            if (maxAe != null && maxAe > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, new android.hardware.camera2.params.MeteringRectangle[]{
                    new android.hardware.camera2.params.MeteringRectangle(focusRegion, 600)
            });
        }

        if (mode == Mode.NIGHT && template == CameraDevice.TEMPLATE_STILL_CAPTURE && !lowLightBoostSupported) {
            Range<Integer> ar = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            if (ar != null) {
                int ev = Math.min(ar.getUpper(), Math.max(ar.getLower(), 2));
                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev);
            }
        }
        return b;
    }

    private void applyHardwareZoom(CaptureRequest.Builder b, float requestedZoom) {
        lastRequestedZoom = requestedZoom;
        resetZoomConfidence(requestedZoom);
        if (sensor == null) return;
        if (requestedZoom < 1f && activePhysicalId != null) {
            if (Build.VERSION.SDK_INT >= 30) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f);
            else b.set(CaptureRequest.SCALER_CROP_REGION, sensor);
            return;
        }
        if (requestedZoom < 1f && cameraId != null && cameraId.equals(wideCameraId)) {
            if (Build.VERSION.SDK_INT >= 30 && minHardware < 1f && requestedZoom >= minHardware && requestedZoom <= maxHardware) {
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, Math.max(minHardware, Math.min(maxHardware, requestedZoom)));
            } else {
                b.set(CaptureRequest.SCALER_CROP_REGION, sensor);
            }
            return;
        }

        float hardwareZoom = Math.max(1f, Math.min(Math.max(1f, maxHardware), requestedZoom));
        if (zoomConfidence == ZoomConfidence.TRUSTED_CROP_ONLY && hardwareZoom > 1.0f) {
            Rect trustedCrop = cropForZoom(hardwareZoom);
            if (trustedCrop != null) b.set(CaptureRequest.SCALER_CROP_REGION, trustedCrop);
            return;
        }
        if (Build.VERSION.SDK_INT >= 30 && hardwareZoom >= minHardware && hardwareZoom <= maxHardware) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, hardwareZoom);
        } else {
            Rect crop = cropForZoom(hardwareZoom);
            if (crop != null) b.set(CaptureRequest.SCALER_CROP_REGION, crop);
        }
    }

    private CaptureRequest.Builder requestAtZoom(int template, float forcedZoom) throws Exception {
        CaptureRequest.Builder b = request(template);
        applyHardwareZoom(b, forcedZoom);
        return b;
    }

    private Rect cropForZoom(float z) {
        if (sensor == null) return null;
        z = Math.max(1f, Math.min(Math.max(1f, maxHardware), z));
        int cw = Math.max(2, Math.round(sensor.width() / z));
        int ch = Math.max(2, Math.round(sensor.height() / z));
        int left = sensor.centerX() - cw / 2;
        int top = sensor.centerY() - ch / 2;
        Rect r = new Rect(left, top, left + cw, top + ch);
        r.intersect(sensor);
        return r;
    }

    private final CameraCaptureSession.CaptureCallback previewCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
            try {
                Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
                if (ae != null) lastAeState = ae;
                Integer af = result.get(CaptureResult.CONTROL_AF_STATE);
                if (af != null) {
                    AfStateMachine.State prevAf = afStateMachine.get();
                    afStateMachine.result(af);
                    if ((prevAf == AfStateMachine.State.STARTING || prevAf == AfStateMachine.State.SCANNING) && afStateMachine.isTerminal()) {
                        focusDiagnostics = "AF estado=" + afStateName(af) + " • " + afStateMachine.get().name();
                        restorePreviewRepeating();
                    }
                }
                Long exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                Long fd = result.get(CaptureResult.SENSOR_FRAME_DURATION);
                Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                if (exp != null) lastExposureTimeNs = exp;
                if (fd != null) lastFrameDurationNs = fd;
                if (iso != null) lastSensitivity = iso;
                lastAeResultTime = System.currentTimeMillis();
                Float rz = result.get(CaptureResult.CONTROL_ZOOM_RATIO);
                if (rz != null) lastResultZoom = rz;
                Rect cr = result.get(CaptureResult.SCALER_CROP_REGION);
                if (cr != null) lastResultCrop = new Rect(cr);
                if (lastResultCrop != null && sensor != null && lastResultCrop.width() > 0 && lastResultCrop.height() > 0) {
                    double sensorArea = (double) sensor.width() * sensor.height();
                    double cropArea = (double) lastResultCrop.width() * lastResultCrop.height();
                    lastGeometricZoom = (float) Math.max(1.0, Math.sqrt(sensorArea / Math.max(1.0, cropArea)));
                } else {
                    lastGeometricZoom = 1f;
                }
                updateZoomConfidence(r, rz);
                if (Build.VERSION.SDK_INT >= 28) {
                    String pid = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
                    lastResultPhysicalId = pid == null ? "(nenhum)" : pid;
                }
                Float focal = result.get(CaptureResult.LENS_FOCAL_LENGTH);
                if (focal != null) lastFocalResult = String.format(Locale.US, "%.3fmm", focal);
                updateStreamGeometryDiagnostics();
                if (rz != null) {
                    imageEngineDiagnostics = String.format(Locale.US, "FUSÃO %s • REQ %.2f× • CAM %.2f× • GEO %s • %s • AE %s",
                            targetFrames > 1 ? "PRONTA" : "OFF", lastRequestedZoom, rz,
                            (zoomConfidence == ZoomConfidence.TRUSTED_RATIO || zoomConfidence == ZoomConfidence.TRUSTED_CROP_ONLY) ? String.format(Locale.US, "%.2f×", lastGeometricZoom) : "—",
                            zoomConfidenceLabel(), aeStateName(lastAeState));
                }
                postInvalidate();
            } catch (Exception ignored) { }
        }
    };

    private void resetZoomConfidence(float requested) {
        if (Math.abs(confidenceRequestZoom - requested) > 0.04f) {
            confidenceRequestZoom = requested;
            zoomConfidence = ZoomConfidence.UNKNOWN;
            zoomProbeFrames = 0;
            ratioConsistentFrames = 0;
            cropConsistentFrames = 0;
            contradictionFrames = 0;
        }
    }

    private boolean cropIsFullSensor() {
        return sensor != null && lastResultCrop != null &&
                Math.abs(lastResultCrop.width() - sensor.width()) <= 4 &&
                Math.abs(lastResultCrop.height() - sensor.height()) <= 4;
    }

    /**
     * Layer A (Declared): validates CaptureResult zoom ratio, crop region, and their geometric consistency.
     * Does not blindly trust 1.0x if CaptureResult reports a contradictory zoom ratio (e.g. ~4.62x).
     */
    private void updateZoomConfidence(CaptureRequest request, Float resultZoom) {
        if (request == null) return;
        Float requestedRatio = request.get(CaptureRequest.CONTROL_ZOOM_RATIO);
        float requested = requestedRatio == null ? lastRequestedZoom : requestedRatio;
        if (requested < 1.0f && activePhysicalId != null) {
            requested = 1.0f;
        }
        resetZoomConfidence(requested);
        if (zoomConfidence == ZoomConfidence.UNRELIABLE) return;
        if (zoomConfidence == ZoomConfidence.UNKNOWN) {
            zoomConfidence = ZoomConfidence.PROBING;
        }
        zoomProbeFrames++;

        boolean ratioMatch = resultZoom != null && Math.abs(resultZoom - requested) <= Math.max(0.15f, requested * 0.05f);
        boolean cropMatch = lastResultCrop != null && lastGeometricZoom >= requested * 0.88f && lastGeometricZoom <= requested * 1.12f;
        boolean fullCrop = cropIsFullSensor();

        // Contradiction check: e.g., requested 1.0x but resultZoom reports ~4.62x, or non-full crop disagrees with ratio
        boolean ratioContradicts = resultZoom != null && Math.abs(resultZoom - requested) > Math.max(0.35f, requested * 0.18f);
        boolean cropContradictsWhenNotFull = lastResultCrop != null && !fullCrop && !cropMatch &&
                Math.abs(lastGeometricZoom - requested) > Math.max(0.35f, requested * 0.20f);

        if (ratioMatch && !cropContradictsWhenNotFull) {
            ratioConsistentFrames++;
            contradictionFrames = 0;
        } else {
            ratioConsistentFrames = 0;
        }

        if (cropMatch && !ratioContradicts) {
            cropConsistentFrames++;
            contradictionFrames = 0;
        } else {
            cropConsistentFrames = 0;
        }

        if (ratioContradicts || cropContradictsWhenNotFull) {
            contradictionFrames++;
        }

        if (ratioConsistentFrames >= ZOOM_PROBE_FRAMES) {
            zoomConfidence = ZoomConfidence.TRUSTED_RATIO;
            return;
        }
        if (cropConsistentFrames >= ZOOM_PROBE_FRAMES) {
            zoomConfidence = ZoomConfidence.TRUSTED_CROP_ONLY;
            return;
        }
        if (contradictionFrames >= ZOOM_PROBE_FRAMES ||
            (zoomProbeFrames >= ZOOM_UNRELIABLE_FRAMES && ratioConsistentFrames == 0 && cropConsistentFrames == 0)) {
            zoomConfidence = ZoomConfidence.UNRELIABLE;
        }
    }

    private String cropString(Rect r) {
        return r == null ? "—" : r.left + "," + r.top + "–" + r.right + "," + r.bottom + " (" + r.width() + "×" + r.height() + ")";
    }

    private void updateStreamGeometryDiagnostics() {
        try {
            if (sensor == null || previewSize == null) return;
            double sensorAspect = (double) sensor.width() / Math.max(1, sensor.height());
            double streamAspect = (double) previewSize.getWidth() / Math.max(1, previewSize.getHeight());
            boolean sameAspect = Math.abs(sensorAspect - streamAspect) < 0.02;
            boolean portrait = getHeight() >= getWidth();
            double viewportAspect = portrait ? 3.0 / 4.0 : 4.0 / 3.0;
            float fov = horizontalFov(chars);
            String fovText = fov > 0 ? String.format(Locale.US, "%.2f°", Math.toDegrees(fov)) : "?";
            SizeF phys = chars == null ? null : chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            String physText = phys == null ? "?" : String.format(Locale.US, "%.2f×%.2fmm", phys.getWidth(), phys.getHeight());
            streamGeometryDiagnostics = String.format(Locale.US,
                    "sensor %.3f • stream %.3f • sensor/stream=%s • viewport %.3f • sem esticar • FOV H %s • físico %s",
                    sensorAspect, streamAspect, sameAspect ? "1:1" : "CONVERSÃO", viewportAspect, fovText, physText);
        } catch (Exception ignored) { }
    }

    private String aeStateName(int state) {
        switch (state) {
            case CaptureResult.CONTROL_AE_STATE_CONVERGED: return "CONVERGIDO";
            case CaptureResult.CONTROL_AE_STATE_SEARCHING: return "BUSCANDO";
            case CaptureResult.CONTROL_AE_STATE_LOCKED: return "TRAVADO";
            default: return "—";
        }
    }

    private void applyPreview() {
        if (cameraHandler == null) return;
        cameraHandler.post(() -> {
            try {
                if (session == null || preview.getSurfaceTexture() == null) return;
                CaptureRequest.Builder b = request(CameraDevice.TEMPLATE_PREVIEW);
                if (previewSurface == null) previewSurface = new Surface(preview.getSurfaceTexture());
                b.addTarget(previewSurface);
                session.setRepeatingRequest(b.build(), previewCaptureCallback, cameraHandler);
                post(() -> {
                    transform();
                    status = mode == Mode.NIGHT ? "NIGHT • PRÉ-VISUALIZAÇÃO" : "PRONTO • " + zoomString();
                    postInvalidate();
                });
            } catch (Exception e) {
                post(() -> { status = "ERRO AO APLICAR ZOOM"; postInvalidate(); });
            }
        });
    }

    private void transform() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post(this::transform);
            return;
        }
        if (preview == null || getWidth() <= 0 || getHeight() <= 0 || previewSize == null) return;
        try {
            int rotation = getContext().getDisplay().getRotation();
            Integer so = null;
            if (deviceChars != null) so = deviceChars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int sensorOrientation = so == null ? 90 : so.intValue();
            int displayDegrees = 0;
            if (rotation == Surface.ROTATION_90) displayDegrees = 90;
            else if (rotation == Surface.ROTATION_180) displayDegrees = 180;
            else if (rotation == Surface.ROTATION_270) displayDegrees = 270;
            int relative = (sensorOrientation - displayDegrees + 360) % 360;
            if (relative != 0 && relative != 90 && relative != 180 && relative != 270) relative = 0;

            RectF viewRect = new RectF(0, 0, getWidth(), getHeight());
            GeometryMath.Viewport vp = GeometryMath.compute4x3Viewport(getWidth(), getHeight());
            RectF targetRect = new RectF(vp.left, vp.top, vp.left + vp.width, vp.top + vp.height);

            RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
            float cx = bufferRect.centerX(), cy = bufferRect.centerY();
            bufferRect.offset(targetRect.centerX() - cx, targetRect.centerY() - cy);
            Matrix matrix = new Matrix();
            matrix.setRectToRect(bufferRect, targetRect, Matrix.ScaleToFit.CENTER);

            int displayTransform = 0;
            if (rotation == Surface.ROTATION_90) displayTransform = -90;
            else if (rotation == Surface.ROTATION_270) displayTransform = 90;
            else if (rotation == Surface.ROTATION_180) displayTransform = 180;
            if (displayTransform != 0) matrix.postRotate(displayTransform, viewRect.centerX(), viewRect.centerY());

            preview.setTransform(matrix);

            orientationDiagnostics = "display=" + displayDegrees + "° • sensor=" + sensorOrientation + "° • relative=" + relative +
                    "° • transform=FRAME 4:3 UNIFORM • viewport=" + Math.round(vp.width) + "×" + Math.round(vp.height) +
                    " • buffer=" + sizeString(previewSize);
            updateStreamGeometryDiagnostics();
        } catch (Exception ignored) { }
    }

    private int relativeDisplayDegrees() {
        int sensorOrientation = 90;
        try {
            if (deviceChars != null) {
                Integer so = deviceChars.get(CameraCharacteristics.SENSOR_ORIENTATION);
                if (so != null) sensorOrientation = so;
            }
        } catch (Exception ignored) { }
        int displayDegrees = 0;
        try {
            int r = getContext().getDisplay().getRotation();
            if (r == Surface.ROTATION_90) displayDegrees = 90;
            else if (r == Surface.ROTATION_180) displayDegrees = 180;
            else if (r == Surface.ROTATION_270) displayDegrees = 270;
        } catch (Exception ignored) { }
        return (sensorOrientation - displayDegrees + 360) % 360;
    }

    private void beginAeZoomLock() {
        if (!aeLockSupported) return;
        aeZoomLockedByGesture = true;
        aeUnlockAt = 0L;
    }

    private void scheduleAeUnlock() {
        if (!aeLockSupported) return;
        final long unlockAt = System.currentTimeMillis() + AE_ZOOM_UNLOCK_DELAY_MS;
        aeUnlockAt = unlockAt;
        postDelayed(new Runnable() {
            @Override public void run() {
                if (!aeZoomLockedByGesture && aeUnlockAt == unlockAt && System.currentTimeMillis() >= unlockAt) {
                    aeUnlockAt = 0L;
                    applyPreview();
                } else if (aeUnlockAt == unlockAt) {
                    postDelayed(this, AE_ZOOM_UNLOCK_DELAY_MS);
                }
            }
        }, AE_ZOOM_UNLOCK_DELAY_MS);
    }

    private void selectCamera(String id) {
        if (id == null || id.length() == 0) return;
        manualCameraId = id;
        cameraId = id;
        zoom = 1f;
        showCameraMenu = false;
        close();
        open();
        updateStatusOnUi("CÂMERA ID " + id + " • TESTE MANUAL");
    }

    private void selectWide() { selectWide(0.5f); }

    private void selectWide(float requested) {
        if (!wideSupported || wideCameraId == null) {
            updateStatusOnUi("0,5× INDISPONÍVEL NESTE APARELHO");
            return;
        }
        float nz = Math.max(0.5f, Math.min(0.99f, requested));
        boolean alreadyWide = cameraId != null && cameraId.equals(wideCameraId) &&
                (physicalWideId == null || activePhysicalId != null);
        zoom = nz;
        manualCameraId = null;
        if (!alreadyWide) {
            cameraId = wideCameraId;
            close();
            open();
        } else {
            applyPreview();
        }
        updateStatusOnUi("0,5× • ULTRAWIDE REAL");
    }

    private void selectMain(float z) {
        selectMain(z, true);
    }

    private void selectMain(float z, boolean triggerDiagnostic) {
        if (mainCameraId == null) {
            updateStatusOnUi("CÂMERA PRINCIPAL INDISPONÍVEL");
            return;
        }
        float previousZoom = zoom;
        manualCameraId = null;
        zoom = Math.max(1f, Math.min(Math.max(1f, maxHardware), z));
        boolean needLensReopen = !mainCameraId.equals(cameraId) || activePhysicalId != null;
        if (!needLensReopen && Math.abs(previousZoom - zoom) > 0.01f) beginAeZoomLock();
        cameraId = mainCameraId;
        if (needLensReopen) {
            close();
            open();
        } else {
            applyPreview();
        }
        status = "ZOOM • " + zoomString() + " • " + zoomConfidenceLabel();
        if (triggerDiagnostic) {
            scheduleAutoDiagnostic(zoom);
        }
        postInvalidate();
    }

    private void capture() {
        if (busy || session == null || device == null || reader == null) return;
        busy = true;
        synchronized (frames) {
            frames.clear();
        }
        finalizeScheduled = false;
        int workW = jpegSize == null ? 1920 : Math.max(1, jpegSize.getWidth() / 2);
        int workH = jpegSize == null ? 1440 : Math.max(1, jpegSize.getHeight() / 2);
        if (mode == Mode.NIGHT) targetFrames = MemoryPolicy.safeTargetFrames(4, reader.getMaxImages(), 4, workW, workH, MemoryPolicy.DEFAULT_FRAME_BUDGET_BYTES);
        else if (mode == Mode.MAX) targetFrames = MemoryPolicy.safeTargetFrames(5, reader.getMaxImages(), 4, workW, workH, MemoryPolicy.DEFAULT_FRAME_BUDGET_BYTES);
        else if (mode == Mode.ULTRA) targetFrames = MemoryPolicy.safeTargetFrames(3, reader.getMaxImages(), 4, workW, workH, MemoryPolicy.DEFAULT_FRAME_BUDGET_BYTES);
        else targetFrames = MemoryPolicy.safeTargetFrames(1, reader.getMaxImages(), 4);
        imageEngineDiagnostics = targetFrames > 1 ? "FUSÃO MULTI-FRAME • ALINHAMENTO + MÉDIA ROBUSTA" : "FUSÃO OFF • 1 FRAME";
        completedCaptures = 0;
        status = targetFrames > 1 ? (mode == Mode.NIGHT ? "NIGHT • CAPTURANDO " + targetFrames + " FRAMES" : "FUSÃO • CAPTURANDO " + targetFrames + " FRAMES") : "CAPTURANDO • " + zoomString();
        issueCapture();
        postInvalidate();
    }

    private void issueCapture() {
        if (!busy) return;
        try {
            CaptureRequest.Builder b = request(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(reader.getSurface());
            b.set(CaptureRequest.JPEG_ORIENTATION, rotation());
            session.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
                    completedCaptures++;
                    if (completedCaptures < targetFrames) workHandler.postDelayed(() -> issueCapture(), mode == Mode.NIGHT ? 180 : 90);
                    else {
                        workHandler.post(() -> maybeFinalizeAfterCapture());
                    }
                }
            }, cameraHandler);
        } catch (Exception e) {
            busy = false;
            updateStatusOnUi("FALHA AO FOTOGRAFAR");
        }
    }

    private void finishPhoto(Bitmap bm) {
        if (!busy || bm == null) {
            if (bm != null && !bm.isRecycled()) bm.recycle();
            return;
        }
        boolean recordedFirst = false;
        synchronized (frames) {
            if (frames.size() >= targetFrames) {
                if (!bm.isRecycled()) bm.recycle();
                return;
            }
            recordedFirst = frames.isEmpty();
            frames.add(bm);
        }
        // Read-only diagnostic inspection of the first voluntarily captured JPEG frame;
        // never alters `bm` and never performs hidden captures.
        if (recordedFirst) {
            recordJpegDiagnostic(bm);
        }
        maybeFinalizeAfterCapture();
    }

    private void maybeFinalizeAfterCapture() {
        if (!busy) return;
        int capturedCount;
        synchronized (frames) {
            capturedCount = frames.size();
        }
        if (completedCaptures >= targetFrames && capturedCount >= targetFrames) {
            finalizeCapture();
        } else if (completedCaptures >= targetFrames && !finalizeScheduled) {
            finalizeScheduled = true;
            workHandler.postDelayed(() -> {
                if (busy && !frames.isEmpty()) finalizeCapture();
                else finalizeScheduled = false;
            }, 1500);
        }
    }

    private Bitmap fuseFrames(List<Bitmap> input) {
        if (input == null || input.size() < 2) return input == null || input.isEmpty() ? null : input.get(0);
        Bitmap ref = input.get(0);
        if (ref == null || ref.isRecycled()) return null;
        int w = ref.getWidth(), h = ref.getHeight();
        ArrayList<int[]> shifts = new ArrayList<int[]>();
        shifts.add(new int[]{0, 0});
        for (int i = 1; i < input.size(); i++) shifts.add(estimateShift(ref, input.get(i)));
        lastZoomDx = shifts.get(shifts.size() - 1)[0];
        lastZoomDy = shifts.get(shifts.size() - 1)[1];
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int[][] rows = new int[input.size()][];
        int[] outRow = new int[w];
        for (int y = 0; y < h; y++) {
            for (int f = 0; f < input.size(); f++) {
                Bitmap bm = input.get(f);
                int[] sh = shifts.get(f);
                int sy = y + sh[1];
                if (bm == null || bm.isRecycled() || sy < 0 || sy >= h) rows[f] = null;
                else {
                    if (rows[f] == null || rows[f].length != w) rows[f] = new int[w];
                    bm.getPixels(rows[f], 0, w, 0, sy, w, 1);
                }
            }
            for (int x = 0; x < w; x++) {
                int[] rv = new int[input.size()];
                int[] gv = new int[input.size()];
                int[] bv = new int[input.size()];
                int count = 0;
                for (int f = 0; f < input.size(); f++) {
                    int[] row = rows[f];
                    int sx = x + shifts.get(f)[0];
                    if (row == null || sx < 0 || sx >= w) continue;
                    int c = row[sx];
                    rv[count] = Color.red(c); gv[count] = Color.green(c); bv[count] = Color.blue(c);
                    count++;
                }
                if (count == 0) {
                    outRow[x] = ref.getPixel(Math.max(0, Math.min(w - 1, x)), y);
                } else {
                    outRow[x] = Color.rgb(robustAverage(rv, count), robustAverage(gv, count), robustAverage(bv, count));
                }
            }
            out.setPixels(outRow, 0, w, 0, y, w, 1);
        }
        return out;
    }

    private int robustAverage(int[] values, int n) {
        if (n <= 1) return values[0];
        int sum = 0, min = 255, max = 0;
        for (int i = 0; i < n; i++) { sum += values[i]; min = Math.min(min, values[i]); max = Math.max(max, values[i]); }
        if (n >= 4) return Math.round((sum - min - max) / (float) (n - 2));
        if (n == 3) {
            int a = values[0], b = values[1], c = values[2];
            return a + b + c - Math.min(a, Math.min(b, c)) - Math.max(a, Math.max(b, c));
        }
        return Math.round(sum / (float) n);
    }

    private int[] estimateShift(Bitmap ref, Bitmap other) {
        if (ref == null || other == null || ref.isRecycled() || other.isRecycled()) return new int[]{0, 0};
        int tw = 240, th = Math.max(80, Math.round(240f * ref.getHeight() / Math.max(1f, ref.getWidth())));
        Bitmap a = Bitmap.createScaledBitmap(ref, tw, th, true);
        Bitmap b = Bitmap.createScaledBitmap(other, tw, th, true);
        int bestDx = 0, bestDy = 0; double best = Double.MAX_VALUE;
        for (int dy = -6; dy <= 6; dy++) {
            for (int dx = -6; dx <= 6; dx++) {
                double err = 0; int n = 0;
                for (int y = 18; y < th - 18; y += 4) {
                    int by = y + dy; if (by < 0 || by >= th) continue;
                    for (int x = 18; x < tw - 18; x += 4) {
                        int bx = x + dx; if (bx < 0 || bx >= tw) continue;
                        int ca = a.getPixel(x, y), cb = b.getPixel(bx, by);
                        int la = (Color.red(ca) * 54 + Color.green(ca) * 183 + Color.blue(ca) * 19) >> 8;
                        int lb = (Color.red(cb) * 54 + Color.green(cb) * 183 + Color.blue(cb) * 19) >> 8;
                        int d = la - lb; err += d * d; n++;
                    }
                }
                double score = err / Math.max(1, n);
                if (score < best) { best = score; bestDx = dx; bestDy = dy; }
            }
        }
        if (a != ref && !a.isRecycled()) a.recycle();
        if (b != other && !b.isRecycled()) b.recycle();
        int fullDx = Math.round(bestDx * (wScale(ref, tw)));
        int fullDy = Math.round(bestDy * (hScale(ref, th)));
        return new int[]{fullDx, fullDy};
    }

    private float wScale(Bitmap b, int tw) { return b.getWidth() / (float) Math.max(1, tw); }
    private float hScale(Bitmap b, int th) { return b.getHeight() / (float) Math.max(1, th); }

    private void adaptiveSharpen(Bitmap bm, float amount) {
        if (bm == null || bm.isRecycled() || amount <= 0) return;
        int w = bm.getWidth(), h = bm.getHeight();
        int[] prev = new int[w], cur = new int[w], next = new int[w], out = new int[w];
        bm.getPixels(cur, 0, w, 0, 0, w, 1);
        if (h > 1) bm.getPixels(next, 0, w, 0, 1, w, 1);
        for (int y = 1; y < h - 1; y++) {
            int[] tmp = prev; prev = cur; cur = next; next = tmp;
            bm.getPixels(next, 0, w, 0, y + 1, w, 1);
            for (int x = 1; x < w - 1; x++) {
                int c = cur[x];
                int lum = (Color.red(c) * 54 + Color.green(c) * 183 + Color.blue(c) * 19) >> 8;
                int avg = ((Color.red(cur[x - 1]) * 54 + Color.green(cur[x - 1]) * 183 + Color.blue(cur[x - 1]) * 19) +
                        (Color.red(cur[x + 1]) * 54 + Color.green(cur[x + 1]) * 183 + Color.blue(cur[x + 1]) * 19) +
                        (Color.red(prev[x]) * 54 + Color.green(prev[x]) * 183 + Color.blue(prev[x]) * 19) +
                        (Color.red(next[x]) * 54 + Color.green(next[x]) * 183 + Color.blue(next[x]) * 19)) >> 10;
                int detail = lum - avg;
                int rr = clamp255(Math.round(Color.red(c) + detail * amount));
                int gg = clamp255(Math.round(Color.green(c) + detail * amount));
                int bb = clamp255(Math.round(Color.blue(c) + detail * amount));
                out[x] = Color.rgb(rr, gg, bb);
            }
            out[0] = cur[0]; out[w - 1] = cur[w - 1];
            bm.setPixels(out, 0, w, 0, y, w, 1);
        }
    }

    private void finalizeCapture() {
        if (!busy) return;
        Bitmap best = null;
        Bitmap output = null;
        synchronized (frames) {
            if (!frames.isEmpty()) {
                if (targetFrames > 1 && frames.size() > 1) {
                    output = fuseFrames(new ArrayList<Bitmap>(frames));
                    best = frames.get(0);
                } else {
                    best = frames.get(0);
                    for (Bitmap b : frames) if (b != null && !b.isRecycled() && sharpnessScore(b) > sharpnessScore(best)) best = b;
                    output = best;
                }
            }
        }
        boolean savedOk = false;
        if (output != null && !output.isRecycled()) {
            if (targetFrames > 1) {
                adaptiveSharpen(output, zoom > 8f ? 0.14f : (zoom > 4f ? 0.09f : 0.05f));
            }
            if (mode == Mode.NIGHT) {
                Bitmap night = liftNight(output);
                if (night != output && output != best && !output.isRecycled()) {
                    output.recycle();
                }
                output = night;
            }
            savedOk = save(output, mode == Mode.NIGHT ? "NIGHT" : MODES[mode.ordinal()]);
            if (output != best && !output.isRecycled()) {
                output.recycle();
            }
        }
        synchronized (frames) {
            for (Bitmap b : frames) {
                if (b != null && !b.isRecycled()) b.recycle();
            }
            frames.clear();
        }
        busy = false;
        finalizeScheduled = false;
        final boolean hadFrame = best != null;
        final boolean wasSaved = savedOk;
        post(() -> {
            if (!hadFrame) status = "NÃO FOI POSSÍVEL FOTOGRAFAR";
            else if (!wasSaved) status = "ERRO AO SALVAR FOTO";
            else status = "FOTO SALVA • " + zoomString();
            postInvalidate();
            postDelayed(() -> {
                if (!busy) {
                    status = MODES[mode.ordinal()] + " • PRONTO";
                    postInvalidate();
                }
            }, 1800);
        });
    }

    private double sharpnessScore(Bitmap b) {
        if (b == null || b.isRecycled()) return 0.0;
        int w = Math.min(180, b.getWidth()), h = Math.min(140, b.getHeight());
        Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
        int[] px = new int[w * h];
        s.getPixels(px, 0, w, 0, 0, w, h);
        if (s != b && !s.isRecycled()) s.recycle();
        double sum = 0, sum2 = 0; int n = 0;
        for (int y = 1; y < h - 1; y += 2) for (int x = 1; x < w - 1; x += 2) {
            int i = y * w + x;
            int center = Color.red(px[i]) + Color.green(px[i]) + Color.blue(px[i]);
            int l = Color.red(px[i - 1]) + Color.green(px[i - 1]) + Color.blue(px[i - 1]);
            int r = Color.red(px[i + 1]) + Color.green(px[i + 1]) + Color.blue(px[i + 1]);
            int u = Color.red(px[i - w]) + Color.green(px[i - w]) + Color.blue(px[i - w]);
            int d = Color.red(px[i + w]) + Color.green(px[i + w]) + Color.blue(px[i + w]);
            double q = center * 4d - l - r - u - d;
            sum += q; sum2 += q * q; n++;
        }
        double mean = sum / Math.max(1, n);
        return sum2 / Math.max(1, n) - mean * mean;
    }

    private Bitmap liftNight(Bitmap src) {
        Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
        if (out == null) return src;
        int w = out.getWidth(), h = out.getHeight();
        int[] px = new int[w * h];
        out.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) {
            int r = nightTone(Color.red(px[i]));
            int g = nightTone(Color.green(px[i]));
            int b = nightTone(Color.blue(px[i]));
            px[i] = Color.rgb(r, g, b);
        }
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    private int nightTone(int v) {
        double n = v / 255d;
        double lifted = Math.pow(Math.min(1d, n * 1.24d), .80d);
        return Math.max(0, Math.min(255, (int) (lifted * 255d)));
    }

    private int rotation() {
        int displayDegrees = 0;
        try {
            int r = getContext().getDisplay().getRotation();
            if (r == Surface.ROTATION_90) displayDegrees = 90;
            else if (r == Surface.ROTATION_180) displayDegrees = 180;
            else if (r == Surface.ROTATION_270) displayDegrees = 270;
        } catch (Exception ignored) { }
        int sensorOrientation = 90;
        try {
            if (deviceChars != null) {
                Integer so = deviceChars.get(CameraCharacteristics.SENSOR_ORIENTATION);
                if (so != null) sensorOrientation = so;
            }
        } catch (Exception ignored) { }
        return (sensorOrientation + displayDegrees) % 360;
    }

    private boolean save(Bitmap b, String tag) {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "UltraZoom_" + tag + "_" + System.currentTimeMillis() + ".jpg");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UltraZoom");
            Uri u = getContext().getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (u != null) {
                OutputStream o = getContext().getContentResolver().openOutputStream(u);
                if (o != null) {
                    b.compress(Bitmap.CompressFormat.JPEG, 97, o);
                    o.close();
                }
            }
            return u != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void focus(float x, float y) {
        if (session == null || device == null || sensor == null) return;
        focusX = x; focusY = y; focusUntil = System.currentTimeMillis() + 1200;

        int relative = relativeDisplayDegrees();
        GeometryMath.Point norm = GeometryMath.mapTouchToSensorNormalized(x, y, getWidth(), getHeight(), relative);
        Rect activeArea = effectiveMapCrop();
        if (activeArea == null || activeArea.width() <= 0 || activeArea.height() <= 0) activeArea = sensor;

        int rw = Math.max(80, activeArea.width() / 10);
        int rh = Math.max(80, activeArea.height() / 10);
        int cx = activeArea.left + Math.round(norm.x * activeArea.width());
        int cy = activeArea.top + Math.round(norm.y * activeArea.height());
        Rect r = new Rect(cx - rw / 2, cy - rh / 2, cx + rw / 2, cy + rh / 2);
        r.intersect(activeArea);
        focusRegion = r;

        final int seq = ++afSequenceId;
        afStateMachine.cancel();
        focusDiagnostics = "AF estado=CANCELLING • tentativa #" + (afStateMachine.getAttempts() + 1);

        cameraHandler.post(() -> {
            try {
                if (session == null || device == null || seq != afSequenceId) return;
                CaptureRequest.Builder b = request(CameraDevice.TEMPLATE_PREVIEW);
                if (previewSurface == null) previewSurface = new Surface(preview.getSurfaceTexture());
                b.addTarget(previewSurface);
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                session.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                    @Override public void onCaptureCompleted(CameraCaptureSession ss, CaptureRequest rr, TotalCaptureResult result) {
                        cameraHandler.postDelayed(() -> startFocusAfterCancel(seq), 70);
                    }
                }, cameraHandler);
            } catch (Exception ignored) {
                restorePreviewRepeating();
            }
        });
        postInvalidate();
    }

    private boolean supportsAfAuto() {
        if (chars == null) return false;
        int[] modes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        return hasCapability(modes, CaptureRequest.CONTROL_AF_MODE_AUTO);
    }

    private void startFocusAfterCancel(final int seq) {
        try {
            if (session == null || device == null || seq != afSequenceId) return;
            afStateMachine.start();
            int afMode = supportsAfAuto() ? CaptureRequest.CONTROL_AF_MODE_AUTO : CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;

            CaptureRequest.Builder repeatDuringAf = request(CameraDevice.TEMPLATE_PREVIEW);
            if (previewSurface == null) previewSurface = new Surface(preview.getSurfaceTexture());
            repeatDuringAf.addTarget(previewSurface);
            repeatDuringAf.set(CaptureRequest.CONTROL_AF_MODE, afMode);
            repeatDuringAf.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            session.setRepeatingRequest(repeatDuringAf.build(), previewCaptureCallback, cameraHandler);

            CaptureRequest.Builder trigger = request(CameraDevice.TEMPLATE_PREVIEW);
            trigger.addTarget(previewSurface);
            trigger.set(CaptureRequest.CONTROL_AF_MODE, afMode);
            trigger.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            session.capture(trigger.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession ss, CaptureRequest rr, TotalCaptureResult result) {
                    if (seq != afSequenceId) return;
                    Integer af = result.get(CaptureResult.CONTROL_AF_STATE);
                    afStateMachine.result(af);
                    focusDiagnostics = "AF estado=" + afStateName(af) + " • " + afStateMachine.get().name() + " • CANCEL→START";
                    if (afStateMachine.isTerminal()) {
                        restorePreviewRepeating();
                    }
                    postInvalidate();
                }
            }, cameraHandler);

            cameraHandler.postDelayed(() -> {
                if (seq != afSequenceId) return;
                if (!afStateMachine.isTerminal()) {
                    afStateMachine.timeout();
                    focusDiagnostics = "AF estado=TIMEOUT • recuperando repeating request";
                    restorePreviewRepeating();
                    postInvalidate();
                }
            }, 1200);
        } catch (Exception ignored) {
            restorePreviewRepeating();
        }
    }

    private void restorePreviewRepeating() {
        try {
            if (session == null || device == null) return;
            CaptureRequest.Builder repeat = request(CameraDevice.TEMPLATE_PREVIEW);
            if (previewSurface == null) previewSurface = new Surface(preview.getSurfaceTexture());
            repeat.addTarget(previewSurface);
            repeat.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            repeat.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            session.setRepeatingRequest(repeat.build(), previewCaptureCallback, cameraHandler);
        } catch (Exception ignored) { }
    }

    private String afStateName(Integer af) {
        if (af == null) return "—";
        switch (af) {
            case CaptureResult.CONTROL_AF_STATE_INACTIVE: return "INACTIVE";
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN: return "PASSIVE_SCAN";
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED: return "PASSIVE_FOCUSED";
            case CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN: return "ACTIVE_SCAN";
            case CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED: return "FOCUSED_LOCKED";
            case CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED: return "NOT_FOCUSED_LOCKED";
            case CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED: return "PASSIVE_UNFOCUSED";
            default: return String.valueOf(af);
        }
    }

    private void captureMiniIfNeeded() {
        if (miniReader == null || !miniInSession || session == null || busy) return;
        long now = System.currentTimeMillis();
        if (miniCapturePending || now - lastMiniCapture < 1200) return;
        miniCapturePending = true;
        lastMiniCapture = now;
        cameraHandler.post(() -> {
            try {
                CaptureRequest.Builder b = requestAtZoom(CameraDevice.TEMPLATE_PREVIEW, 1f);
                b.addTarget(miniReader.getSurface());
                if (flashAvailable) {
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                    b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                }
                session.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                    @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) { }
                    @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, android.hardware.camera2.CaptureFailure f) {
                        miniCapturePending = false;
                    }
                }, cameraHandler);
            } catch (Exception e) {
                miniCapturePending = false;
            }
        });
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private void persistDiagnosticState() {
        try {
            android.content.SharedPreferences sp = getContext().getSharedPreferences("uz_diagnostic_124", Context.MODE_PRIVATE);
            sp.edit().putString("store_v124", diagnosticStore.serialize()).apply();
        } catch (Exception ignored) { }
    }

    private void loadDiagnosticState() {
        try {
            android.content.SharedPreferences sp = getContext().getSharedPreferences("uz_diagnostic_124", Context.MODE_PRIVATE);
            diagnosticStore.deserialize(sp.getString("store_v124", ""));
            diagnosticLastSampleZoom = diagnosticStore.getLastSampleZoom();
        } catch (Exception ignored) { }
    }

    private String diagnosticSummary() {
        return "A=" + zoomConfidenceLabel() +
                " • B=" + diagnosticStore.getLayerBState().name() +
                " • C=" + diagnosticStore.getLayerCState().name() +
                " • GLOBAL=" + diagnosticGlobalState() +
                " • pares=" + diagnosticStore.getTotalPairsStored() +
                " • níveis confirmados=" + diagnosticStore.getConfirmedLevelsCount();
    }

    private String diagnosticGlobalState() {
        ZoomConfidenceEngine.State combined = ZoomConfidenceEngine.combine(
                mapLayerAState(),
                diagnosticStore.getLayerBState(),
                diagnosticStore.getLayerCState());
        return combined.name();
    }

    private void scheduleAutoDiagnostic(final float requestedZoom) {
        if (requestedZoom < 1f || getWidth() <= 0 || getHeight() <= 0) return;
        diagnosticPendingZoom = requestedZoom;
        if (diagnosticPending) return;
        diagnosticPending = true;
        postDelayed(() -> {
            diagnosticPending = false;
            if (isBusyForDiagnostic()) return;
            try {
                Bitmap bm = preview.getBitmap(320, 240);
                if (bm == null) return;
                final float z = diagnosticPendingZoom;
                workHandler.post(() -> processPreviewDiagnostic(bm, z));
            } catch (Exception ignored) { }
        }, DIAGNOSTIC_DELAY_MS);
    }

    private boolean isBusyForDiagnostic() {
        if (busy || getVisibility() != View.VISIBLE || !hasWindowFocus()) return true;
        try {
            BatteryManager bm = (BatteryManager) getContext().getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                int pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                if (pct >= 0 && pct < 20) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void processPreviewDiagnostic(Bitmap bm, float z) {
        byte[] gray = toGray(bm, 160, 120);
        if (!bm.isRecycled()) bm.recycle();
        if (gray == null) return;
        if (diagnosticLastGray != null && DiagnosticSessionStore.hasSufficientOverlap(diagnosticLastSampleZoom, z)) {
            float ratio = z / Math.max(0.01f, diagnosticLastSampleZoom);
            float expected = ratio >= 1f ? ratio : 1f / Math.max(0.01f, ratio);
            ScaleEstimator.Result r = ratio >= 1f
                    ? ScaleEstimator.estimate(diagnosticLastGray, 160, 120, gray, 160, 120, Math.max(1f, expected * 0.65f), Math.min(5f, expected * 1.35f))
                    : ScaleEstimator.estimate(gray, 160, 120, diagnosticLastGray, 160, 120, Math.max(1f, expected * 0.65f), Math.min(5f, expected * 1.35f));
            diagnosticStore.recordObservedPair(diagnosticLastSampleZoom, z, r.scale, r.confidence);
            persistDiagnosticState();
        }
        diagnosticLastGray = gray;
        diagnosticLastSampleZoom = z;
        postInvalidate();
        persistDiagnosticLog(false);
    }

    private byte[] toGray(Bitmap src, int w, int h) {
        if (src == null || src.isRecycled()) return null;
        try {
            Bitmap s = Bitmap.createScaledBitmap(src, w, h, true);
            int[] px = new int[w * h];
            s.getPixels(px, 0, w, 0, 0, w, h);
            if (s != src && !s.isRecycled()) s.recycle();
            byte[] out = new byte[w * h];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Math.round(0.299f * Color.red(px[i]) + 0.587f * Color.green(px[i]) + 0.114f * Color.blue(px[i]));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Layer C (Inferred): inspects native pixel acutance and center thumbnail structure
     * from a JPEG voluntarily captured by the user without mutating the Bitmap.
     */
    private void recordJpegDiagnostic(Bitmap bm) {
        if (bm == null || bm.isRecycled()) return;
        try {
            int bw = bm.getWidth();
            int bh = bm.getHeight();
            int roiW = Math.min(256, bw);
            int roiH = Math.min(256, bh);
            if (roiW < 16 || roiH < 16) return;
            int startX = (bw - roiW) / 2;
            int startY = (bh - roiH) / 2;
            int[] roi = new int[roiW * roiH];
            bm.getPixels(roi, 0, roiW, startX, startY, roiW, roiH);

            double fineSum = 0;
            double coarseSum = 0;
            int fineN = 0;
            int coarseN = 0;
            int edgeCount = 0;

            for (int y = 4; y < roiH - 4; y += 2) {
                for (int x = 4; x < roiW - 4; x += 2) {
                    int idx = y * roiW + x;
                    int lumC = lumOf(roi[idx]);
                    int lumL1 = lumOf(roi[idx - 1]);
                    int lumR1 = lumOf(roi[idx + 1]);
                    int lumU1 = lumOf(roi[idx - roiW]);
                    int lumD1 = lumOf(roi[idx + roiW]);
                    int gx1 = Math.abs(lumR1 - lumL1);
                    int gy1 = Math.abs(lumD1 - lumU1);
                    int lap1 = Math.abs(4 * lumC - lumL1 - lumR1 - lumU1 - lumD1);
                    fineSum += (gx1 + gy1 + lap1);
                    fineN++;
                    if (gx1 + gy1 > 32) edgeCount++;

                    int lumL4 = lumOf(roi[idx - 4]);
                    int lumR4 = lumOf(roi[idx + 4]);
                    int lumU4 = lumOf(roi[idx - 4 * roiW]);
                    int lumD4 = lumOf(roi[idx + 4 * roiW]);
                    coarseSum += (Math.abs(lumR4 - lumL4) + Math.abs(lumD4 - lumU4)) * 0.25;
                    coarseN++;
                }
            }

            float fineDetail = (float) (fineSum / Math.max(1, fineN));
            float coarseDetail = (float) (coarseSum / Math.max(1, coarseN));
            byte[] thumbGray = toGray(bm, 160, 120);

            diagnosticStore.recordJpegEvidence(
                    zoom, bw, bh, fineDetail, coarseDetail, edgeCount, thumbGray, 160, 120);
            persistDiagnosticState();
            persistDiagnosticLog(false);
        } catch (Exception ignored) { }
    }

    private int lumOf(int argb) {
        return (Color.red(argb) * 77 + Color.green(argb) * 150 + Color.blue(argb) * 29) >> 8;
    }

    private void persistDiagnosticLog(boolean force) {
        if (!force && diagnosticStore.getTotalSamples() % 5 != 0) return;
        try {
            java.io.FileOutputStream out = getContext().openFileOutput("ultrazoom_diagnostico.json", Context.MODE_PRIVATE);
            out.write(diagnosticJson().getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) { }
    }

    private String diagnosticJson() {
        StringBuilder j = new StringBuilder();
        float lastObs = diagnosticStore.getLastObservedScale();
        j.append("{\n  \"build\":\"").append(BUILD_ID).append("\",\n");
        j.append("  \"device\":\"").append(escapeJson(Build.MANUFACTURER + " " + Build.MODEL)).append("\",\n");
        j.append("  \"camera\":\"").append(escapeJson(cameraId == null ? "" : cameraId)).append("\",\n");
        j.append("  \"maxHardware\":").append(String.format(Locale.US, "%.3f", maxHardware)).append(",\n");
        j.append("  \"maxSamplesPerLevel\":").append(MAX_SAMPLES_PER_LEVEL).append(",\n");
        j.append("  \"maxTotalSamples\":").append(MAX_TOTAL_DIAGNOSTIC_SAMPLES).append(",\n");
        j.append("  \"samples\":").append(diagnosticStore.getTotalSamples()).append(",\n");
        j.append("  \"pairs\":").append(diagnosticStore.getTotalPairsStored()).append(",\n");
        j.append("  \"last\":{\"zoom\":").append(String.format(Locale.US, "%.3f", diagnosticStore.getLastSampleZoom()));
        j.append(",\"observedScale\":").append(Float.isNaN(lastObs) ? "null" : String.format(Locale.US, "%.3f", lastObs));
        j.append(",\"confidence\":").append(String.format(Locale.US, "%.3f", diagnosticStore.getLastObservedConfidence()));
        j.append(",\"A\":\"").append(zoomConfidenceLabel())
         .append("\",\"B\":\"").append(diagnosticStore.getLayerBState().name())
         .append("\",\"C\":\"").append(diagnosticStore.getLayerCState().name())
         .append("\",\"global\":\"").append(diagnosticGlobalState()).append("\"},\n");
        j.append("  \"levels\":[");
        int n = 0;
        for (Map.Entry<Float, DiagnosticSessionStore.LevelRecord> x : diagnosticStore.getLevels().entrySet()) {
            if (n++ > 0) j.append(',');
            DiagnosticSessionStore.LevelRecord e = x.getValue();
            j.append("{\"zoom\":").append(String.format(Locale.US, "%.1f", x.getKey()))
             .append(",\"state\":\"").append(e.state.name())
             .append("\",\"good\":").append(e.good)
             .append(",\"contradictions\":").append(e.contradictions).append('}');
        }
        j.append("],\n  \"pairRecords\":[");
        n = 0;
        for (Map.Entry<String, DiagnosticSessionStore.PairRecord> x : diagnosticStore.getPairs().entrySet()) {
            if (n++ > 0) j.append(',');
            DiagnosticSessionStore.PairRecord pr = x.getValue();
            j.append("{\"pair\":\"").append(escapeJson(x.getKey()))
             .append("\",\"state\":\"").append(pr.state.name())
             .append("\",\"good\":").append(pr.good)
             .append(",\"contradictions\":").append(pr.contradictions).append('}');
        }
        j.append("],\n  \"details\":[");
        n = 0;
        for (Map.Entry<Float, DiagnosticSessionStore.JpegRecord> x : diagnosticStore.getJpegs().entrySet()) {
            if (n++ > 0) j.append(',');
            DiagnosticSessionStore.JpegRecord d = x.getValue();
            j.append("{\"zoom\":").append(String.format(Locale.US, "%.1f", x.getKey()))
             .append(",\"fineDetail\":").append(String.format(Locale.US, "%.3f", d.fineDetail))
             .append(",\"acutanceRatio\":").append(String.format(Locale.US, "%.3f", d.acutanceRatio))
             .append(",\"state\":\"").append(d.state.name())
             .append("\",\"size\":\"").append(d.width).append("x").append(d.height).append("\"}");
        }
        return j.append("]\n}\n").toString();
    }

    private void exportDiagnosticLog() {
        persistDiagnosticLog(true);
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Files.FileColumns.DISPLAY_NAME, "UltraZoom_diagnostico_" + System.currentTimeMillis() + ".json");
            v.put(MediaStore.Files.FileColumns.MIME_TYPE, "application/json");
            v.put(MediaStore.Files.FileColumns.RELATIVE_PATH, "Pictures/UltraZoom/diagnostico");
            Uri u = getContext().getContentResolver().insert(MediaStore.Files.getContentUri("external"), v);
            if (u != null) {
                OutputStream out = getContext().getContentResolver().openOutputStream(u);
                if (out != null) {
                    out.write(diagnosticJson().getBytes("UTF-8"));
                    out.close();
                }
                Toast.makeText(getContext(), "Diagnóstico exportado para Pictures/UltraZoom/diagnostico", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(getContext(), "Não foi possível exportar o diagnóstico", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(getContext(), "Falha ao exportar diagnóstico", Toast.LENGTH_LONG).show();
        }
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) { return true; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int w = getWidth(), h = getHeight();
        float x = e.getX(), y = e.getY();
        ui.compute(w, h);
        validateLayout();

        if (e.getPointerCount() == 2 || pinchDistance > 0) {
            if (e.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() >= 2) {
                pinchDistance = distance(e);
                pinchStart = zoom;
                beginAeZoomLock();
            } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && e.getPointerCount() >= 2 && pinchDistance > 0) {
                float minAllowed = wideSupported ? 0.5f : 1.0f;
                float nz = Math.max(minAllowed, Math.min(Math.max(1f, maxHardware), pinchStart * distance(e) / pinchDistance));
                if (wideSupported && nz < 0.96f) {
                    pinchWideActive = true;
                    selectWide(nz);
                } else {
                    if (pinchWideActive || nz >= 1f) {
                        // Do not run diagnostic comparison on every move event; wait for gesture end (at most 1 per gesture).
                        selectMain(Math.max(1f, nz), false);
                        pinchWideActive = false;
                    }
                }
            } else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                pinchDistance = 0;
                pinchWideActive = false;
                aeZoomLockedByGesture = false;
                scheduleAeUnlock();
                if (zoom >= 1f) {
                    scheduleAutoDiagnostic(zoom);
                }
            }
            return true;
        }
        if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;

        if (showInfo) {
            showInfo = false;
            postInvalidate();
            return true;
        }
        if (showCameraMenu) {
            float top = 132 * ui.scale;
            int shown = Math.min(8, publicCameraRecords.size());
            for (int i = 0; i < shown; i++) {
                float bottom = top + 58 * ui.scale;
                if (y >= top && y <= bottom) { selectCamera(publicCameraRecords.get(i).id); return true; }
                top += 66 * ui.scale;
            }
            if (y < 120 * ui.scale || y > h - 34 * ui.scale) { showCameraMenu = false; postInvalidate(); }
            return true;
        }
        if (showModeMenu) {
            float top = 174 * ui.scale;
            if (y >= top && y <= top + MODES.length * 79 * ui.scale) {
                int i = (int) ((y - top) / (79 * ui.scale));
                if (i >= 0 && i < MODES.length) {
                    mode = Mode.values()[i]; showModeMenu = false; status = MODES[i] + " • PRONTO"; applyPreview(); postInvalidate();
                }
            } else { showModeMenu = false; postInvalidate(); }
            return true;
        }
        if (ui.logExport.contains(x, y)) { exportDiagnosticLog(); return true; }
        if (ui.camera.contains(x, y)) { showCameraMenu = true; postInvalidate(); return true; }
        if (ui.info.contains(x, y)) { showInfo = true; postInvalidate(); return true; }
        if (ui.flash.contains(x, y)) {
            if (!flashAvailable) status = "FLASH INDISPONÍVEL";
            else { flashMode = (flashMode + 1) % 3; applyPreview(); status = flashLabel(); }
            postInvalidate(); return true;
        }
        if (ui.mode.contains(x, y)) { showModeMenu = true; postInvalidate(); return true; }
        if (ui.shutter.contains(x, y)) { capture(); return true; }
        float[] targets = lensTargets();
        for (int i = 0; i < ui.lens.length; i++) {
            if (ui.lens[i].contains(x, y)) {
                if (i == 0) selectWide();
                else selectMain(targets[i]);
                return true;
            }
        }
        if (ui.zoomArea.contains(x, y)) {
            float ratio = Math.max(0f, Math.min(1f, (x - ui.zoomArea.left) / Math.max(1f, ui.zoomArea.width())));
            double minSlider = wideSupported ? 0.5 : 1.0;
            double maxSlider = Math.max(minSlider + 0.01, maxHardware);
            float nz = (float) Math.exp(Math.log(minSlider) + ratio * (Math.log(maxSlider) - Math.log(minSlider)));
            if (wideSupported && nz < 0.96f) selectWide(nz);
            else selectMain(Math.max(1f, nz));
            return true;
        }
        focus(x, y);
        return true;
    }

    private float distance(MotionEvent e) {
        float dx = e.getX(1) - e.getX(0), dy = e.getY(1) - e.getY(0);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    void start() {
        startThreads();
        if (preview.isAvailable() && device == null) open();
    }

    void stop() { close(); }

    private void close() {
        aeZoomLockedByGesture = false;
        aeUnlockAt = 0L;
        pinchDistance = 0f;
        pinchWideActive = false;
        zoomConfidence = ZoomConfidence.UNKNOWN;
        zoomProbeFrames = ratioConsistentFrames = cropConsistentFrames = contradictionFrames = 0;
        try { if (session != null) { session.close(); session = null; } } catch (Exception ignored) { }
        try { if (device != null) { device.close(); device = null; } } catch (Exception ignored) { }
        try { if (previewSurface != null) { previewSurface.release(); previewSurface = null; } } catch (Exception ignored) { }
        miniCapturePending = false;
        miniInSession = false;
        if (miniBitmap != null && !miniBitmap.isRecycled()) { miniBitmap.recycle(); miniBitmap = null; }
    }

    void shutdown() {
        close();
        try { if (reader != null) reader.close(); } catch (Exception ignored) { }
        try { if (miniReader != null) miniReader.close(); } catch (Exception ignored) { }
        if (miniBitmap != null && !miniBitmap.isRecycled()) miniBitmap.recycle();
        try { if (cameraThread != null) cameraThread.quitSafely(); } catch (Exception ignored) { }
        try { if (workThread != null) workThread.quitSafely(); } catch (Exception ignored) { }
    }

}
