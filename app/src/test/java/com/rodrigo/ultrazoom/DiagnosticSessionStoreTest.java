package com.rodrigo.ultrazoom;

import com.rodrigo.ultrazoom.diagnostic.DiagnosticSessionStore;
import com.rodrigo.ultrazoom.diagnostic.ZoomConfidenceEngine;
import org.junit.Test;
import static org.junit.Assert.*;

public class DiagnosticSessionStoreTest {
    @Test
    public void bPersistsByPairAndLevelAndUnknownDoesNotRevokeConfirmed() {
        DiagnosticSessionStore store = new DiagnosticSessionStore();

        // Record two strong matching observations for 1.0x -> 2.0x (expected = 2.0)
        store.recordObservedPair(1.0f, 2.0f, 2.01f, 0.85f);
        assertEquals(ZoomConfidenceEngine.State.PARTIAL, store.getPairState(1.0f, 2.0f));

        store.recordObservedPair(1.0f, 2.0f, 1.98f, 0.88f);
        assertEquals(ZoomConfidenceEngine.State.CONFIRMED, store.getPairState(1.0f, 2.0f));
        assertEquals(ZoomConfidenceEngine.State.CONFIRMED, store.getLevelState(2.0f));
        assertEquals(ZoomConfidenceEngine.State.CONFIRMED, store.getLayerBState());

        // Subsequent inconclusive observations (blank wall / low confidence / NaN) must NOT revoke CONFIRMED
        store.recordObservedPair(1.0f, 2.0f, Float.NaN, 0.0f);
        store.recordObservedPair(1.0f, 2.0f, 1.4f, 0.20f);
        assertEquals(ZoomConfidenceEngine.State.CONFIRMED, store.getPairState(1.0f, 2.0f));
        assertEquals(ZoomConfidenceEngine.State.CONFIRMED, store.getLevelState(2.0f));
        assertEquals(ZoomConfidenceEngine.State.CONFIRMED, store.getLayerBState());
    }

    @Test
    public void bContradictionsGenerateAbsorbingFail() {
        DiagnosticSessionStore store = new DiagnosticSessionStore();
        // Requested 1.0x -> 2.0x (expected 2.0), but camera actually stayed at ~1.0x with high confidence
        store.recordObservedPair(1.0f, 2.0f, 1.02f, 0.86f);
        store.recordObservedPair(1.0f, 2.0f, 1.01f, 0.89f);
        assertEquals(ZoomConfidenceEngine.State.FAIL, store.getPairState(1.0f, 2.0f));
        assertEquals(ZoomConfidenceEngine.State.FAIL, store.getLevelState(2.0f));
        assertEquals(ZoomConfidenceEngine.State.FAIL, store.getLayerBState());

        // FAIL is absorbing even if a later observation looks good
        store.recordObservedPair(1.0f, 2.0f, 2.0f, 0.90f);
        assertEquals(ZoomConfidenceEngine.State.FAIL, store.getPairState(1.0f, 2.0f));
        assertEquals(ZoomConfidenceEngine.State.FAIL, store.getLayerBState());
        assertEquals(
                ZoomConfidenceEngine.State.REVOKED,
                ZoomConfidenceEngine.combine(ZoomConfidenceEngine.State.CONFIRMED, store.getLayerBState(), ZoomConfidenceEngine.State.CONFIRMED));
    }

    @Test
    public void cRejectsCropAndUpscaleAndConfirmsRealDetailAcrossLevels() {
        DiagnosticSessionStore store = new DiagnosticSessionStore();
        // 1.0x JPEG with sharp native fine detail (fine=42, coarse=50 -> acutanceRatio=0.84)
        store.recordJpegEvidence(1.0f, 4096, 3072, 42f, 50f, 60, null, 0, 0);
        assertEquals(ZoomConfidenceEngine.State.UNKNOWN, store.getLayerCState());

        // 2.0x JPEG preserving real native acutance (fine=36, coarse=48 -> acutanceRatio=0.75)
        store.recordJpegEvidence(2.0f, 4096, 3072, 36f, 48f, 55, null, 0, 0);
        assertEquals(ZoomConfidenceEngine.State.CONFIRMED, store.getLayerCState());

        // Now test a separate store where 3.0x is a fake crop+upscale (severe loss of fine acutance)
        DiagnosticSessionStore fakeZoomStore = new DiagnosticSessionStore();
        fakeZoomStore.recordJpegEvidence(1.0f, 4096, 3072, 42f, 50f, 60, null, 0, 0);
        // Blurred upscale: fine detail collapses to 5f while coarse is 40f (acutanceRatio=0.125)
        fakeZoomStore.recordJpegEvidence(3.0f, 4096, 3072, 5f, 40f, 45, null, 0, 0);
        assertEquals(ZoomConfidenceEngine.State.FAIL, fakeZoomStore.getLayerCState());
    }

    @Test
    public void serializationRoundTripPreservesState() {
        DiagnosticSessionStore store = new DiagnosticSessionStore();
        store.recordObservedPair(1.0f, 2.0f, 2.0f, 0.86f);
        store.recordObservedPair(1.0f, 2.0f, 1.99f, 0.88f);
        store.recordJpegEvidence(1.0f, 4096, 3072, 40f, 48f, 50, null, 0, 0);
        store.recordJpegEvidence(2.0f, 4096, 3072, 35f, 45f, 48, null, 0, 0);

        String serialized = store.serialize();
        DiagnosticSessionStore restored = new DiagnosticSessionStore();
        restored.deserialize(serialized);

        assertEquals(ZoomConfidenceEngine.State.CONFIRMED, restored.getPairState(1.0f, 2.0f));
        assertEquals(ZoomConfidenceEngine.State.CONFIRMED, restored.getLevelState(2.0f));
        assertEquals(ZoomConfidenceEngine.State.CONFIRMED, restored.getLayerBState());
        assertEquals(ZoomConfidenceEngine.State.CONFIRMED, restored.getLayerCState());
        assertEquals(1, restored.getConfirmedLevelsCount());
    }

    @Test
    public void staticRegressionAndArtifactVerification() throws Exception {
        java.io.File src = new java.io.File("src/main/java/com/rodrigo/ultrazoom/MainActivity.java");
        if (!src.exists()) {
            src = new java.io.File("app/src/main/java/com/rodrigo/ultrazoom/MainActivity.java");
        }
        assertTrue("MainActivity.java must exist", src.exists());
        String content = new String(java.nio.file.Files.readAllBytes(src.toPath()), java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(content.contains("UZ-124-AUTODIAGNOSTIC"));
        assertTrue(content.contains("CONTROL_AF_TRIGGER_CANCEL"));
        assertTrue(content.contains("MAX_SAMPLES_PER_LEVEL"));
        assertTrue(content.contains("ScaleEstimator"));
        assertTrue(content.contains("MemoryPolicy.safeTargetFrames"));
        assertFalse(content.contains("applySoftwareZoomToPhoto"));
        assertFalse(content.contains("calibrationActive"));
        assertFalse(content.contains("toggleCalibration"));
        assertFalse(content.contains("recordCalibrationSample"));
        assertFalse(content.contains(".invalidate();"));

        java.io.File apk = new java.io.File("build/outputs/apk/debug/app-debug.apk");
        if (!apk.exists()) {
            apk = new java.io.File("app/build/outputs/apk/debug/app-debug.apk");
        }
        if (apk.exists()) {
            System.out.println("VERIFIED_APK: " + apk.getAbsolutePath() + " size=" + apk.length() + " bytes");
            assertTrue("APK must be non-empty", apk.length() > 0);
        }

        java.io.File zip = new java.io.File("../UltraZoom-00-v12.4.0-AUTODIAGNOSTIC.zip");
        if (!zip.exists()) {
            zip = new java.io.File("UltraZoom-00-v12.4.0-AUTODIAGNOSTIC.zip");
        }
        if (zip.exists()) {
            System.out.println("VERIFIED_ZIP: " + zip.getAbsolutePath() + " size=" + zip.length() + " bytes");
            assertTrue("ZIP must be non-empty", zip.length() > 0);
        }
    }
}
