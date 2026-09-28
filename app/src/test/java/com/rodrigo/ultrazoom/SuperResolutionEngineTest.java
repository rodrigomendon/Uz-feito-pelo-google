package com.rodrigo.ultrazoom;

import com.rodrigo.ultrazoom.diagnostic.CapturePlanner;
import com.rodrigo.ultrazoom.diagnostic.DeconvolutionEngine;
import com.rodrigo.ultrazoom.diagnostic.FrameQualityAnalyzer;
import com.rodrigo.ultrazoom.diagnostic.LocalRegistration;
import com.rodrigo.ultrazoom.diagnostic.QualityMetrics;
import com.rodrigo.ultrazoom.diagnostic.SuperResolutionEngine;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class SuperResolutionEngineTest {

    @Test
    public void lanczos3KernelSatisfiesInterpolationProperties() {
        assertEquals(1.0f, SuperResolutionEngine.lanczos3(0.0f), 0.0001f);
        assertEquals(0.0f, SuperResolutionEngine.lanczos3(1.0f), 0.005f);
        assertEquals(0.0f, SuperResolutionEngine.lanczos3(2.0f), 0.005f);
        assertEquals(0.0f, SuperResolutionEngine.lanczos3(3.0f), 0.0001f);
        assertEquals(0.0f, SuperResolutionEngine.lanczos3(3.5f), 0.0001f);
        assertTrue(SuperResolutionEngine.lanczos3(0.4f) > 0.5f);
    }

    @Test
    public void luckyImagingSelectsSharpestFrameOverMotionBlurredFrame() {
        int w = 96, h = 72;
        int[] blurred = new int[w * h];
        int[] sharp = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int valSharp = ((x / 3 + y / 3) % 2 == 0) ? 230 : 25;
                int valBlur = 120 + (int) Math.round(15.0 * Math.sin(x * 0.1));
                sharp[y * w + x] = 0xFF000000 | (valSharp << 16) | (valSharp << 8) | valSharp;
                blurred[y * w + x] = 0xFF000000 | (valBlur << 16) | (valBlur << 8) | valBlur;
            }
        }
        List<int[]> burst = new ArrayList<int[]>();
        burst.add(blurred);
        burst.add(sharp);
        int bestIdx = SuperResolutionEngine.selectSharpestReferenceIndex(burst, w, h);
        assertEquals(1, bestIdx);

        FrameQualityAnalyzer.BurstQualityReport qr = FrameQualityAnalyzer.analyzeBurst(burst, w, h);
        assertEquals(1, qr.referenceIndex);
        assertTrue("Sharp frame must have higher 0-100 score than blurred frame",
                qr.scores[1].score0To100 > qr.scores[0].score0To100);
    }

    @Test
    public void subpixelRegistrationRecoversFrameOffsetAndSuperResolvesBurst() {
        int w = 96, h = 72;
        int[] f0 = new int[w * h];
        int[] f1 = new int[w * h];
        int[] f2 = new int[w * h];
        int[] f3 = new int[w * h];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                f0[y * w + x] = synthRgb(x, y);
                f1[y * w + x] = synthRgb(x - 1.4f, y + 0.85f);
                f2[y * w + x] = synthRgb(x + 0.75f, y - 0.55f);
                f3[y * w + x] = synthRgb(x - 0.45f, y - 1.15f);
            }
        }

        SuperResolutionEngine.SubpixelShift sh = SuperResolutionEngine.estimateSubpixelShift(f0, f1, w, h);
        assertEquals(1.4f, sh.dx, 0.75f);
        assertEquals(-0.85f, sh.dy, 0.75f);
        assertTrue(sh.confidence > 0.3f);

        List<int[]> burst = new ArrayList<int[]>();
        burst.add(f0);
        burst.add(f1);
        burst.add(f2);
        burst.add(f3);

        SuperResolutionEngine.BurstResult res = SuperResolutionEngine.processBurst(
                burst, w, h, 2.0f, 20.0f, false);
        assertNotNull(res);
        assertEquals(w, res.width);
        assertEquals(h, res.height);
        assertEquals(w * h, res.pixels.length);
        assertTrue("Subpixel coverage should be positive for diverse shifts", res.subpixelCoverage > 0.20f);
        assertNotNull(res.qualityComparison);
        assertNotNull(res.profileReport);
        assertNotNull(res.artifactReport);
        assertTrue("Ringing must remain within safe bounds", res.artifactReport.ringingScore <= 0.25f);
        double acutance = SuperResolutionEngine.computeSharpnessScore(res.pixels, w, h);
        assertTrue("Expected positive acutance from SR output, got " + acutance, acutance > 50.0);
        assertTrue(res.diagnostics.contains("SR DRIZZLE+LANCZOS3"));
    }

    @Test
    public void localOpticalFlowDetectsDynamicObjectAndPreventsGhosting() {
        int w = 96, h = 72;
        int[] ref = new int[w * h];
        int[] targetWithMovingObj = new int[w * h];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = synthRgb(x, y);
                ref[y * w + x] = c;
                // In target frame, inject a bright moving object inside block (1, 1)
                if (x >= 26 && x <= 44 && y >= 26 && y <= 44) {
                    targetWithMovingObj[y * w + x] = 0xFFFFFFFF;
                } else {
                    targetWithMovingObj[y * w + x] = synthRgb(x - 0.5f, y + 0.4f);
                }
            }
        }

        LocalRegistration.MotionField4x3 mf = LocalRegistration.estimateMotionField(ref, targetWithMovingObj, w, h);
        assertNotNull(mf);
        assertTrue("Must detect at least 1 dynamic/moving block", mf.dynamicBlocksCount >= 1);

        List<int[]> burst = new ArrayList<int[]>();
        burst.add(ref);
        burst.add(targetWithMovingObj);

        SuperResolutionEngine.BurstResult res = SuperResolutionEngine.processBurstFull(
                burst, w, h, 1.0f, w, h, 10.0f, false, false);
        assertNotNull(res);
        assertTrue("Dynamic block must be suppressed so ghosting score stays low",
                res.artifactReport.ghostingScore < 0.06f);
    }

    @Test
    public void deconvolutionAndArtifactRollbackProtectAgainstRinging() {
        int w = 80, h = 60;
        int[] base = new int[w * h];
        int[] fused = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = synthRgb(x, y);
                base[y * w + x] = c;
                fused[y * w + x] = c;
            }
        }

        DeconvolutionEngine.ArtifactReport report =
                DeconvolutionEngine.processWithArtifactControl(fused, base, w, h, 0.32f, 0.65f);
        assertNotNull(report);
        assertTrue("Ringing score must be bounded after artifact control", report.ringingScore <= 0.24f);

        QualityMetrics.ComparisonReport cmp = QualityMetrics.compare(base, fused, w, h);
        assertNotNull(cmp);
        assertTrue("Processed acutance should exceed base acutance", cmp.acutanceGainPct > 0.0f);
    }

    @Test
    public void capturePlannerAdaptsRegimeShutterAndEffectiveUsefulZoom() {
        CapturePlanner.StabilityLevel stable =
                CapturePlanner.estimateSceneStability(0.12f, 6_000_000L, 20f, true);
        CapturePlanner.StabilityLevel shaky =
                CapturePlanner.estimateSceneStability(2.8f, 35_000_000L, 25f, false);

        assertEquals(CapturePlanner.StabilityLevel.TRIPOD, stable);
        assertEquals(CapturePlanner.StabilityLevel.HIGH_MOTION, shaky);

        CapturePlanner.CapturePlan planStable30x = CapturePlanner.planCapture(
                30f, 10f, 5.5f, 6_000_000L, 120, true,
                stable, false, false, 8, 2560, 1920);
        CapturePlanner.CapturePlan planShaky30x = CapturePlanner.planCapture(
                30f, 10f, 5.5f, 35_000_000L, 1800, false,
                shaky, false, false, 8, 2560, 1920);

        assertEquals(CapturePlanner.ZoomRegime.EXTREME_SR_20X_30X, planStable30x.regime);
        assertTrue("Stable tripod/OIS scene should allow higher effective useful zoom than high-motion high-ISO scene",
                planStable30x.effectiveMaxUsefulZoom > planShaky30x.effectiveMaxUsefulZoom);
        assertTrue("Shaky long-exposure capture at 30x should recommend negative AE EV to speed up shutter",
                planShaky30x.recommendedAeCompEv < 0);
    }

    private static int synthRgb(float x, float y) {
        double edge = (Math.abs(x - 48f) < 12f && Math.abs(y - 36f) < 10f) ? 45.0 : -35.0;
        int lum = (int) Math.round(128.0
                + 55.0 * Math.sin(x * 0.35) * Math.cos(y * 0.32)
                + 35.0 * Math.cos((x - y) * 0.24)
                + edge);
        lum = Math.max(0, Math.min(255, lum));
        return 0xFF000000 | (lum << 16) | (lum << 8) | lum;
    }
}
