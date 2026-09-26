package com.rodrigo.ultrazoom.diagnostic;

public final class GeometryMath {
    public static final class Point {
        public final float x, y;
        public Point(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    public static final class Viewport {
        public final float left, top, width, height;
        public Viewport(float left, float top, float width, float height) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }
    }

    public static Point mapUniform(float x, float y, float srcW, float srcH, float dstLeft, float dstTop, float dstW, float dstH) {
        float safeSrcW = Math.max(1e-4f, srcW);
        float safeSrcH = Math.max(1e-4f, srcH);
        float scale = Math.min(dstW / safeSrcW, dstH / safeSrcH);
        float ox = dstLeft + (dstW - safeSrcW * scale) / 2f;
        float oy = dstTop + (dstH - safeSrcH * scale) / 2f;
        return new Point(ox + x * scale, oy + y * scale);
    }

    public static float aspect(float w, float h) {
        return w / Math.max(1f, h);
    }

    /**
     * Computes the centered, non-distorted 4:3 camera viewport (3:4 in portrait, 4:3 in landscape)
     * inside an arbitrary display container without stretching to fill a tall/wide screen.
     */
    public static Viewport compute4x3Viewport(float viewW, float viewH) {
        float safeW = Math.max(1f, viewW);
        float safeH = Math.max(1f, viewH);
        boolean portrait = safeH >= safeW;
        float frameAspect = portrait ? (3f / 4f) : (4f / 3f);
        float frameW, frameH;
        if (portrait) {
            frameW = safeW;
            frameH = frameW / frameAspect;
            if (frameH > safeH) {
                frameH = safeH;
                frameW = frameH * frameAspect;
            }
        } else {
            frameH = safeH;
            frameW = frameH * frameAspect;
            if (frameW > safeW) {
                frameW = safeW;
                frameH = frameW / frameAspect;
            }
        }
        float left = (safeW - frameW) / 2f;
        float top = (safeH - frameH) / 2f;
        return new Viewport(left, top, frameW, frameH);
    }

    /**
     * Converts a touch coordinate on the view into normalized [0..1] sensor coordinates,
     * accounting for the letterboxed 4:3 viewport and relative sensor rotation.
     */
    public static Point mapTouchToSensorNormalized(float touchX, float touchY, float viewW, float viewH, int relativeRotationDegrees) {
        Viewport vp = compute4x3Viewport(viewW, viewH);
        float nx = Math.max(0f, Math.min(1f, (touchX - vp.left) / Math.max(1f, vp.width)));
        float ny = Math.max(0f, Math.min(1f, (touchY - vp.top) / Math.max(1f, vp.height)));
        int rel = ((relativeRotationDegrees % 360) + 360) % 360;
        float sx, sy;
        if (rel == 90) {
            sx = ny;
            sy = 1f - nx;
        } else if (rel == 180) {
            sx = 1f - nx;
            sy = 1f - ny;
        } else if (rel == 270) {
            sx = 1f - ny;
            sy = nx;
        } else {
            sx = nx;
            sy = ny;
        }
        return new Point(Math.max(0f, Math.min(1f, sx)), Math.max(0f, Math.min(1f, sy)));
    }

    private GeometryMath() {}
}
