package com.rodrigo.ultrazoom;

import com.rodrigo.ultrazoom.diagnostic.ScaleEstimator;
import org.junit.Test;
import static org.junit.Assert.*;

public class ScaleEstimatorTest {
    @Test
    public void rejectsFeaturelessScene() {
        byte[] a = new byte[160 * 120], b = new byte[160 * 120];
        ScaleEstimator.Result r = ScaleEstimator.estimate(a, 160, 120, b, 160, 120, 1f, 3f);
        assertEquals(0f, r.confidence, .001f);
        assertTrue(Float.isNaN(r.scale));
    }

    @Test
    public void rejectsPredominantly1dPattern() {
        int w = 160, h = 120;
        byte[] a = new byte[w * h];
        byte[] b = new byte[w * h];
        // Pure vertical stripes (1D pattern with dx != 0, dy == 0)
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                a[y * w + x] = (byte) (((x / 4) % 2 == 0) ? 220 : 30);
                b[y * w + x] = (byte) (((x / 8) % 2 == 0) ? 220 : 30);
            }
        }
        ScaleEstimator.Result r = ScaleEstimator.estimate(a, w, h, b, w, h, 1f, 3f);
        assertEquals(0f, r.confidence, .001f);
        assertTrue(Float.isNaN(r.scale));
    }

    @Test
    public void estimatesKnown2dScaleAccurately() {
        int w = 160, h = 120;
        byte[] a = new byte[w * h];
        byte[] b = new byte[w * h];
        float trueScale = 2.0f;
        float cx = w * 0.5f, cy = h * 0.5f;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = x - cx;
                float dy = y - cy;
                a[y * w + x] = (byte) syntheticPattern(dx, dy);
                b[y * w + x] = (byte) syntheticPattern(dx / trueScale, dy / trueScale);
            }
        }

        ScaleEstimator.Result r = ScaleEstimator.estimate(a, w, h, b, w, h, 1.2f, 2.8f);
        assertFalse(Float.isNaN(r.scale));
        assertEquals(trueScale, r.scale, 0.15f);
        assertTrue("Expected high confidence for rich 2D scaled scene, got " + r.confidence, r.confidence >= 0.72f);
    }

    @Test
    public void recoversScaleUnderHandheldCameraShakeTranslation() {
        int w = 160, h = 120;
        byte[] a = new byte[w * h];
        byte[] b = new byte[w * h];
        float trueScale = 2.0f;
        float shakeDx = 5.0f;
        float shakeDy = -4.0f;
        float cx = w * 0.5f, cy = h * 0.5f;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = x - cx;
                float dy = y - cy;
                a[y * w + x] = (byte) syntheticPattern(dx, dy);
                b[y * w + x] = (byte) syntheticPattern(dx / trueScale + shakeDx, dy / trueScale + shakeDy);
            }
        }

        ScaleEstimator.Result r = ScaleEstimator.estimate(a, w, h, b, w, h, 1.2f, 2.8f);
        assertFalse("Should recover scale even with handheld camera shake", Float.isNaN(r.scale));
        assertEquals(trueScale, r.scale, 0.22f);
        assertTrue("Expected high confidence under handheld shake, got " + r.confidence, r.confidence >= 0.52f);
    }

    private static int syntheticPattern(float x, float y) {
        double v = 128.0
                + 55.0 * Math.sin(x * 0.23) * Math.cos(y * 0.27)
                + 40.0 * Math.cos((x + y) * 0.17)
                + ((Math.abs(x) < 18 && Math.abs(y) < 14) ? 35.0 : -20.0);
        return Math.max(0, Math.min(255, (int) Math.round(v)));
    }
}
