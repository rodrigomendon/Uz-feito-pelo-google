package com.rodrigo.ultrazoom;

import com.rodrigo.ultrazoom.diagnostic.GeometryMath;
import org.junit.Test;
import static org.junit.Assert.*;

public class GeometryMathTest {
    @Test
    public void fourCornersPreserveAspect() {
        GeometryMath.Point a = GeometryMath.mapUniform(0, 0, 4, 3, 0, 0, 400, 300);
        GeometryMath.Point b = GeometryMath.mapUniform(4, 0, 4, 3, 0, 0, 400, 300);
        GeometryMath.Point c = GeometryMath.mapUniform(0, 3, 4, 3, 0, 0, 400, 300);
        GeometryMath.Point d = GeometryMath.mapUniform(4, 3, 4, 3, 0, 0, 400, 300);
        assertEquals(0, a.x, .001); assertEquals(0, a.y, .001);
        assertEquals(400, b.x, .001); assertEquals(0, b.y, .001);
        assertEquals(0, c.x, .001); assertEquals(300, c.y, .001);
        assertEquals(400, d.x, .001); assertEquals(300, d.y, .001);
        assertEquals(400f / 300f, GeometryMath.aspect(4, 3), .001);
    }

    @Test
    public void compute4x3ViewportDoesNotStretchOnTallScreen() {
        // 1080x2400 (9:20 phone display) -> portrait 3:4 frame must be 1080x1440 centered at y=480
        GeometryMath.Viewport vp = GeometryMath.compute4x3Viewport(1080f, 2400f);
        assertEquals(0f, vp.left, 0.01f);
        assertEquals(480f, vp.top, 0.01f);
        assertEquals(1080f, vp.width, 0.01f);
        assertEquals(1440f, vp.height, 0.01f);
        assertEquals(3f / 4f, GeometryMath.aspect(vp.width, vp.height), 0.001f);
    }

    @Test
    public void mapTouchToSensorNormalizedRespects4x3ViewportAndRotation() {
        // Center of 1080x2400 screen is (540, 1200), which is also center of 4:3 viewport -> (0.5, 0.5)
        GeometryMath.Point center = GeometryMath.mapTouchToSensorNormalized(540f, 1200f, 1080f, 2400f, 90);
        assertEquals(0.5f, center.x, 0.001f);
        assertEquals(0.5f, center.y, 0.001f);

        // Top-left corner of the 4:3 viewport on a 1080x2400 screen is (0, 480) -> nx=0, ny=0
        // With 90 deg relative sensor rotation: sx = ny = 0, sy = 1 - nx = 1
        GeometryMath.Point topLeft = GeometryMath.mapTouchToSensorNormalized(0f, 480f, 1080f, 2400f, 90);
        assertEquals(0f, topLeft.x, 0.001f);
        assertEquals(1f, topLeft.y, 0.001f);
    }
}
