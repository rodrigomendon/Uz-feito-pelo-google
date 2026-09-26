package com.rodrigo.ultrazoom;

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
    }

    @Test
    public void subpixelRegistrationRecoversFrameOffsetAndSuperResolvesBurst() {
        int w = 96, h = 72;
        int[] f0 = new int[w * h];
        int[] f1 = new int[w * h];
        int[] f2 = new int[w * h];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                f0[y * w + x] = synthRgb(x, y);
                f1[y * w + x] = synthRgb(x - 1.5f, y + 1.0f);
                f2[y * w + x] = synthRgb(x + 1.0f, y - 0.5f);
            }
        }

        SuperResolutionEngine.SubpixelShift sh = SuperResolutionEngine.estimateSubpixelShift(f0, f1, w, h);
        assertEquals(1.5f, sh.dx, 0.8f);
        assertEquals(-1.0f, sh.dy, 0.8f);
        assertTrue(sh.confidence > 0.3f);

        List<int[]> burst = new ArrayList<int[]>();
        burst.add(f0);
        burst.add(f1);
        burst.add(f2);

        SuperResolutionEngine.BurstResult res = SuperResolutionEngine.processBurst(
                burst, w, h, 2.0f, 20.0f, false);
        assertNotNull(res);
        assertEquals(w, res.width);
        assertEquals(h, res.height);
        assertEquals(w * h, res.pixels.length);
        double acutance = SuperResolutionEngine.computeSharpnessScore(res.pixels, w, h);
        assertTrue("Expected positive acutance from SR output, got " + acutance, acutance > 50.0);
        assertTrue(res.diagnostics.contains("SR DRIZZLE+LANCZOS3"));
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
