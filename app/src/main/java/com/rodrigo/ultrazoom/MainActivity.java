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
import com.rodrigo.ultrazoom.diagnostic.CapturePlanner;
import com.rodrigo.ultrazoom.diagnostic.DiagnosticSessionStore;
import com.rodrigo.ultrazoom.diagnostic.GeometryMath;
import com.rodrigo.ultrazoom.diagnostic.MemoryPolicy;
import com.rodrigo.ultrazoom.diagnostic.ScaleEstimator;
import com.rodrigo.ultrazoom.diagnostic.SuperResolutionEngine;
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

    public View getCameraView() { return camera; }
    public boolean isHudLayoutValid() { return camera != null && camera.isLayoutValid(); }
    public String getHudLayoutDiagnostic() { return camera == null ? "NULL" : camera.getLayoutDiagnostic(); }
    public RectF getMiniRect() { return camera == null ? new RectF() : new RectF(camera.getUiMini()); }
    public RectF getZoomAreaRect() { return camera == null ? new RectF() : new RectF(camera.getUiZoomArea()); }
    public RectF getZoomBadgeRect() { return camera == null ? new RectF() : new RectF(camera.getUiZoomBadge()); }
    public RectF getTelemetryCardRect() { return camera == null ? new RectF() : new RectF(camera.getUiTelemetryCard()); }
    public RectF getLensRect(int idx) { return camera == null ? new RectF() : new RectF(camera.getUiLens(idx)); }
    public float getUiScale() { return camera == null ? 1f : camera.getUiScale(); }
    public void setModalStateForAudit(boolean info, boolean modeMenu, boolean cameraMenu) {
        if (camera != null) camera.setModalStateForAudit(info, modeMenu, cameraMenu);
    }
    public void populateSampleHardwareStateForAudit(boolean wide, float maxZ, float currentZoom) {
        if (camera != null) camera.populateSampleHardwareStateForAudit(wide, maxZ, currentZoom);
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
    private boolean awbLockSupported;
    private boolean oisSupported;
    private static final float MAX_SR_FACTOR = 3.0f;
    private int lastZoomDx;
    private int lastZoomDy;
    private volatile String imageEngineDiagnostics = "FUSÃO SR MULTI-FRAME • PRONTA";
    private final List<CameraRecord> publicCameraRecords = new ArrayList<CameraRecord>();
    private boolean showCameraMenu;
    private float lastRequestedZoom = 1f;
    private float lastHardwareRequestedZoom = 1f;
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
    private byte[] diagnosticAnchor1xGray;
    private float diagnosticAnchor1xZoom = 1f;
    private byte[] diagnosticAnchorMidGray;
    private float diagnosticAnchorMidZoom = 0f;
    private final Map<Float, byte[]> diagnosticAnchorMap =
            Collections.synchronizedMap(new java.util.LinkedHashMap<Float, byte[]>());
    private long lastPassiveAnchorTime;
    private byte[] prevStabilityGray;
    private float recentPreviewShiftPx = 0.25f;
    private boolean sceneHdrDetected;
    private volatile CapturePlanner.CapturePlan activeCapturePlan;
    private volatile String lastCaptureEngineDiag;
    private volatile String lastCaptureSciTelemetry;
    private volatile String lastQualityVerdict;
    private float lastFocalMm = 4.5f;
    private Bitmap referenceMiniBitmap;
    private static final int MAX_SAMPLES_PER_LEVEL = DiagnosticSessionStore.MAX_SAMPLES_PER_LEVEL;
    private static final int MAX_TOTAL_DIAGNOSTIC_SAMPLES = DiagnosticSessionStore.MAX_TOTAL_SAMPLES;
    private static final float MIN_OVERLAP_RATIO = DiagnosticSessionStore.MIN_OVERLAP_RATIO;
    private static final long DIAGNOSTIC_DELAY_MS = 350L;

    private static final String[] MODES = {"AUTO", "PRO", "ULTRA", "MAX", "NIGHT"};
    private static final String[] DESCS = {
            "Equilíbrio automático", "Controle manual e estabilidade", "Processamento multi-frame", "Máxima ampliação disponível", "Visão noturna • baixa luz + múltiplos frames"
    };

    private static class UiLayout {
        final RectF header = new RectF();
        final RectF zoomBadge = new RectF();
        final RectF telemetryCard = new RectF();
        final RectF mini = new RectF();
        final RectF zoomArea = new RectF();
        final RectF zoomTrack = new RectF();
        final RectF flash = new RectF();
        final RectF shutter = new RectF();
        final RectF mode = new RectF();
        final RectF info = new RectF();
        final RectF logExport = new RectF();
        final RectF camera = new RectF();
        final RectF[] lens = new RectF[]{new RectF(), new RectF(), new RectF(), new RectF(), new RectF()};
        final RectF modeSheet = new RectF();
        final RectF[] modeItems = new RectF[]{new RectF(), new RectF(), new RectF(), new RectF(), new RectF()};
        final RectF cameraSheet = new RectF();
        final RectF[] cameraItems = new RectF[]{
                new RectF(), new RectF(), new RectF(), new RectF(),
                new RectF(), new RectF(), new RectF(), new RectF()
        };
        final RectF infoSheet = new RectF();
        final RectF infoClose = new RectF();
        float scale = 1f;
        float deckTop = 0f;
        float lensGap = 0f;
        float lensWidth = 0f;

        void compute(int w, int h) {
            float sW = w / 900f;
            float sH = h / 1680f;
            scale = Math.max(0.74f, Math.min(1.55f, Math.min(sW, sH)));

            // Top obsidian header bar
            header.set(0f, 0f, w, 158 * scale);
            zoomBadge.set(w - 256 * scale, 14 * scale, w - 20 * scale, 144 * scale);

            // Bottom deck start (418 * scale tall so on 20:9 displays it sits below the 4:3 viewfinder)
            deckTop = h - 418 * scale;

            // Upper Telemetry + Minimap strip anchored directly below header (in upper letterbox on 20:9)
            float miniTop = header.bottom + 12 * scale;
            float miniW = 154 * scale;
            float miniH = 196 * scale;
            float maxMiniBottom = deckTop - 14 * scale;
            if (miniTop + miniH > maxMiniBottom) {
                miniH = Math.max(120 * scale, maxMiniBottom - miniTop);
            }
            mini.set(w - miniW - 20 * scale, miniTop, w - 20 * scale, miniTop + miniH);
            telemetryCard.set(20 * scale, miniTop, mini.left - 12 * scale, mini.bottom);

            // Bottom Row D: 3 symmetric luxury utility pills (LOG, CAM, INFO)
            float utilTop = h - 74 * scale;
            float utilBottom = h - 14 * scale;
            float utilGap = 12 * scale;
            float utilW = (w - 40 * scale - 2 * utilGap) / 3f;
            logExport.set(20 * scale, utilTop, 20 * scale + utilW, utilBottom);
            camera.set(logExport.right + utilGap, utilTop, logExport.right + utilGap + utilW, utilBottom);
            info.set(camera.right + utilGap, utilTop, camera.right + utilGap + utilW, utilBottom);

            // Bottom Row C: Primary capture deck (FLASH, SHUTTER, MODE)
            float cy = h - 142 * scale;
            shutter.set(w / 2f - 56 * scale, cy - 56 * scale, w / 2f + 56 * scale, cy + 56 * scale);
            flash.set(20 * scale, cy - 44 * scale, shutter.left - 20 * scale, cy + 44 * scale);
            mode.set(shutter.right + 20 * scale, cy - 44 * scale, w - 20 * scale, cy + 44 * scale);

            // Bottom Row B: Optical zoom dial card + inner logarithmic track
            zoomArea.set(20 * scale, h - 326 * scale, w - 20 * scale, h - 208 * scale);
            zoomTrack.set(zoomArea.left + 36 * scale, zoomArea.top + 42 * scale, zoomArea.right - 36 * scale, zoomArea.top + 82 * scale);

            // Bottom Row A: Ergonomic lens pill dock above zoom dial
            float lensTop = h - 404 * scale;
            float lensBottom = h - 338 * scale;
            lensGap = 10 * scale;
            lensWidth = (w - 40 * scale - 4 * lensGap) / 5f;
            for (int i = 0; i < lens.length; i++) {
                float x = 20 * scale + i * (lensWidth + lensGap);
                lens[i].set(x, lensTop, x + lensWidth, lensBottom);
            }

            // Modal sheet geometry: Mode Picker
            modeSheet.set(20 * scale, 88 * scale, w - 20 * scale, h - 36 * scale);
            float modeY = 198 * scale;
            float modeStep = Math.min(98 * scale, (modeSheet.bottom - 76 * scale - modeY) / MODES.length);
            float modeCardH = Math.max(64 * scale, modeStep - 12 * scale);
            for (int i = 0; i < modeItems.length; i++) {
                float top = modeY + i * modeStep;
                modeItems[i].set(36 * scale, top, w - 36 * scale, top + modeCardH);
            }

            // Modal sheet geometry: Camera Picker
            cameraSheet.set(18 * scale, 48 * scale, w - 18 * scale, h - 24 * scale);
            float camY = 152 * scale;
            float camStep = Math.min(92 * scale, (cameraSheet.bottom - 68 * scale - camY) / 8f);
            float camCardH = Math.max(58 * scale, camStep - 10 * scale);
            for (int i = 0; i < cameraItems.length; i++) {
                float top = camY + i * camStep;
                cameraItems[i].set(32 * scale, top, w - 32 * scale, top + camCardH);
            }

            // Modal sheet geometry: Diagnostic Info Screen
            infoSheet.set(16 * scale, 24 * scale, w - 16 * scale, h - 20 * scale);
            infoClose.set(28 * scale, h - 80 * scale, w - 28 * scale, h - 30 * scale);
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

    private void fitTxt(Canvas c, String s, float x, float y, float maxWidth, float size, int color, boolean bold) {
        if (s == null) return;
        Typeface tf = Typeface.create("sans", bold ? Typeface.BOLD : Typeface.NORMAL);
        text.setTypeface(tf);
        text.setColor(color);
        float minSize = Math.max(16.5f * ui.scale, size * 0.80f);
        float curSize = size;
        text.setTextSize(curSize);
        if (maxWidth > 0) {
            while (curSize > minSize && text.measureText(s) > maxWidth) {
                curSize -= 0.5f;
                text.setTextSize(curSize);
            }
        }
        String out = s;
        if (maxWidth > 0 && text.measureText(out) > maxWidth) {
            while (out.length() > 3 && text.measureText(out + "…") > maxWidth) {
                out = out.substring(0, out.length() - 1);
            }
            out = out + "…";
        }
        c.drawText(out, x, y, text);
    }

    private void centerFit(Canvas c, String s, float cx, float y, float maxWidth, float size, int color, boolean bold) {
        if (s == null) return;
        Typeface tf = Typeface.create("sans", bold ? Typeface.BOLD : Typeface.NORMAL);
        text.setTypeface(tf);
        text.setColor(color);
        float minSize = Math.max(16.5f * ui.scale, size * 0.80f);
        float curSize = size;
        text.setTextSize(curSize);
        if (maxWidth > 0) {
            while (curSize > minSize && text.measureText(s) > maxWidth) {
                curSize -= 0.5f;
                text.setTextSize(curSize);
            }
        }
        String out = s;
        if (maxWidth > 0 && text.measureText(out) > maxWidth) {
            while (out.length() > 3 && text.measureText(out + "…") > maxWidth) {
                out = out.substring(0, out.length() - 1);
            }
            out = out + "…";
        }
        c.drawText(out, cx - text.measureText(out) / 2f, y, text);
    }

    private void rightTxt(Canvas c, String s, float rightX, float y, float maxWidth, float size, int color, boolean bold) {
        if (s == null) return;
        Typeface tf = Typeface.create("sans", bold ? Typeface.BOLD : Typeface.NORMAL);
        text.setTypeface(tf);
        text.setColor(color);
        float minSize = Math.max(16.5f * ui.scale, size * 0.80f);
        float curSize = size;
        text.setTextSize(curSize);
        if (maxWidth > 0) {
            while (curSize > minSize && text.measureText(s) > maxWidth) {
                curSize -= 0.5f;
                text.setTextSize(curSize);
            }
        }
        String out = s;
        if (maxWidth > 0 && text.measureText(out) > maxWidth) {
            while (out.length() > 3 && text.measureText(out + "…") > maxWidth) {
                out = out.substring(0, out.length() - 1);
            }
            out = out + "…";
        }
        c.drawText(out, rightX - text.measureText(out), y, text);
    }

    private int statusAccentColor() {
        String st = status == null ? "" : status;
        if (st.contains("ERRO") || st.contains("FALHA") || st.contains("INDISPONÍVEL") || st.contains("NENHUMA")) return 0xFFEF4444;
        if (st.contains("CAPTURANDO") || st.contains("FUSÃO") || st.contains("SALVA")) return 0xFF38BDF8;
        if (zoomConfidence == ZoomConfidence.TRUSTED_RATIO || zoomConfidence == ZoomConfidence.TRUSTED_CROP_ONLY) return 0xFF10B981;
        return 0xFFF5B041;
    }

    private int confidenceAccentColor(String stateName) {
        if ("CONFIRMED".equals(stateName)) return 0xFF10B981;
        if ("REVOKED".equals(stateName) || "FAIL".equals(stateName)) return 0xFFEF4444;
        if ("PARTIAL".equals(stateName)) return 0xFFF5B041;
        return 0xFF94A3B8;
    }

    private void drawHud(Canvas c) {
        int w = getWidth(), h = getHeight();
        ui.compute(w, h);
        float s = ui.scale;

        drawViewfinderFrame(c, w, h, s);

        if (showInfo) {
            drawInfo(c, s);
            return;
        }
        if (showCameraMenu) {
            drawCameraMenu(c, s);
            return;
        }
        if (showModeMenu) {
            drawModeSheet(c, s);
            return;
        }

        // Top luxury obsidian header bar + lower control deck backdrop
        rounded(c, 0, 0, w, ui.header.bottom, 0, 0xEC08090C);
        p.setColor(0x2EFFFFFF);
        p.setStrokeWidth(Math.max(1f, 1.5f * s));
        c.drawLine(0, ui.header.bottom, w, ui.header.bottom, p);

        rounded(c, 0, ui.deckTop, w, h, 28 * s, 0xEE08090C);
        strokeRound(c, 2 * s, ui.deckTop, w - 2 * s, h + 28 * s, 28 * s, 1.5f * s, 0x28FFFFFF);

        // Left header: Brand + Pro subtitle + Status capsule
        float leftMaxW = ui.zoomBadge.left - 44 * s;
        rounded(c, 20 * s, 20 * s, 26 * s, 78 * s, 3 * s, 0xFFF5B041);
        fitTxt(c, "ULTRAZOOM", 36 * s, 50 * s, leftMaxW - 12 * s, 31 * s, Color.WHITE, true);
        fitTxt(c, "PRO CAMERA2 • 12.4 AUTODIAGNOSTIC", 36 * s, 78 * s, leftMaxW - 12 * s, 18.5f * s, 0xFFD8E0EB, true);

        RectF statusPill = new RectF(20 * s, 94 * s, ui.zoomBadge.left - 12 * s, 144 * s);
        rounded(c, statusPill.left, statusPill.top, statusPill.right, statusPill.bottom, 24 * s, 0xD8141821);
        strokeRound(c, statusPill.left, statusPill.top, statusPill.right, statusPill.bottom, 24 * s, 1.2f * s, 0x38FFFFFF);
        p.setColor(statusAccentColor());
        c.drawCircle(statusPill.left + 20 * s, statusPill.centerY(), 6.5f * s, p);
        fitTxt(c, status, statusPill.left + 36 * s, statusPill.centerY() + 7 * s, statusPill.width() - 48 * s, 19 * s, Color.WHITE, true);

        // Right header: Luxury Optical Zoom Readout Badge
        rounded(c, ui.zoomBadge.left, ui.zoomBadge.top, ui.zoomBadge.right, ui.zoomBadge.bottom, 22 * s, 0xE212151E);
        strokeRound(c, ui.zoomBadge.left, ui.zoomBadge.top, ui.zoomBadge.right, ui.zoomBadge.bottom, 22 * s, 1.6f * s, 0x77F5B041);
        centerFit(c, zoomHudString(), ui.zoomBadge.centerX(), ui.zoomBadge.top + 48 * s, ui.zoomBadge.width() - 24 * s, 34 * s, 0xFFFFD166, true);
        centerFit(c, MODES[mode.ordinal()] + " • " + zoomDomain(), ui.zoomBadge.centerX(), ui.zoomBadge.top + 78 * s, ui.zoomBadge.width() - 24 * s, 18.5f * s, WHITE_92, true);

        String globalSt = diagnosticGlobalState();
        RectF abcPill = new RectF(ui.zoomBadge.left + 12 * s, ui.zoomBadge.bottom - 42 * s, ui.zoomBadge.right - 12 * s, ui.zoomBadge.bottom - 10 * s);
        int abcColor = confidenceAccentColor(globalSt);
        rounded(c, abcPill.left, abcPill.top, abcPill.right, abcPill.bottom, 16 * s, (abcColor & 0x00FFFFFF) | 0x33000000);
        strokeRound(c, abcPill.left, abcPill.top, abcPill.right, abcPill.bottom, 16 * s, 1.3f * s, abcColor);
        centerFit(c, "A/B/C • " + globalSt, abcPill.centerX(), abcPill.centerY() + 6.5f * s, abcPill.width() - 16 * s, 18.5f * s, Color.WHITE, true);

        drawTelemetryCard(c, s);
        drawMiniMap(c, w, s);
        drawLensPills(c, w, s);
        drawZoomBar(c, w, h, s);
        drawBottom(c, w, h, s);

        if (focusUntil > System.currentTimeMillis()) {
            drawFocusReticle(c, focusX, focusY, s);
            postInvalidateDelayed(80);
        }
    }

    private void drawTelemetryCard(Canvas c, float s) {
        RectF box = ui.telemetryCard;
        if (box.width() < 160 * s || box.height() < 110 * s) return;
        rounded(c, box.left, box.top, box.right, box.bottom, 18 * s, 0xDF0B0E14);
        strokeRound(c, box.left, box.top, box.right, box.bottom, 18 * s, 1.4f * s, 0x38FFFFFF);

        float padX = 16 * s;
        float innerW = box.width() - 2 * padX;
        fitTxt(c, "AUTODIAGNÓSTICO REAL • TOQUE P/ DETALHES", box.left + padX, box.top + 28 * s, innerW, 18 * s, 0xFFFFD166, true);

        // 3 horizontal A / B / C live badges inside telemetry card
        float pillTop = box.top + 40 * s;
        float pillH = Math.min(64 * s, (box.height() - 86 * s));
        float gap = 8 * s;
        float pillW = (innerW - 2 * gap) / 3f;
        drawMiniConfidenceBadge(c, box.left + padX, pillTop, pillW, pillH, "CAMADA A", mapLayerAState().name(), s);
        drawMiniConfidenceBadge(c, box.left + padX + pillW + gap, pillTop, pillW, pillH, "CAMADA B", diagnosticStore.getLayerBState().name(), s);
        drawMiniConfidenceBadge(c, box.left + padX + 2 * (pillW + gap), pillTop, pillW, pillH, "CAMADA C", diagnosticStore.getLayerCState().name(), s);

        String geoVal = (zoomConfidence == ZoomConfidence.TRUSTED_CROP_ONLY || zoomConfidence == ZoomConfidence.TRUSTED_RATIO)
                ? String.format(Locale.US, "%.1f×", lastGeometricZoom) : "—";
        String line1 = String.format(Locale.US, "REQ %.1f× • CAM %.1f× • GEO %s", lastRequestedZoom, lastResultZoom, geoVal);
        CapturePlanner.CapturePlan plan = activeCapturePlan;
        String usefulPart = plan != null
                ? String.format(Locale.US, " • ÚTIL %.0f× (%s)", plan.effectiveMaxUsefulZoom, plan.stability.label)
                : "";
        String line2 = "4:3 • PARES B: " + diagnosticStore.getTotalPairsStored() +
                " • JPEGs C: " + diagnosticStore.getJpegs().size() + usefulPart;
        fitTxt(c, line1, box.left + padX, pillTop + pillH + 28 * s, innerW, 18.5f * s, Color.WHITE, true);
        if (pillTop + pillH + 56 * s <= box.bottom - 8 * s) {
            fitTxt(c, line2, box.left + padX, pillTop + pillH + 54 * s, innerW, 18 * s, 0xFFD0D7E2, true);
        }
    }

    private void drawMiniConfidenceBadge(Canvas c, float x, float y, float w, float h, String label, String state, float s) {
        int col = confidenceAccentColor(state);
        rounded(c, x, y, x + w, y + h, 12 * s, 0xFF121620);
        strokeRound(c, x, y, x + w, y + h, 12 * s, 1.3f * s, col);
        centerFit(c, label, x + w / 2f, y + h * 0.40f, w - 10 * s, 17.5f * s, WHITE_70, true);
        centerFit(c, state, x + w / 2f, y + h * 0.80f, w - 10 * s, 18.5f * s, col, true);
    }

    private void drawViewfinderFrame(Canvas c, int w, int h, float s) {
        GeometryMath.Viewport vp = GeometryMath.compute4x3Viewport(w, h);
        // Mask letterbox bars outside the native 4:3 sensor frame so GPU HyperZoom SR (>10x) never spills outside 4:3
        if (vp.top > 0) {
            rounded(c, 0, 0, w, vp.top, 0, Color.BLACK);
            rounded(c, 0, vp.top + vp.height, w, h, 0, Color.BLACK);
        }
        if (vp.left > 0) {
            rounded(c, 0, vp.top, vp.left, vp.top + vp.height, 0, Color.BLACK);
            rounded(c, vp.left + vp.width, vp.top, w, vp.top + vp.height, 0, Color.BLACK);
        }
        float topBound = Math.max(ui.mini.bottom + 8 * s, vp.top + 6 * s);
        float bottomBound = Math.min(ui.deckTop - 8 * s, vp.top + vp.height - 6 * s);
        float l = vp.left + 8 * s, t = topBound;
        float r = vp.left + vp.width - 8 * s, b = bottomBound;
        if (b <= t + 80 * s) return;

        // Subtle rule-of-thirds grid inside visible viewfinder
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f * s);
        p.setColor(0x22FFFFFF);
        float thirdW = (r - l) / 3f;
        float thirdH = (b - t) / 3f;
        c.drawLine(l + thirdW, t, l + thirdW, b, p);
        c.drawLine(l + 2f * thirdW, t, l + 2f * thirdW, b, p);
        c.drawLine(l, t + thirdH, r, t + thirdH, p);
        c.drawLine(l, t + 2f * thirdH, r, t + 2f * thirdH, p);

        // Precision optical corner brackets + center rangefinder crosshair
        p.setStrokeWidth(2.6f * s);
        p.setColor(0x88F5B041);
        float corner = 24 * s;
        c.drawLine(l, t, l + corner, t, p); c.drawLine(l, t, l, t + corner, p);
        c.drawLine(r - corner, t, r, t, p); c.drawLine(r, t, r, t + corner, p);
        c.drawLine(l, b - corner, l, b, p); c.drawLine(l, b, l + corner, b, p);
        c.drawLine(r - corner, b, r, b, p); c.drawLine(r, b - corner, r, b, p);

        float cx = (l + r) / 2f, cy = (t + b) / 2f;
        p.setStrokeWidth(1.4f * s);
        p.setColor(0x44FFFFFF);
        c.drawLine(cx - 16 * s, cy, cx - 5 * s, cy, p);
        c.drawLine(cx + 5 * s, cy, cx + 16 * s, cy, p);
        c.drawLine(cx, cy - 16 * s, cx, cy - 5 * s, p);
        c.drawLine(cx, cy + 5 * s, cx, cy + 16 * s, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawFocusReticle(Canvas c, float fx, float fy, float s) {
        float rad = 42 * s;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.8f * s);
        p.setColor(0xFFFFD166);
        c.drawRoundRect(fx - rad, fy - rad, fx + rad, fy + rad, 12 * s, 12 * s, p);
        p.setStrokeWidth(1.6f * s);
        c.drawLine(fx - rad - 8 * s, fy, fx - rad + 10 * s, fy, p);
        c.drawLine(fx + rad - 10 * s, fy, fx + rad + 8 * s, fy, p);
        c.drawLine(fx, fy - rad - 8 * s, fx, fy - rad + 10 * s, p);
        c.drawLine(fx, fy + rad - 10 * s, fx, fy + rad + 8 * s, p);
        p.setStyle(Paint.Style.FILL);
    }

    private float maxTotalZoom() {
        float hw = Math.max(1f, maxHardware);
        if (hw <= 1.05f) return 1.0f;
        return Math.min(50f, Math.max(12f, hw * MAX_SR_FACTOR));
    }

    private float currentSrFactor() {
        float hw = Math.max(1f, maxHardware);
        return zoom > hw ? Math.min(MAX_SR_FACTOR, zoom / hw) : 1.0f;
    }

    private String zoomString() {
        float clamped = Math.max(wideSupported ? 0.5f : 1.0f, Math.min(maxTotalZoom(), zoom));
        if (clamped < 10f) return String.format(Locale.US, "%.1f×", clamped);
        return String.format(Locale.US, "%.0f×", clamped);
    }

    private String zoomHudString() {
        String base = zoomString();
        if (zoom > maxHardware + 0.08f) return base + " SR";
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
        if (zoom < 0.99f && wideSupported) return "ULTRAWIDE";
        if (zoom > maxHardware + 0.08f) return String.format(Locale.US, "HW %.0f× • SR %.1f×", maxHardware, currentSrFactor());
        return String.format(Locale.US, "HW %.0f× • SR %.0f×", Math.max(1f, maxHardware), maxTotalZoom());
    }

    private void drawMiniMap(Canvas c, int w, float s) {
        RectF box = ui.mini;
        rounded(c, box.left, box.top, box.right, box.bottom, 18 * s, 0xE60A0C10);
        strokeRound(c, box.left, box.top, box.right, box.bottom, 18 * s, 1.8f * s, 0x88F5B041);

        float footerH = 38 * s;
        RectF image = new RectF(box.left + 6 * s, box.top + 6 * s, box.right - 6 * s, box.bottom - footerH - 4 * s);
        rounded(c, image.left, image.top, image.right, image.bottom, 12 * s, 0xFF141820);
        Bitmap displayMap = (zoom > 1.25f && referenceMiniBitmap != null && !referenceMiniBitmap.isRecycled())
                ? referenceMiniBitmap : miniBitmap;
        if (displayMap != null && !displayMap.isRecycled()) {
            Rect src = new Rect(0, 0, displayMap.getWidth(), displayMap.getHeight());
            c.drawBitmap(displayMap, src, image, p);
        }
        RectF view = zoomViewRect(image.left, image.top, image.right, image.bottom);
        strokeRound(c, view.left, view.top, view.right, view.bottom, 4 * s, 2.2f * s, zoom > maxHardware + 0.08f ? 0xFF38BDF8 : 0xFFFFD166);
        p.setColor(0xCCFFFFFF);
        p.setStrokeWidth(1.4f * s);
        c.drawLine(image.centerX() - 8 * s, image.centerY(), image.centerX() + 8 * s, image.centerY(), p);
        c.drawLine(image.centerX(), image.centerY() - 8 * s, image.centerX(), image.centerY() + 8 * s, p);

        // Dedicated high-contrast footer inside the minimap card so text is large and never overlaps outside
        RectF footer = new RectF(box.left + 5 * s, box.bottom - footerH, box.right - 5 * s, box.bottom - 5 * s);
        rounded(c, footer.left, footer.top, footer.right, footer.bottom, 11 * s, 0xE6121620);
        String miniLabel = (zoomConfidence == ZoomConfidence.TRUSTED_RATIO || zoomConfidence == ZoomConfidence.TRUSTED_CROP_ONLY)
                ? "FOV • " + zoomString()
                : "MAPA 1× • " + zoomString();
        centerFit(c, miniLabel, footer.centerX(), footer.centerY() + 6.5f * s, footer.width() - 10 * s, 18.5f * s, Color.WHITE, true);
    }

    private RectF zoomViewRect(float l, float t, float r, float b) {
        float mapW = Math.max(1f, r - l);
        float mapH = Math.max(1f, b - t);
        float z = Math.max(0.5f, Math.min(maxTotalZoom(), zoom));
        if (z < 1f) return new RectF(l, t, r, b);

        Rect crop = effectiveMapCrop();
        float sr = currentSrFactor();
        if (crop == null || sensor == null) {
            float fraction = 1f / z;
            float rw = mapW * fraction, rh = mapH * fraction;
            float cx = (l + r) / 2f, cy = (t + b) / 2f;
            return new RectF(cx - rw / 2f, cy - rh / 2f, cx + rw / 2f, cy + rh / 2f);
        }
        float sx = mapW / Math.max(1f, sensor.width());
        float sy = mapH / Math.max(1f, sensor.height());
        float cl = l + crop.left * sx;
        float ct = t + crop.top * sy;
        float cr = l + crop.right * sx;
        float cb = t + crop.bottom * sy;
        if (sr > 1.01f) {
            float cw = (cr - cl) / sr;
            float ch = (cb - ct) / sr;
            float cx = (cl + cr) * 0.5f;
            float cy = (ct + cb) * 0.5f;
            return new RectF(cx - cw * 0.5f, cy - ch * 0.5f, cx + cw * 0.5f, cy + ch * 0.5f);
        }
        return new RectF(cl, ct, cr, cb);
    }

    private Rect effectiveMapCrop() {
        if (sensor == null) return null;
        float hwZoom = Math.max(1f, Math.min(Math.max(1f, maxHardware), zoom));
        Rect crop = lastResultCrop;
        boolean full = crop == null || (crop.left == sensor.left && crop.top == sensor.top &&
                crop.right == sensor.right && crop.bottom == sensor.bottom);
        if (zoomConfidence == ZoomConfidence.UNKNOWN || zoomConfidence == ZoomConfidence.PROBING || zoomConfidence == ZoomConfidence.UNRELIABLE) {
            return crop == null ? new Rect(sensor) : new Rect(crop);
        }
        if (!full || Math.abs(hwZoom - 1f) < 0.01f) return crop == null ? new Rect(sensor) : new Rect(crop);
        if (zoomConfidence == ZoomConfidence.TRUSTED_CROP_ONLY || zoomConfidence == ZoomConfidence.TRUSTED_RATIO) {
            return cropForZoom(hwZoom);
        }
        return crop == null ? new Rect(sensor) : new Rect(crop);
    }

    private float[] lensTargets() {
        float maxHw = Math.max(1f, maxHardware);
        float maxSr = maxTotalZoom();
        if (wideSupported) {
            if (maxHw >= 6.0f) {
                return new float[]{0.5f, 1.0f, 3.0f, maxHw, maxSr};
            }
            if (maxHw >= 2.0f) {
                return new float[]{0.5f, 1.0f, 2.0f, maxHw, maxSr};
            }
            return new float[]{0.5f, 1.0f, 2.0f, 4.0f, 8.0f};
        } else {
            if (maxHw >= 6.0f) {
                return new float[]{1.0f, 2.0f, Math.min(5.0f, Math.round(maxHw * 0.5f)), maxHw, maxSr};
            }
            if (maxHw >= 2.0f) {
                return new float[]{1.0f, 1.5f, 2.0f, maxHw, maxSr};
            }
            return new float[]{0.5f, 1.0f, 2.0f, 4.0f, 8.0f};
        }
    }

    private void drawLensPills(Canvas c, int w, float s) {
        float[] targets = lensTargets();
        String[] lens = new String[targets.length];
        float maxSr = maxTotalZoom();
        for (int i = 0; i < targets.length; i++) {
            float v = targets[i];
            if (v < 0.99f) {
                lens[i] = "0,5×";
            } else {
                String num = (Math.abs(v - Math.round(v)) < 0.05f)
                        ? String.format(Locale.US, "%d×", Math.round(v))
                        : String.format(Locale.US, "%.1f×", v).replace('.', ',');
                lens[i] = (v > maxHardware + 0.1f) ? (num + " SR") : num;
            }
        }
        for (int i = 0; i < lens.length; i++) {
            RectF r = ui.lens[i];
            boolean enabled = (targets[i] < 1.0f) ? wideSupported : (targets[i] <= maxSr + 0.05f && maxHardware > 1.01f || targets[i] <= 1.01f);
            boolean isSrStop = targets[i] > maxHardware + 0.1f;
            boolean active = enabled && Math.abs(zoom - targets[i]) < (targets[i] < 1.5f ? 0.09f : (targets[i] > 12f ? 0.8f : 0.35f));
            int bg = active ? (isSrStop ? 0xFF38BDF8 : 0xFFF5B041) : (enabled ? 0xE0151922 : 0x77101218);
            rounded(c, r.left, r.top, r.right, r.bottom, 32 * s, bg);
            strokeRound(c, r.left, r.top, r.right, r.bottom, 32 * s, active ? 2.2f * s : 1.3f * s,
                    active ? 0xFFFFE082 : (enabled ? (isSrStop ? 0x8838BDF8 : 0x48FFFFFF) : 0x22FFFFFF));
            centerFit(c, lens[i], r.centerX(), r.centerY() + 8.5f * s, r.width() - 12 * s, 22 * s,
                    active ? 0xFF0A0C10 : (enabled ? Color.WHITE : 0x77FFFFFF), true);
        }
    }

    private void drawZoomBar(Canvas c, int w, int h, float s) {
        RectF card = ui.zoomArea;
        boolean scrubbing = draggingZoomSlider || pinchDistance > 0;
        rounded(c, card.left, card.top, card.right, card.bottom, 22 * s, 0xE411151E);
        strokeRound(c, card.left, card.top, card.right, card.bottom, 22 * s, scrubbing ? 2.0f * s : 1.4f * s,
                scrubbing ? 0x99F5B041 : 0x38FFFFFF);

        float maxZ = maxTotalZoom();
        double minSlider = wideSupported ? 0.5 : 1.0;
        double maxSlider = Math.max(minSlider + 0.01, maxZ);
        double lo = Math.log(minSlider), hi = Math.log(maxSlider);

        float[] candidateTicks = wideSupported
                ? new float[]{0.5f, 1f, 2f, 4f, 10f, 20f, 30f, 50f}
                : new float[]{1f, 2f, 4f, 6f, 10f, 20f, 30f, 50f};
        List<Float> ticks = new ArrayList<Float>();
        float lastQ = -1f;
        for (float t : candidateTicks) {
            if (t <= maxZ + 0.01f) {
                float q = (float) ((Math.log(Math.max(minSlider, t)) - lo) / (hi - lo));
                if (lastQ < 0f || (q - lastQ >= 0.15f && q <= 0.84f)) {
                    ticks.add(t);
                    lastQ = q;
                }
            }
        }
        if (ticks.isEmpty() || Math.abs(ticks.get(ticks.size() - 1) - maxZ) > 0.12f) {
            ticks.add(maxZ);
        }

        float trackLeft = ui.zoomTrack.left;
        float trackRight = ui.zoomTrack.right;
        float trackW = Math.max(1f, trackRight - trackLeft);
        float trackY = ui.zoomTrack.centerY();

        // Logarithmic tick labels aligned 1:1 with actual slider thumb positions
        for (int i = 0; i < ticks.size(); i++) {
            float v = ticks.get(i);
            String label = (v < 1f) ? "0,5×" : (Math.abs(v - Math.round(v)) < 0.05f ? Math.round(v) + "×" : String.format(Locale.US, "%.1f×", v));
            boolean active = Math.abs(zoom - v) < (v < 2f ? 0.08f : 0.45f);
            boolean isSr = v > maxHardware + 0.1f;
            float tq = ticks.size() == 1 ? 0f : (float) ((Math.log(Math.max(minSlider, Math.min(maxSlider, v))) - lo) / (hi - lo));
            float tx = trackLeft + tq * trackW;
            centerFit(c, label, tx, card.top + 30 * s, 68 * s, (active ? 21f : 19f) * s,
                    active ? (isSr ? 0xFF38BDF8 : 0xFFFFD166) : (isSr ? 0xFFB0E2FF : WHITE_92), true);
        }

        // Precision optical dial track + graduated Vernier ticks
        rounded(c, trackLeft - 6 * s, trackY - 12 * s, trackRight + 6 * s, trackY + 12 * s, 12 * s, 0xFF0A0D13);
        p.setColor(0x44FFFFFF);
        c.drawRoundRect(trackLeft, trackY - 3.0f * s, trackRight, trackY + 3.0f * s, 4 * s, 4 * s, p);
        p.setStrokeWidth(1.5f * s);
        for (int i = 0; i <= 28; i++) {
            float gx = trackLeft + (trackW * i) / 28f;
            float gh = (i % 4 == 0) ? 9 * s : 5 * s;
            p.setColor(i % 4 == 0 ? 0x88FFFFFF : 0x3DFFFFFF);
            c.drawLine(gx, trackY - gh, gx, trackY + gh, p);
        }

        double clampedZoom = Math.max(minSlider, Math.min(maxSlider, zoom));
        float q = ticks.size() == 1 ? 0f : (float) ((Math.log(clampedZoom) - lo) / (hi - lo));
        float thumbX = trackLeft + q * trackW;

        boolean inSrZone = zoom > maxHardware + 0.08f;
        p.setColor(inSrZone ? 0xFF38BDF8 : 0xFFF5B041);
        c.drawRoundRect(trackLeft, trackY - 3.0f * s, thumbX, trackY + 3.0f * s, 4 * s, 4 * s, p);
        p.setColor(inSrZone ? 0xFF7DD3FC : 0xFFFFD166);
        c.drawCircle(thumbX, trackY, 14 * s, p);
        p.setColor(0xFF0A0C10);
        c.drawCircle(thumbX, trackY, 6 * s, p);

        // Bottom caption row inside the zoom card with collision-proof bounds
        float captionY = card.bottom - 13 * s;
        String minLabel = wideSupported ? "MIN 0,5×" : "MIN 1,0×";
        String maxLabel = String.format(Locale.US, "HW %.0f× • SR %.0f×", Math.max(1f, maxHardware), maxZ);
        CapturePlanner.CapturePlan plan = activeCapturePlan;
        String centerCaption = plan != null
                ? (plan.regime.label + " • " + plan.stability.label + (sceneHdrDetected ? " • HDR" : ""))
                : "FUSÃO MULTI-FRAME LANCZOS-3 ATIVA";
        fitTxt(c, minLabel, card.left + 20 * s, captionY, 105 * s, 18 * s, WHITE_70, true);
        centerFit(c, centerCaption, card.centerX(), captionY, card.width() - 280 * s, 17.5f * s, 0xFFD8E0EB, true);
        rightTxt(c, maxLabel, card.right - 20 * s, captionY, 150 * s, 18 * s, inSrZone ? 0xFF38BDF8 : WHITE_70, true);
    }

    private void drawBottom(Canvas c, int w, int h, float s) {
        float cy = ui.shutter.centerY();
        drawFlash(c, ui.flash, s);
        drawShutter(c, ui.shutter.centerX(), cy, s);
        drawMode(c, ui.mode, s);

        // Bottom symmetric Pro Utility Row: LOG, CÂMERAS, DIAGNÓSTICO
        drawUtilityPill(c, ui.logExport, "EXPORTAR LOG", false, s);
        drawUtilityPill(c, ui.camera, "CÂMERAS (" + publicCameraRecords.size() + ")", showCameraMenu, s);
        drawUtilityPill(c, ui.info, "DIAGNÓSTICO i", showInfo, s);
    }

    private void drawUtilityPill(Canvas c, RectF r, String label, boolean active, float s) {
        rounded(c, r.left, r.top, r.right, r.bottom, 22 * s, active ? 0xFFF5B041 : 0xE0141821);
        strokeRound(c, r.left, r.top, r.right, r.bottom, 22 * s, 1.4f * s, active ? 0xFFFFE082 : 0x38FFFFFF);
        centerFit(c, label, r.centerX(), r.centerY() + 7 * s, r.width() - 18 * s, 19 * s, active ? 0xFF0A0C10 : Color.WHITE, true);
    }

    private String flashLabel() {
        return flashMode == 0 ? "FLASH OFF" : flashMode == 1 ? "FLASH AUTO" : "FLASH ON";
    }

    private void drawFlash(Canvas c, RectF box, float s) {
        boolean active = flashMode != 0;
        rounded(c, box.left, box.top, box.right, box.bottom, 24 * s, active ? 0xFFF5B041 : 0xE0141821);
        strokeRound(c, box.left, box.top, box.right, box.bottom, 24 * s, 1.5f * s, active ? 0xFFFFE082 : 0x38FFFFFF);
        int fg = active ? 0xFF0A0C10 : Color.WHITE;
        float iconX = box.centerX();
        float iconY = box.top + 28 * s;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3.0f * s);
        p.setColor(fg);
        Path q = new Path();
        q.moveTo(iconX + 5 * s, iconY - 14 * s);
        q.lineTo(iconX - 8 * s, iconY + 2 * s);
        q.lineTo(iconX + 1 * s, iconY + 2 * s);
        q.lineTo(iconX - 4 * s, iconY + 14 * s);
        q.lineTo(iconX + 10 * s, iconY - 2 * s);
        q.lineTo(iconX + 2 * s, iconY - 2 * s);
        q.close();
        c.drawPath(q, p);
        p.setStyle(Paint.Style.FILL);
        centerFit(c, flashLabel(), box.centerX(), box.bottom - 15 * s, box.width() - 18 * s, 19.5f * s, fg, true);
    }

    private void drawShutter(Canvas c, float x, float y, float s) {
        p.setColor(0xFFF8FAFC);
        c.drawCircle(x, y, 55 * s, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3.5f * s);
        p.setColor(0xFFF5B041);
        c.drawCircle(x, y, 51 * s, p);
        p.setStrokeWidth(4.2f * s);
        p.setColor(0xFF0E1015);
        c.drawCircle(x, y, 43 * s, p);
        p.setStyle(Paint.Style.FILL);
        if (busy) {
            p.setColor(0xFF0E1015);
            c.drawRoundRect(x - 14 * s, y - 14 * s, x + 14 * s, y + 14 * s, 5 * s, 5 * s, p);
        } else {
            centerFit(c, mode == Mode.NIGHT ? "NIGHT" : "FOTO", x, y + 7 * s, 68 * s, 18.5f * s, 0xFF0E1015, true);
        }
    }

    private void drawMode(Canvas c, RectF box, float s) {
        rounded(c, box.left, box.top, box.right, box.bottom, 24 * s, 0xE0141821);
        strokeRound(c, box.left, box.top, box.right, box.bottom, 24 * s, 1.5f * s, 0x55F5B041);
        centerFit(c, "MODO", box.centerX(), box.top + 32 * s, box.width() - 18 * s, 18 * s, WHITE_70, true);
        centerFit(c, MODES[mode.ordinal()], box.centerX(), box.bottom - 16 * s, box.width() - 18 * s, 23 * s, 0xFFFFD166, true);
    }

    private void drawModeSheet(Canvas c, float s) {
        int w = getWidth(), h = getHeight();
        rounded(c, 0, 0, w, h, 0, 0xC4030508);
        RectF sheet = ui.modeSheet;
        rounded(c, sheet.left, sheet.top, sheet.right, sheet.bottom, 28 * s, 0xFA0A0C12);
        strokeRound(c, sheet.left, sheet.top, sheet.right, sheet.bottom, 28 * s, 1.8f * s, 0x66F5B041);
        fitTxt(c, "MODO DE CAPTURA", sheet.left + 24 * s, sheet.top + 52 * s, sheet.width() - 48 * s, 28 * s, Color.WHITE, true);
        fitTxt(c, "Selecione o pipeline computacional da câmera", sheet.left + 24 * s, sheet.top + 84 * s, sheet.width() - 48 * s, 19 * s, WHITE_70, false);
        for (int i = 0; i < MODES.length; i++) {
            RectF item = ui.modeItems[i];
            boolean active = i == mode.ordinal();
            rounded(c, item.left, item.top, item.right, item.bottom, 20 * s, active ? 0xFFF5B041 : 0xE0161A24);
            strokeRound(c, item.left, item.top, item.right, item.bottom, 20 * s, 1.4f * s, active ? 0xFFFFE082 : 0x38FFFFFF);
            fitTxt(c, MODES[i], item.left + 22 * s, item.top + item.height() * 0.44f, item.width() - 44 * s, 23 * s, active ? 0xFF0A0C10 : Color.WHITE, true);
            fitTxt(c, DESCS[i], item.left + 22 * s, item.top + item.height() * 0.80f, item.width() - 44 * s, 18.5f * s, active ? 0xFF1E2430 : 0xFFD8E0EB, false);
        }
        centerFit(c, "TOQUE FORA PARA FECHAR", w / 2f, sheet.bottom - 26 * s, sheet.width() - 40 * s, 19 * s, 0xFFFFD166, true);
    }

    private void drawCameraMenu(Canvas c, float s) {
        int w = getWidth(), h = getHeight();
        rounded(c, 0, 0, w, h, 0, 0xC4030508);
        RectF sheet = ui.cameraSheet;
        rounded(c, sheet.left, sheet.top, sheet.right, sheet.bottom, 28 * s, 0xFA0A0C12);
        strokeRound(c, sheet.left, sheet.top, sheet.right, sheet.bottom, 28 * s, 1.8f * s, 0x66F5B041);
        fitTxt(c, "CÂMERAS EXPOSTAS (CAMERA2)", sheet.left + 22 * s, sheet.top + 48 * s, sheet.width() - 44 * s, 27 * s, Color.WHITE, true);
        fitTxt(c, "Toque em uma câmera para abrir diretamente em 1,0×", sheet.left + 22 * s, sheet.top + 80 * s, sheet.width() - 44 * s, 18.5f * s, WHITE_70, false);
        int shown = Math.min(ui.cameraItems.length, publicCameraRecords.size());
        if (shown == 0) {
            fitTxt(c, "Nenhuma câmera pública encontrada", sheet.left + 22 * s, sheet.top + 140 * s, sheet.width() - 44 * s, 21 * s, WHITE_70, false);
            centerFit(c, "TOQUE FORA PARA FECHAR", w / 2f, sheet.bottom - 26 * s, sheet.width() - 40 * s, 18.5f * s, 0xFFFFD166, true);
            return;
        }
        for (int i = 0; i < shown; i++) {
            CameraRecord r = publicCameraRecords.get(i);
            RectF item = ui.cameraItems[i];
            boolean active = r.id != null && r.id.equals(cameraId);
            rounded(c, item.left, item.top, item.right, item.bottom, 20 * s, active ? 0xFFF5B041 : 0xE0161A24);
            strokeRound(c, item.left, item.top, item.right, item.bottom, 20 * s, 1.4f * s, active ? 0xFFFFE082 : 0x38FFFFFF);
            String face = r.facing == CameraCharacteristics.LENS_FACING_BACK ? "TRASEIRA" :
                    r.facing == CameraCharacteristics.LENS_FACING_FRONT ? "FRONTAL" : "EXTERNA";
            int primaryColor = active ? 0xFF0A0C10 : Color.WHITE;
            int secondaryColor = active ? 0xFF1E2430 : 0xFFD8E0EB;
            fitTxt(c, "ID " + r.id + " • " + face + (r.logical ? " • LÓGICA" : ""), item.left + 20 * s, item.top + item.height() * 0.44f, item.width() - 40 * s, 21.5f * s, primaryColor, true);
            String res = r.pixels == null ? "res ?" : r.pixels.getWidth() + "×" + r.pixels.getHeight();
            String fov = r.fov > 0 ? String.format(Locale.US, "FOV %.1f°", Math.toDegrees(r.fov)) : "FOV ?";
            String detailLine = res + " • " + fov + " • " + String.format(Locale.US, "zoom %.1f–%.1f×", r.range.min, r.range.max);
            fitTxt(c, detailLine, item.left + 20 * s, item.top + item.height() * 0.80f, item.width() - 40 * s, 18.5f * s, secondaryColor, false);
        }
        centerFit(c, "TOQUE FORA PARA FECHAR • USE 'DIAGNÓSTICO i' PARA TELEMETRIA", w / 2f, sheet.bottom - 24 * s, sheet.width() - 40 * s, 18.5f * s, 0xFFFFD166, true);
    }

    private void drawInfo(Canvas c, float s) {
        int w = getWidth(), h = getHeight();
        rounded(c, 0, 0, w, h, 0, 0xCC030508);
        RectF sheet = ui.infoSheet;
        rounded(c, sheet.left, sheet.top, sheet.right, sheet.bottom, 28 * s, 0xFA090B10);
        strokeRound(c, sheet.left, sheet.top, sheet.right, sheet.bottom, 28 * s, 1.8f * s, 0x66F5B041);

        float cardLeft = 28 * s, cardRight = w - 28 * s, cardW = cardRight - cardLeft;
        fitTxt(c, "ULTRAZOOM 12.4 • DIAGNÓSTICO", cardLeft, sheet.top + 46 * s, cardW, 27 * s, Color.WHITE, true);
        fitTxt(c, "BUILD " + BUILD_ID + " • LAYOUT " + (layoutValid ? "SEM SOBREPOSIÇÃO" : layoutDiagnostic), cardLeft, sheet.top + 76 * s, cardW, 18.5f * s, 0xFFFFD166, true);

        float topY = sheet.top + 92 * s;
        float bottomY = ui.infoClose.top - 12 * s;
        float availH = Math.max(400 * s, bottomY - topY);
        float gap = 10 * s;

        // Proportional heights for the 4 telemetry cards so they always fit any screen height cleanly
        float c1H = Math.max(136 * s, availH * 0.23f);
        float c2H = Math.max(168 * s, availH * 0.27f);
        float c3H = Math.max(168 * s, availH * 0.27f);
        float y = topY;

        // CARD 1: Matriz de Confiança A / B / C
        float c1Bottom = y + c1H;
        rounded(c, cardLeft, y, cardRight, c1Bottom, 20 * s, 0xEB131722);
        strokeRound(c, cardLeft, y, cardRight, c1Bottom, 20 * s, 1.4f * s, 0x38FFFFFF);
        String globalSt = diagnosticGlobalState();
        fitTxt(c, "MATRIZ DE CONFIANÇA A / B / C", cardLeft + 18 * s, y + 29 * s, cardW - 220 * s, 18.5f * s, 0xFFFFD166, true);
        rightTxt(c, "GLOBAL: " + globalSt, cardRight - 18 * s, y + 29 * s, 200 * s, 18.5f * s, confidenceAccentColor(globalSt), true);

        float pillGap = 10 * s;
        float pillW = (cardW - 36 * s - 2 * pillGap) / 3f;
        float boxTop = y + 38 * s;
        float boxH = Math.max(54 * s, c1H - 72 * s);
        drawConfidenceBox(c, cardLeft + 18 * s, boxTop, pillW, boxH, "A • DECLARADO", mapLayerAState().name(), s);
        drawConfidenceBox(c, cardLeft + 18 * s + pillW + pillGap, boxTop, pillW, boxH, "B • OBSERVADO", diagnosticStore.getLayerBState().name(), s);
        drawConfidenceBox(c, cardLeft + 18 * s + 2 * (pillW + pillGap), boxTop, pillW, boxH, "C • INFERIDO", diagnosticStore.getLayerCState().name(), s);

        String bStats = "Pares B: " + diagnosticStore.getTotalPairsStored() +
                " • Confirmados: " + diagnosticStore.getConfirmedLevelsCount() +
                " • JPEGs C: " + diagnosticStore.getJpegs().size() +
                " • Amostras: " + diagnosticStore.getTotalSamples();
        fitTxt(c, bStats, cardLeft + 18 * s, c1Bottom - 12 * s, cardW - 36 * s, 18 * s, WHITE_92, false);
        y = c1Bottom + gap;

        // CARD 2: Telemetria de Zoom & Geometria 4:3
        float c2Bottom = y + c2H;
        rounded(c, cardLeft, y, cardRight, c2Bottom, 20 * s, 0xEB131722);
        strokeRound(c, cardLeft, y, cardRight, c2Bottom, 20 * s, 1.4f * s, 0x38FFFFFF);
        fitTxt(c, "GEOMETRIA 4:3 & TELEMETRIA DE ZOOM", cardLeft + 18 * s, y + 28 * s, cardW - 36 * s, 18.5f * s, 0xFFFFD166, true);
        float rowStep2 = (c2H - 42 * s) / 5f;
        float ry = y + 34 * s + rowStep2 * 0.78f;
        String geoVal = (zoomConfidence == ZoomConfidence.TRUSTED_CROP_ONLY || zoomConfidence == ZoomConfidence.TRUSTED_RATIO)
                ? String.format(Locale.US, "%.2f×", lastGeometricZoom) : "—";
        String reqStr = currentSrFactor() > 1.02f
                ? String.format(Locale.US, "%.1f× (HW %.1f×•SR %.1f×)", lastRequestedZoom, lastHardwareRequestedZoom, currentSrFactor())
                : String.format(Locale.US, "%.2f×", lastRequestedZoom);
        infoRow(c, "REQ / CAM / GEO", String.format(Locale.US, "%s / %.2f× / %s", reqStr, lastResultZoom, geoVal), ry, cardW, s); ry += rowStep2;
        infoRow(c, "Estado Camada A", zoomConfidenceLabel(), ry, cardW, s); ry += rowStep2;
        CapturePlanner.CapturePlan plan = activeCapturePlan;
        String usefulStr = plan != null ? String.format(Locale.US, " • Útil %.0f×", plan.effectiveMaxUsefulZoom) : "";
        infoRow(c, "Faixa Camera2", zoomDiagnostics + usefulStr, ry, cardW, s); ry += rowStep2;
        infoRow(c, "Crop SCALER", cropString(lastResultCrop), ry, cardW, s); ry += rowStep2;
        infoRow(c, "Ultrawide 0,5×", wideSupported ? "EVIDÊNCIA COMPROVADA" : "NÃO EXPOSTO PELA CAMERA2", ry, cardW, s);
        y = c2Bottom + gap;

        // CARD 3: Sensor, Óptica & Estabilização
        float c3Bottom = y + c3H;
        rounded(c, cardLeft, y, cardRight, c3Bottom, 20 * s, 0xEB131722);
        strokeRound(c, cardLeft, y, cardRight, c3Bottom, 20 * s, 1.4f * s, 0x38FFFFFF);
        fitTxt(c, "SENSOR, ÓPTICA & ESTABILIZAÇÃO", cardLeft + 18 * s, y + 28 * s, cardW - 36 * s, 18.5f * s, 0xFFFFD166, true);
        float rowStep3 = (c3H - 42 * s) / 5f;
        ry = y + 34 * s + rowStep3 * 0.78f;
        infoRow(c, "Câmera Ativa", (cameraId == null ? "—" : "ID " + cameraId) + " (Principal: " + (mainCameraId == null ? "—" : mainCameraId) + ")", ry, cardW, s); ry += rowStep3;
        infoRow(c, "JPEG / Preview", sizeString(jpegSize) + " • Preview " + sizeString(previewSize), ry, cardW, s); ry += rowStep3;
        infoRow(c, "Físico / Focal", lastResultPhysicalId + " • " + lastFocalResult, ry, cardW, s); ry += rowStep3;
        infoRow(c, "OIS / EIS", stabilizationDiagnostics, ry, cardW, s); ry += rowStep3;
        infoRow(c, "Physical IDs", physicalIdsDiagnostics, ry, cardW, s);
        y = c3Bottom + gap;

        // CARD 4: Autofoco, Exposição & Motor Computacional
        float c4Bottom = bottomY;
        if (c4Bottom > y + 76 * s) {
            rounded(c, cardLeft, y, cardRight, c4Bottom, 20 * s, 0xEB131722);
            strokeRound(c, cardLeft, y, cardRight, c4Bottom, 20 * s, 1.4f * s, 0x38FFFFFF);
            fitTxt(c, "AUTOFOCO, EXPOSIÇÃO & MOTOR DE IMAGEM", cardLeft + 18 * s, y + 28 * s, cardW - 36 * s, 18.5f * s, 0xFFFFD166, true);
            float c4H = c4Bottom - y;
            float rowStep4 = (c4H - 42 * s) / 3f;
            ry = y + 34 * s + rowStep4 * 0.78f;
            String engineStr = lastCaptureEngineDiag != null ? lastCaptureEngineDiag : imageEngineDiagnostics;
            infoRow(c, "Motor Imagem", engineStr, ry, cardW, s); ry += rowStep4;
            infoRow(c, "Autofoco / AE", focusDiagnostics, ry, cardW, s); ry += rowStep4;
            String thirdRowVal = lastQualityVerdict != null
                    ? (lastQualityVerdict + " • " + orientationDiagnostics)
                    : orientationDiagnostics;
            infoRow(c, lastQualityVerdict != null ? "Qualidade / 4:3" : "Orientação", thirdRowVal, ry, cardW, s);
        }

        // Footer close button
        RectF closeBtn = ui.infoClose;
        rounded(c, closeBtn.left, closeBtn.top, closeBtn.right, closeBtn.bottom, 22 * s, 0xFFF5B041);
        centerFit(c, "TOQUE PARA VOLTAR AO VISOR", closeBtn.centerX(), closeBtn.centerY() + 7 * s, closeBtn.width() - 24 * s, 20 * s, 0xFF0A0C10, true);
    }

    private void drawConfidenceBox(Canvas c, float x, float y, float w, float h, String title, String state, float s) {
        int col = confidenceAccentColor(state);
        rounded(c, x, y, x + w, y + h, 14 * s, 0xFF0D1017);
        strokeRound(c, x, y, x + w, y + h, 14 * s, 1.5f * s, col);
        centerFit(c, title, x + w / 2f, y + h * 0.40f, w - 12 * s, 18 * s, WHITE_70, true);
        centerFit(c, state, x + w / 2f, y + h * 0.80f, w - 12 * s, 20.5f * s, col, true);
    }

    private void infoRow(Canvas c, String label, String value, float y, float cardW, float s) {
        float labelX = 46 * s;
        float valX = 248 * s;
        float maxValW = Math.max(80 * s, cardW - (valX - 28 * s) - 18 * s);
        fitTxt(c, label, labelX, y, 192 * s, 18.5f * s, 0xFFB8C2D0, true);
        fitTxt(c, value == null ? "—" : value, valX, y, maxValW, 19.5f * s, Color.WHITE, true);
    }

    private boolean overlaps(RectF a, RectF b) {
        return RectF.intersects(a, b);
    }

    boolean isLayoutValid() {
        return layoutValid;
    }

    String getLayoutDiagnostic() {
        return layoutDiagnostic;
    }

    RectF getUiMini() { return ui.mini; }
    RectF getUiZoomArea() { return ui.zoomArea; }
    RectF getUiZoomBadge() { return ui.zoomBadge; }
    RectF getUiTelemetryCard() { return ui.telemetryCard; }
    RectF getUiLens(int idx) { return ui.lens[idx]; }
    float getUiScale() { return ui.scale; }
    void setModalStateForAudit(boolean info, boolean modeMenu, boolean cameraMenu) {
        this.showInfo = info;
        this.showModeMenu = modeMenu;
        this.showCameraMenu = cameraMenu;
        postInvalidate();
    }

    void populateSampleHardwareStateForAudit(boolean wide, float maxZ, float currentZoom) {
        this.wideSupported = wide;
        this.minHardware = wide ? 0.5f : 1.0f;
        this.maxHardware = maxZ;
        this.zoom = currentZoom;
        this.lastRequestedZoom = currentZoom;
        this.lastResultZoom = currentZoom;
        this.lastGeometricZoom = currentZoom;
        this.zoomConfidence = ZoomConfidence.TRUSTED_RATIO;
        this.cameraId = "0";
        this.mainCameraId = "0";
        this.publicCameraRecords.clear();
        String[] ids = new String[]{"0", "1", "2", "3"};
        int[] facings = new int[]{
                CameraCharacteristics.LENS_FACING_BACK,
                CameraCharacteristics.LENS_FACING_FRONT,
                CameraCharacteristics.LENS_FACING_BACK,
                CameraCharacteristics.LENS_FACING_BACK
        };
        for (int i = 0; i < ids.length; i++) {
            CameraRecord r = new CameraRecord();
            r.id = ids[i];
            r.facing = facings[i];
            r.logical = (i == 0);
            r.fov = 1.2f;
            r.range = new RangeZ(i == 0 && wide ? 0.5f : 1.0f, i == 0 ? maxZ : 4.0f);
            r.pixels = new Size(4000, 3000);
            r.area = 12_000_000L;
            this.publicCameraRecords.add(r);
        }
        postInvalidate();
    }

    private void validateLayout() {
        layoutValid = true;
        layoutDiagnostic = "OK";
        if (overlaps(ui.header, ui.mini) || overlaps(ui.header, ui.telemetryCard)) {
            layoutValid = false;
            layoutDiagnostic = "PAINEL SUPERIOR SOBRE CABEÇALHO";
        }
        if (overlaps(ui.telemetryCard, ui.mini)) {
            layoutValid = false;
            layoutDiagnostic = "TELEMETRIA SOBRE MINIMAPA";
        }
        for (int i = 0; i < ui.lens.length; i++) {
            if (ui.lens[i].left < 0 || ui.lens[i].right > getWidth()) { layoutValid = false; layoutDiagnostic = "LENTE FORA DA TELA"; }
            for (int j = i + 1; j < ui.lens.length; j++) if (overlaps(ui.lens[i], ui.lens[j])) { layoutValid = false; layoutDiagnostic = "LENTES SOBREPOSTAS"; }
            if (overlaps(ui.mini, ui.lens[i]) || overlaps(ui.telemetryCard, ui.lens[i])) { layoutValid = false; layoutDiagnostic = "MINIMAPA SOBRE LENTES"; }
            if (overlaps(ui.zoomArea, ui.lens[i])) { layoutValid = false; layoutDiagnostic = "LENTES SOBRE ZOOM"; }
        }
        if (overlaps(ui.mini, ui.zoomArea) || overlaps(ui.telemetryCard, ui.zoomArea)) {
            layoutValid = false;
            layoutDiagnostic = "MINIMAPA SOBRE ZOOM";
        }
        if (overlaps(ui.zoomArea, ui.shutter) || overlaps(ui.zoomArea, ui.flash) || overlaps(ui.zoomArea, ui.mode)) {
            layoutValid = false; layoutDiagnostic = "ZOOM SOBRE CONTROLES";
        }
        if (overlaps(ui.flash, ui.shutter) || overlaps(ui.shutter, ui.mode) || overlaps(ui.flash, ui.mode) ||
                overlaps(ui.logExport, ui.flash) || overlaps(ui.logExport, ui.shutter) || overlaps(ui.logExport, ui.mode) ||
                overlaps(ui.camera, ui.shutter) || overlaps(ui.info, ui.mode) ||
                overlaps(ui.logExport, ui.camera) || overlaps(ui.camera, ui.info)) {
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
        Boolean awbLock = cc.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE);
        aeLockSupported = Boolean.TRUE.equals(aeLock);
        awbLockSupported = Boolean.TRUE.equals(awbLock);
        Integer maxAfRegions = cc.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
        Integer maxAeRegions = cc.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        focusDiagnostics = "AF modes=" + arrayString(afModes) + " • maxAF=" +
                String.valueOf(maxAfRegions) +
                " • maxAE=" + String.valueOf(maxAeRegions) +
                " • AE=" + arrayString(aeModes) + " • AWB=" + arrayString(awbModes);
        int[] ois = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        if (ois == null) ois = new int[0];
        oisSupported = hasCapability(ois, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
        int[] eis = cc.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        stabilizationDiagnostics = "OIS=" + (ois.length != 0 ? Arrays.toString(ois) : "?") + (oisSupported ? " (ATIVO)" : "") + " • EIS=" + arrayString(eis);
        Range<Float> zr = cc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        Float dz = cc.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        zoomDiagnostics = "ratio=" + rangeString(zr) + " • maxDigital=" + (dz == null ? "?" : String.format(Locale.US, "%.2f×", dz)) + " • SR=" + String.format(Locale.US, "%.0f×", maxTotalZoom());
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
            zoom = Math.max(wideSupported ? 0.5f : 1.0f, Math.min(maxTotalZoom(), zoom));

            prepareReader(chars);
            collectOutputDiagnostics(chars);
            collectCapabilityDiagnostics(chars);
            flashAvailable = Boolean.TRUE.equals(chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
            collectDetailedDiagnostics(cm, ids, main);
            lowLightBoostSupported = supportsLowLightBoost(chars);
            cameraDiagnostics = publicCameraDiagnostic;
            updateStatusOnUi("CÂMERA PRONTA • 4:3 " + sizeString(jpegSize) + " • SR ATÉ " + String.format(Locale.US, "%.0f×", maxTotalZoom()));

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
        jpegSize = new Size(1920, 1440);
        if (map != null) {
            Size[] sizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG);
            if (sizes != null && sizes.length > 0) {
                int[] ws = new int[sizes.length];
                int[] hs = new int[sizes.length];
                Size largest = sizes[0];
                for (int i = 0; i < sizes.length; i++) {
                    ws[i] = sizes[i].getWidth();
                    hs[i] = sizes[i].getHeight();
                    if ((long) ws[i] * hs[i] > (long) largest.getWidth() * largest.getHeight()) {
                        largest = sizes[i];
                    }
                }
                int sw = sensor != null ? sensor.width() : 4096;
                int sh = sensor != null ? sensor.height() : 3072;
                int bestIdx = GeometryMath.chooseBestNative4x3SizeIndex(ws, hs, sw, sh, MemoryPolicy.MAX_NATIVE_4X3_PIXELS);
                jpegSize = (bestIdx >= 0 && bestIdx < sizes.length) ? sizes[bestIdx] : largest;
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
                    // Decode at 100% full native resolution (inSampleSize = 1 for up to 4096x3072 bursts)
                    opt.inSampleSize = MemoryPolicy.recommendedInSampleSize(
                            jpegSize.getWidth(), jpegSize.getHeight(), targetFrames, MemoryPolicy.FULL_RES_FRAME_BUDGET_BYTES);
                    Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length, opt);
                    if (bm != null) finishPhoto(bm);
                }
            } catch (Exception ignored) {
            } finally {
                if (im != null) im.close();
            }
        }, workHandler);
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
            if (st == null || device == null || reader == null) return;
            previewSize = choosePreviewSize(chars);
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            if (previewSurface != null) { try { previewSurface.release(); } catch (Exception ignored) { } }
            previewSurface = new Surface(st);
            Surface ps = previewSurface;
            miniInSession = true;

            // Pure 2-surface hardware golden path (PREVIEW + Full-Sensor 4:3 JPEG) so the ISP never drops to 3-stream fallback
            if (Build.VERSION.SDK_INT >= 28 && activePhysicalId != null) {
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
                        miniInSession = true;
                        applyPreview();
                        post(() -> scheduleAutoDiagnostic(Math.min(Math.max(1f, maxHardware), zoom)));
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
                device.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession s) {
                        session = s;
                        miniInSession = true;
                        applyPreview();
                        post(() -> scheduleAutoDiagnostic(Math.min(Math.max(1f, maxHardware), zoom)));
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
                    miniInSession = true;
                    updateStatusOnUi("0,5× • ULTRAWIDE REAL");
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
                    miniInSession = true;
                    updateStatusOnUi("CÂMERA PRONTA");
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

        // Enable Hardware Optical Image Stabilization (OIS) on both preview and still capture when supported
        if (oisSupported) {
            try { b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON); } catch (Exception ignored) { }
            try { b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF); } catch (Exception ignored) { }
        }

        if (template == CameraDevice.TEMPLATE_STILL_CAPTURE) {
            try { b.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE); } catch (Exception ignored) { }
            try { b.set(CaptureRequest.JPEG_QUALITY, (byte) 100); } catch (Exception ignored) { }
            try { b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY); } catch (Exception ignored) { }
            try { b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY); } catch (Exception ignored) { }
            try { b.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY); } catch (Exception ignored) { }
            try { b.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY); } catch (Exception ignored) { }
            try { b.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_HIGH_QUALITY); } catch (Exception ignored) { }
            try { b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY); } catch (Exception ignored) { }
            if (Build.VERSION.SDK_INT >= 28) {
                try { b.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY); } catch (Exception ignored) { }
            }
        } else if (template == CameraDevice.TEMPLATE_PREVIEW && zoom >= 4.0f) {
            // Enhance live viewfinder edge definition and contrast at high zoom (4x..30x)
            try { b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST); } catch (Exception ignored) { }
            try { b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST); } catch (Exception ignored) { }
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
        if (template == CameraDevice.TEMPLATE_STILL_CAPTURE && targetFrames > 1 && mode != Mode.NIGHT &&
                lastAeResultTime > 0 && System.currentTimeMillis() - lastAeResultTime < 700 &&
                (lastAeState == CaptureResult.CONTROL_AE_STATE_CONVERGED || lastAeState == CaptureResult.CONTROL_AE_STATE_LOCKED)) {
            if (aeLockSupported) b.set(CaptureRequest.CONTROL_AE_LOCK, true);
            if (awbLockSupported) b.set(CaptureRequest.CONTROL_AWB_LOCK, true);
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
        if (sensor == null) return;
        if (requestedZoom < 1f && activePhysicalId != null) {
            lastHardwareRequestedZoom = 1f;
            resetZoomConfidence(1f);
            if (Build.VERSION.SDK_INT >= 30) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f);
            else b.set(CaptureRequest.SCALER_CROP_REGION, sensor);
            return;
        }
        if (requestedZoom < 1f && cameraId != null && cameraId.equals(wideCameraId)) {
            float wideHw = Math.max(minHardware, Math.min(maxHardware, requestedZoom));
            lastHardwareRequestedZoom = wideHw;
            resetZoomConfidence(wideHw);
            if (Build.VERSION.SDK_INT >= 30 && minHardware < 1f && requestedZoom >= minHardware && requestedZoom <= maxHardware) {
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, wideHw);
            } else {
                b.set(CaptureRequest.SCALER_CROP_REGION, sensor);
            }
            return;
        }

        float hardwareZoom = Math.max(1f, Math.min(Math.max(1f, maxHardware), requestedZoom));
        lastHardwareRequestedZoom = hardwareZoom;
        resetZoomConfidence(hardwareZoom);
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
                    float cropGeo = (float) Math.max(1.0, Math.sqrt(sensorArea / Math.max(1.0, cropArea)));
                    // On Android 11+, when CONTROL_ZOOM_RATIO is active, SCALER_CROP_REGION stays full sensor;
                    // combine with CONTROL_ZOOM_RATIO so GEO reflects true ISP geometric magnification.
                    if (rz != null && rz > 1.01f && cropGeo <= 1.04f) {
                        lastGeometricZoom = rz;
                    } else {
                        lastGeometricZoom = cropGeo;
                    }
                } else {
                    lastGeometricZoom = rz != null ? Math.max(1f, rz) : 1f;
                }
                updateZoomConfidence(r, rz);
                if (Build.VERSION.SDK_INT >= 28) {
                    String pid = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
                    lastResultPhysicalId = pid == null ? "(nenhum)" : pid;
                }
                Float focal = result.get(CaptureResult.LENS_FOCAL_LENGTH);
                if (focal != null) {
                    lastFocalMm = focal;
                    lastFocalResult = String.format(Locale.US, "%.3fmm", focal);
                }
                if (af != null) {
                    String expMs = lastExposureTimeNs > 0 ? String.format(Locale.US, "%.1fms", lastExposureTimeNs / 1_000_000.0) : "—";
                    String isoStr = lastSensitivity > 0 ? (" • ISO " + lastSensitivity) : "";
                    focusDiagnostics = afStateName(af) + " • AE " + aeStateName(lastAeState) + " • " + expMs + isoStr;
                }
                updateStreamGeometryDiagnostics();
                if (rz != null) {
                    CapturePlanner.StabilityLevel stability = CapturePlanner.estimateSceneStability(
                            recentPreviewShiftPx, lastExposureTimeNs, zoom, oisSupported);
                    int workW = jpegSize == null ? 4096 : Math.max(1, jpegSize.getWidth());
                    int workH = jpegSize == null ? 3072 : Math.max(1, jpegSize.getHeight());
                    int maxImg = reader == null ? 8 : reader.getMaxImages();
                    CapturePlanner.CapturePlan plan = CapturePlanner.planCapture(
                            zoom,
                            Math.max(1f, maxHardware),
                            lastFocalMm,
                            lastExposureTimeNs,
                            lastSensitivity > 0 ? lastSensitivity : 200,
                            oisSupported,
                            stability,
                            mode == Mode.NIGHT,
                            sceneHdrDetected,
                            maxImg,
                            workW,
                            workH);
                    activeCapturePlan = plan;
                    imageEngineDiagnostics = String.format(Locale.US,
                            "%s (%dF) • %s • Útil %.0f× • OIS %s",
                            plan.regime.label,
                            plan.targetFrames,
                            plan.stability.label,
                            plan.effectiveMaxUsefulZoom,
                            oisSupported ? "ON" : "OFF");
                    capturePassiveAnchorIfNeeded(rz);
                }
                postInvalidate();
            } catch (Exception ignored) { }
        }
    };

    private int plannedFramesForCurrentMode() {
        int workW = jpegSize == null ? 4096 : Math.max(1, jpegSize.getWidth());
        int workH = jpegSize == null ? 3072 : Math.max(1, jpegSize.getHeight());
        int desired;
        if (mode == Mode.MAX) desired = 6;
        else if (mode == Mode.NIGHT) desired = 5;
        else if (mode == Mode.ULTRA) desired = 4;
        else if (zoom > 1.35f) desired = 4;
        else desired = 3;
        int maxImg = reader == null ? 8 : reader.getMaxImages();
        return MemoryPolicy.safeTargetFrames(desired, maxImg, 8, workW, workH, MemoryPolicy.FULL_RES_FRAME_BUDGET_BYTES);
    }

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
     * Validates against the hardware zoom target (lastHardwareRequestedZoom).
     */
    private void updateZoomConfidence(CaptureRequest request, Float resultZoom) {
        if (request == null) return;
        Float requestedRatio = request.get(CaptureRequest.CONTROL_ZOOM_RATIO);
        float requested = requestedRatio == null ? lastHardwareRequestedZoom : requestedRatio;
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

            float srFactor = currentSrFactor();
            if (srFactor > 1.005f) {
                matrix.postScale(srFactor, srFactor, targetRect.centerX(), targetRect.centerY());
            }

            preview.setTransform(matrix);

            orientationDiagnostics = String.format(
                    Locale.US,
                    "%d°/%d° (rel %d°) • 4:3 UNIFORM%s • %dx%d",
                    displayDegrees,
                    sensorOrientation,
                    relative,
                    srFactor > 1.01f ? String.format(Locale.US, " SR %.1f×", srFactor) : "",
                    Math.round(vp.width),
                    Math.round(vp.height));
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
        float maxTotal = maxTotalZoom();
        final float targetZoom = Math.max(1f, Math.min(maxTotal, z));
        final float targetHw = Math.min(Math.max(1f, maxHardware), targetZoom);
        final float prevHw = Math.min(Math.max(1f, maxHardware), Math.max(1f, previousZoom));

        boolean needLensReopen = !mainCameraId.equals(cameraId) || activePhysicalId != null;
        if (!needLensReopen && Math.abs(previousZoom - targetZoom) > 0.01f) beginAeZoomLock();
        cameraId = mainCameraId;

        // If user taps a distant zoom pill (e.g. 1x -> 10x or 30x) and no mid-zoom bridge anchor (~3.0x)
        // exists yet, perform a 220ms optical bridge through 3.0x so Layer B observes 1.0->3.0 and 3.0->10.0
        boolean largeJump = triggerDiagnostic && !needLensReopen
                && Math.max(targetHw, prevHw) / Math.max(0.1f, Math.min(targetHw, prevHw)) > 3.4f
                && !hasBridgeAnchorBetween(Math.min(targetHw, prevHw), Math.max(targetHw, prevHw));

        if (largeJump) {
            final float midHw = Math.min(Math.max(1f, maxHardware), 3.0f);
            zoom = midHw;
            applyPreview();
            postDelayed(() -> {
                if (!isBusyForDiagnostic() && preview != null && preview.isAvailable()) {
                    try {
                        Bitmap bmMid = preview.getBitmap(320, 240);
                        if (bmMid != null) {
                            workHandler.post(() -> processPreviewDiagnostic(bmMid, midHw));
                        }
                    } catch (Exception ignored) { }
                }
                zoom = targetZoom;
                applyPreview();
                scheduleAutoDiagnostic(targetHw);
                postInvalidate();
            }, 220L);
        } else {
            zoom = targetZoom;
            if (needLensReopen) {
                close();
                open();
            } else {
                applyPreview();
            }
            if (triggerDiagnostic) {
                scheduleAutoDiagnostic(targetHw);
            }
        }

        float sr = currentSrFactor();
        if (sr > 1.02f) {
            status = String.format(Locale.US, "HYPERZOOM SR %s • HW %.0f× • SR %.1f×", zoomString(), maxHardware, sr);
        } else {
            status = "ZOOM • " + zoomString() + " • " + zoomConfidenceLabel();
        }
        postInvalidate();
    }

    private boolean hasBridgeAnchorBetween(float lowZ, float highZ) {
        synchronized (diagnosticAnchorMap) {
            for (Float k : diagnosticAnchorMap.keySet()) {
                if (k != null && k > lowZ * 1.15f && k < highZ * 0.85f
                        && DiagnosticSessionStore.hasSufficientOverlap(lowZ, k)
                        && DiagnosticSessionStore.hasSufficientOverlap(k, highZ)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void capturePassiveAnchorIfNeeded(float currentResultZoom) {
        if (busy || pinchDistance > 0f || draggingZoomSlider) return;
        if (Math.abs(currentResultZoom - lastHardwareRequestedZoom) > 0.18f) return;
        float bucket = Math.round(currentResultZoom * 2f) / 2f;
        if (bucket < 1.0f) return;
        long now = System.currentTimeMillis();
        if (now - lastPassiveAnchorTime < 900L) return;
        boolean needAnchor;
        synchronized (diagnosticAnchorMap) {
            needAnchor = !diagnosticAnchorMap.containsKey(bucket);
        }
        if (!needAnchor) return;
        lastPassiveAnchorTime = now;
        final float zSnap = currentResultZoom;
        post(() -> {
            if (isBusyForDiagnostic() || preview == null || !preview.isAvailable()) return;
            try {
                Bitmap bm = preview.getBitmap(320, 240);
                if (bm != null) {
                    workHandler.post(() -> processPreviewDiagnostic(bm, zSnap));
                }
            } catch (Exception ignored) { }
        });
    }

    private void capture() {
        if (busy || session == null || device == null || reader == null) return;
        busy = true;
        synchronized (frames) {
            frames.clear();
        }
        finalizeScheduled = false;
        int workW = jpegSize == null ? 4096 : Math.max(1, jpegSize.getWidth());
        int workH = jpegSize == null ? 3072 : Math.max(1, jpegSize.getHeight());
        CapturePlanner.StabilityLevel stability = CapturePlanner.estimateSceneStability(
                recentPreviewShiftPx, lastExposureTimeNs, zoom, oisSupported);
        CapturePlanner.CapturePlan plan = CapturePlanner.planCapture(
                zoom,
                Math.max(1f, maxHardware),
                lastFocalMm,
                lastExposureTimeNs,
                lastSensitivity > 0 ? lastSensitivity : 200,
                oisSupported,
                stability,
                mode == Mode.NIGHT,
                sceneHdrDetected,
                reader.getMaxImages(),
                workW,
                workH);
        activeCapturePlan = plan;

        int desiredFrames = plan.targetFrames;
        if (mode == Mode.MAX) desiredFrames = Math.max(desiredFrames, 6);
        else if (mode == Mode.NIGHT) desiredFrames = Math.max(desiredFrames, 5);
        targetFrames = MemoryPolicy.safeTargetFrames(
                desiredFrames,
                reader.getMaxImages(),
                8,
                workW,
                workH,
                MemoryPolicy.FULL_RES_FRAME_BUDGET_BYTES);
        float sr = currentSrFactor();
        imageEngineDiagnostics = sr > 1.02f
                ? String.format(Locale.US, "SR DRIZZLE+OPTICAL FLOW • %dF • SR %.2f×", targetFrames, sr)
                : String.format(Locale.US, "FUSÃO MULTI-FRAME (%s) • %dF", plan.regime.label, targetFrames);
        completedCaptures = 0;
        status = targetFrames > 1
                ? (mode == Mode.NIGHT
                    ? "NIGHT • FUSÃO " + targetFrames + " FRAMES"
                    : (sr > 1.02f ? "HYPERZOOM SR • " + targetFrames + " FRAMES" : "FUSÃO HD • " + targetFrames + " FRAMES"))
                : "CAPTURANDO • " + zoomString();
        issueCapture();
        postInvalidate();
    }

    private void issueCapture() {
        if (!busy) return;
        try {
            CaptureRequest.Builder b = request(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(reader.getSurface());
            b.set(CaptureRequest.JPEG_ORIENTATION, rotation());
            CapturePlanner.CapturePlan plan = activeCapturePlan;
            Range<Integer> ar = chars == null ? null : chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            if (plan != null && plan.hdrBracketingActive && plan.evBrackets != null
                    && completedCaptures < plan.evBrackets.length && ar != null) {
                int ev = Math.min(ar.getUpper(), Math.max(ar.getLower(), plan.evBrackets[completedCaptures]));
                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev);
            } else {
                if (plan != null && plan.recommendedAeCompEv != 0 && mode != Mode.NIGHT && ar != null) {
                    int ev = Math.min(ar.getUpper(), Math.max(ar.getLower(), plan.recommendedAeCompEv));
                    b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev);
                }
                if (completedCaptures > 0) {
                    if (aeLockSupported) b.set(CaptureRequest.CONTROL_AE_LOCK, true);
                    if (awbLockSupported) b.set(CaptureRequest.CONTROL_AWB_LOCK, true);
                }
            }
            session.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult result) {
                    completedCaptures++;
                    if (completedCaptures < targetFrames) {
                        workHandler.postDelayed(() -> issueCapture(), mode == Mode.NIGHT ? 95 : 42);
                    } else {
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
        if (input == null || input.isEmpty()) return null;
        Bitmap ref0 = input.get(0);
        if (ref0 == null || ref0.isRecycled()) return null;
        int w = ref0.getWidth(), h = ref0.getHeight();
        float srFactor = currentSrFactor();
        boolean hdrActive = activeCapturePlan != null && activeCapturePlan.hdrBracketingActive;

        // Smart ROI Extraction & Working Grid Policy:
        // - When srFactor > 1.02x (e.g., 10x..30x HyperZoom), extract the exact 1:1 native sensor center ROI
        //   directly from each Bitmap before allocating Java heap arrays, cutting RAM by up to 85% while
        //   preserving 100% of native sensor spatial frequencies in the zoomed target region.
        // - Reconstruct to bounded high-resolution grid (up to 2560x1920) so all 7 stages complete in <400ms.
        int maxOutW = Math.min(w, 2560);
        int maxOutH = Math.min(h, Math.max(1, Math.round((float) h * maxOutW / Math.max(1, w))));
        List<int[]> rawBuffers = new ArrayList<int[]>(input.size());
        int roiW;
        int roiH;
        float engineSrFactor;
        int engineOutW;
        int engineOutH;

        if (srFactor > 1.02f) {
            roiW = Math.max(64, Math.min(w, Math.round(w / srFactor)));
            roiH = Math.max(64, Math.min(h, Math.round(h / srFactor)));
            int roiX = Math.max(0, (w - roiW) / 2);
            int roiY = Math.max(0, (h - roiH) / 2);
            for (int i = 0; i < input.size(); i++) {
                Bitmap b = input.get(i);
                if (b == null || b.isRecycled() || b.getWidth() != w || b.getHeight() != h) continue;
                int[] px = new int[roiW * roiH];
                b.getPixels(px, 0, roiW, roiX, roiY, roiW, roiH);
                rawBuffers.add(px);
            }
            engineSrFactor = 1.0f;
            engineOutW = maxOutW;
            engineOutH = maxOutH;
        } else {
            roiW = maxOutW;
            roiH = maxOutH;
            for (int i = 0; i < input.size(); i++) {
                Bitmap b = input.get(i);
                if (b == null || b.isRecycled() || b.getWidth() != w || b.getHeight() != h) continue;
                Bitmap workBm = (w == roiW && h == roiH) ? b : Bitmap.createScaledBitmap(b, roiW, roiH, true);
                int[] px = new int[roiW * roiH];
                workBm.getPixels(px, 0, roiW, 0, 0, roiW, roiH);
                if (workBm != b && !workBm.isRecycled()) workBm.recycle();
                rawBuffers.add(px);
            }
            engineSrFactor = 1.0f;
            engineOutW = roiW;
            engineOutH = roiH;
        }

        if (rawBuffers.isEmpty()) return ref0;
        SuperResolutionEngine.BurstResult srResult = SuperResolutionEngine.processBurstFull(
                rawBuffers, roiW, roiH, engineSrFactor, engineOutW, engineOutH, zoom, mode == Mode.NIGHT, hdrActive);
        if (srResult == null || srResult.pixels == null || srResult.pixels.length == 0) return ref0;
        lastZoomDx = Math.round(srResult.lastDx);
        lastZoomDy = Math.round(srResult.lastDy);
        imageEngineDiagnostics = srResult.diagnostics;
        lastCaptureEngineDiag = srResult.diagnostics;
        lastCaptureSciTelemetry = srResult.scientificTelemetry;
        if (srResult.qualityComparison != null) {
            lastQualityVerdict = String.format(
                    Locale.US,
                    "Det %+.1f%% • MTF %+.1f%% • SNR %+.1fdB",
                    srResult.qualityComparison.acutanceGainPct,
                    srResult.qualityComparison.mtfGainPct,
                    srResult.qualityComparison.snrGainDb);
        }
        Bitmap out = Bitmap.createBitmap(srResult.width, srResult.height, Bitmap.Config.ARGB_8888);
        out.setPixels(srResult.pixels, 0, srResult.width, 0, 0, srResult.width, srResult.height);
        return out;
    }

    private void finalizeCapture() {
        if (!busy) return;
        Bitmap best = null;
        Bitmap output = null;
        synchronized (frames) {
            if (!frames.isEmpty()) {
                best = frames.get(0);
                output = fuseFrames(new ArrayList<Bitmap>(frames));
                if (output == null) output = best;
            }
        }
        boolean savedOk = false;
        if (output != null && !output.isRecycled()) {
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
            else status = "FOTO SALVA • " + zoomString() + " (" + sizeString(jpegSize) + ")";
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
                    b.compress(Bitmap.CompressFormat.JPEG, 100, o);
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
        float sr = currentSrFactor();
        float nx = norm.x;
        float ny = norm.y;
        if (sr > 1.01f) {
            nx = Math.max(0f, Math.min(1f, 0.5f + (nx - 0.5f) / sr));
            ny = Math.max(0f, Math.min(1f, 0.5f + (ny - 0.5f) / sr));
        }
        Rect activeArea = effectiveMapCrop();
        if (activeArea == null || activeArea.width() <= 0 || activeArea.height() <= 0) activeArea = sensor;

        int rw = Math.max(80, Math.round(activeArea.width() / (10f * sr)));
        int rh = Math.max(80, Math.round(activeArea.height() / (10f * sr)));
        int cx = activeArea.left + Math.round(nx * activeArea.width());
        int cy = activeArea.top + Math.round(ny * activeArea.height());
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

    /**
     * Passively refreshes the minimap 1.0x reference thumbnail from the live TextureView
     * whenever the user is near 1.0x zoom, NEVER issuing a competing Camera2 capture request
     * at 1.0x (which previously reset CONTROL_ZOOM_RATIO / SCALER_CROP_REGION on the HAL).
     */
    private void captureMiniIfNeeded() {
        if (session == null || busy || preview == null || !preview.isAvailable()) return;
        long now = System.currentTimeMillis();
        if (miniCapturePending || now - lastMiniCapture < 1100) return;
        miniCapturePending = true;
        lastMiniCapture = now;
        final float curZoom = zoom;
        try {
            final Bitmap snap = preview.getBitmap(240, 180);
            if (snap == null) {
                miniCapturePending = false;
                return;
            }
            workHandler.post(() -> {
                try {
                    // Analyze live preview stability (motion shift) and HDR dynamic range
                    byte[] g = toGray(snap, 96, 72);
                    if (g != null) {
                        updatePreviewSceneTelemetry(g, 96, 72);
                    }
                    if (curZoom <= 1.18f) {
                        Bitmap old = miniBitmap;
                        miniBitmap = snap;
                        if (old != null && old != snap && !old.isRecycled()) old.recycle();
                    } else {
                        if (!snap.isRecycled()) snap.recycle();
                    }
                } finally {
                    miniCapturePending = false;
                }
                postInvalidate();
            });
        } catch (Exception e) {
            miniCapturePending = false;
        }
    }

    private void updatePreviewSceneTelemetry(byte[] gray, int w, int h) {
        if (gray == null || gray.length < w * h) return;
        int highlightClip = 0;
        int shadowCrush = 0;
        int minL = 255, maxL = 0;
        for (int i = 0; i < gray.length; i++) {
            int v = gray[i] & 0xFF;
            if (v >= 242) highlightClip++;
            if (v <= 14) shadowCrush++;
            if (v < minL) minL = v;
            if (v > maxL) maxL = v;
        }
        float hiFrac = (float) highlightClip / gray.length;
        float loFrac = (float) shadowCrush / gray.length;
        float dynStops = (float) (Math.log(Math.max(16, maxL) / (double) Math.max(4, minL)) / Math.log(2.0)) * 2.1f;
        sceneHdrDetected = CapturePlanner.detectHdrScene(hiFrac, loFrac, dynStops);

        if (prevStabilityGray != null && prevStabilityGray.length == gray.length) {
            long diffSum = 0;
            for (int i = 0; i < gray.length; i++) {
                diffSum += Math.abs((gray[i] & 0xFF) - (prevStabilityGray[i] & 0xFF));
            }
            float mad = (float) diffSum / gray.length;
            recentPreviewShiftPx = Math.max(0.08f, Math.min(4.0f, mad * 0.14f));
        }
        prevStabilityGray = gray;
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
        final float hwZoom = Math.min(Math.max(1f, maxHardware), requestedZoom);
        diagnosticPendingZoom = hwZoom;
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
            // Schedule a second verification pass 550ms later if the level is still PROBING so a single zoom gesture can reach CONFIRMED (2 good samples)
            postDelayed(() -> {
                if (isBusyForDiagnostic() || pinchDistance > 0f || draggingZoomSlider) return;
                try {
                    Bitmap bm2 = preview.getBitmap(320, 240);
                    if (bm2 == null) return;
                    final float z2 = Math.min(Math.max(1f, maxHardware), zoom);
                    workHandler.post(() -> processPreviewDiagnostic(bm2, z2));
                } catch (Exception ignored) { }
            }, 550L);
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

        // Multi-anchor selection: compare against the closest overlapping reference from diagnosticAnchorMap or recent samples
        byte[] refGray = null;
        float refZoom = 0f;
        float bestRatioDiff = Float.MAX_VALUE;

        synchronized (diagnosticAnchorMap) {
            for (Map.Entry<Float, byte[]> entry : diagnosticAnchorMap.entrySet()) {
                float az = entry.getKey();
                if (Math.abs(az - z) < 0.18f) continue;
                if (DiagnosticSessionStore.hasSufficientOverlap(az, z)) {
                    float ratio = Math.max(az, z) / Math.max(0.1f, Math.min(az, z));
                    // Prefer moderate zoom ratios (~1.6x..2.8x) for maximum ZNCC template overlap
                    float diff = Math.abs(ratio - 2.1f);
                    if (diff < bestRatioDiff) {
                        bestRatioDiff = diff;
                        refZoom = az;
                        refGray = entry.getValue();
                    }
                }
            }
        }

        if (refGray == null) {
            if (diagnosticLastGray != null && DiagnosticSessionStore.hasSufficientOverlap(diagnosticLastSampleZoom, z)) {
                refGray = diagnosticLastGray;
                refZoom = diagnosticLastSampleZoom;
            } else if (diagnosticAnchor1xGray != null && DiagnosticSessionStore.hasSufficientOverlap(diagnosticAnchor1xZoom, z)) {
                refGray = diagnosticAnchor1xGray;
                refZoom = diagnosticAnchor1xZoom;
            } else if (diagnosticAnchorMidGray != null && DiagnosticSessionStore.hasSufficientOverlap(diagnosticAnchorMidZoom, z)) {
                refGray = diagnosticAnchorMidGray;
                refZoom = diagnosticAnchorMidZoom;
            }
        }

        if (refGray != null && refZoom > 0f && Math.abs(z - refZoom) >= 0.18f) {
            float ratio = z / Math.max(0.01f, refZoom);
            float expected = ratio >= 1f ? ratio : 1f / Math.max(0.01f, ratio);
            ScaleEstimator.Result r = ratio >= 1f
                    ? ScaleEstimator.estimate(refGray, 160, 120, gray, 160, 120, Math.max(1f, expected * 0.65f), Math.min(5f, expected * 1.35f))
                    : ScaleEstimator.estimate(gray, 160, 120, refGray, 160, 120, Math.max(1f, expected * 0.65f), Math.min(5f, expected * 1.35f));
            diagnosticStore.recordObservedPair(refZoom, z, r.scale, r.confidence);
            persistDiagnosticState();
        }

        // Store anchor in bucketed map (0.5x resolution buckets) + dedicated 1x/mid anchors
        float bucket = Math.round(z * 2f) / 2f;
        synchronized (diagnosticAnchorMap) {
            if (diagnosticAnchorMap.size() >= 16 && !diagnosticAnchorMap.containsKey(bucket)) {
                Float firstKey = diagnosticAnchorMap.keySet().iterator().next();
                if (firstKey != null && firstKey > 1.2f) {
                    diagnosticAnchorMap.remove(firstKey);
                }
            }
            diagnosticAnchorMap.put(bucket, gray);
        }
        if (z <= 1.15f) {
            diagnosticAnchor1xGray = gray;
            diagnosticAnchor1xZoom = z;
        } else if (z >= 1.8f && z <= 3.5f) {
            diagnosticAnchorMidGray = gray;
            diagnosticAnchorMidZoom = z;
        }
        if (diagnosticLastGray == null || Math.abs(z - diagnosticLastSampleZoom) >= 0.18f) {
            diagnosticLastGray = gray;
            diagnosticLastSampleZoom = z;
        }
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
            float effectiveHwZoom = Math.min(Math.max(1f, maxHardware), zoom);

            diagnosticStore.recordJpegEvidence(
                    effectiveHwZoom, bw, bh, fineDetail, coarseDetail, edgeCount, thumbGray, 160, 120);
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
        j.append("  \"maxTotalZoom\":").append(String.format(Locale.US, "%.3f", maxTotalZoom())).append(",\n");
        j.append("  \"maxSamplesPerLevel\":").append(MAX_SAMPLES_PER_LEVEL).append(",\n");
        j.append("  \"maxTotalSamples\":").append(MAX_TOTAL_DIAGNOSTIC_SAMPLES).append(",\n");
        j.append("  \"samples\":").append(diagnosticStore.getTotalSamples()).append(",\n");
        j.append("  \"pairs\":").append(diagnosticStore.getTotalPairsStored()).append(",\n");
        j.append("  \"jpegs\":").append(diagnosticStore.getJpegs().size()).append(",\n");
        j.append("  \"scientificTelemetry\":\"").append(escapeJson(lastCaptureSciTelemetry == null ? "" : lastCaptureSciTelemetry)).append("\",\n");
        j.append("  \"qualityVerdict\":\"").append(escapeJson(lastQualityVerdict == null ? "" : lastQualityVerdict)).append("\",\n");
        j.append("  \"capturePlan\":\"").append(escapeJson(activeCapturePlan == null ? "" : activeCapturePlan.planTelemetry)).append("\",\n");
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

    private boolean draggingZoomSlider;

    private void applySliderTouch(float x, boolean finishGesture) {
        float ratio = Math.max(0f, Math.min(1f, (x - ui.zoomTrack.left) / Math.max(1f, ui.zoomTrack.width())));
        double minSlider = wideSupported ? 0.5 : 1.0;
        double maxSlider = Math.max(minSlider + 0.01, maxTotalZoom());
        float nz = (float) Math.exp(Math.log(minSlider) + ratio * (Math.log(maxSlider) - Math.log(minSlider)));
        if (wideSupported && nz < 0.96f) {
            selectWide(nz);
        } else {
            selectMain(Math.max(1f, nz), finishGesture);
        }
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) { return true; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int w = getWidth(), h = getHeight();
        float x = e.getX(), y = e.getY();
        ui.compute(w, h);
        validateLayout();

        if (e.getPointerCount() == 2 || pinchDistance > 0) {
            draggingZoomSlider = false;
            if (e.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() >= 2) {
                pinchDistance = distance(e);
                pinchStart = zoom;
                beginAeZoomLock();
            } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && e.getPointerCount() >= 2 && pinchDistance > 0) {
                float minAllowed = wideSupported ? 0.5f : 1.0f;
                float nz = Math.max(minAllowed, Math.min(maxTotalZoom(), pinchStart * distance(e) / pinchDistance));
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
                    scheduleAutoDiagnostic(Math.min(Math.max(1f, maxHardware), zoom));
                }
            }
            return true;
        }

        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (!showInfo && !showCameraMenu && !showModeMenu && ui.zoomArea.contains(x, y)) {
                draggingZoomSlider = true;
                beginAeZoomLock();
                applySliderTouch(x, false);
                return true;
            }
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (draggingZoomSlider) {
                applySliderTouch(x, false);
                return true;
            }
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            if (draggingZoomSlider) {
                draggingZoomSlider = false;
                aeZoomLockedByGesture = false;
                scheduleAeUnlock();
            }
            return true;
        }
        if (action != MotionEvent.ACTION_UP) return true;

        if (draggingZoomSlider) {
            draggingZoomSlider = false;
            aeZoomLockedByGesture = false;
            scheduleAeUnlock();
            applySliderTouch(x, true);
            return true;
        }

        if (showInfo) {
            showInfo = false;
            postInvalidate();
            return true;
        }
        if (showCameraMenu) {
            int shown = Math.min(ui.cameraItems.length, publicCameraRecords.size());
            for (int i = 0; i < shown; i++) {
                if (ui.cameraItems[i].contains(x, y)) {
                    selectCamera(publicCameraRecords.get(i).id);
                    return true;
                }
            }
            showCameraMenu = false;
            postInvalidate();
            return true;
        }
        if (showModeMenu) {
            for (int i = 0; i < MODES.length; i++) {
                if (ui.modeItems[i].contains(x, y)) {
                    mode = Mode.values()[i];
                    showModeMenu = false;
                    status = MODES[i] + " • PRONTO";
                    applyPreview();
                    postInvalidate();
                    return true;
                }
            }
            showModeMenu = false;
            postInvalidate();
            return true;
        }
        if (ui.logExport.contains(x, y)) { exportDiagnosticLog(); return true; }
        if (ui.camera.contains(x, y)) { showCameraMenu = true; postInvalidate(); return true; }
        if (ui.info.contains(x, y) || ui.telemetryCard.contains(x, y) || ui.zoomBadge.contains(x, y)) {
            showInfo = true;
            postInvalidate();
            return true;
        }
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
                if (targets[i] < 0.99f) selectWide();
                else selectMain(targets[i]);
                return true;
            }
        }
        if (ui.zoomArea.contains(x, y)) {
            applySliderTouch(x, true);
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
