package com.rodrigo.ultrazoom.diagnostic;

/**
 * Conservative 2D scale estimator for small grayscale preview/JPEG thumbnails.
 * Compensates for natural handheld translation (camera shake/pan between zoom changes),
 * while rejecting featureless scenes, predominantly 1D stripe/edge patterns, and ambiguous correlations.
 */
public final class ScaleEstimator {
    public static final class Result {
        public final float scale;
        public final float confidence;
        public Result(float s, float c) {
            scale = s;
            confidence = c;
        }
    }

    private static final class StructureStats {
        int edgeCount;
        int corner2dCount;
        int activeQuadrants;
        double sumGx;
        double sumGy;

        boolean isValid2dStructure() {
            if (edgeCount < 16 || corner2dCount < 5 || activeQuadrants < 3) return false;
            double maxAxis = Math.max(sumGx, sumGy);
            double minAxis = Math.min(sumGx, sumGy);
            if (maxAxis < 350.0) return false;
            // Reject predominantly 1D patterns (horizontal blinds, vertical bars, single straight line)
            return (minAxis / maxAxis) >= 0.18;
        }
    }

    public static Result estimate(byte[] a, int aw, int ah, byte[] b, int bw, int bh, float minScale, float maxScale) {
        if (a == null || b == null || aw < 16 || ah < 16 || bw < 16 || bh < 16) {
            return new Result(Float.NaN, 0f);
        }
        if (a.length < aw * ah || b.length < bw * bh || minScale < 0.95f || maxScale < minScale) {
            return new Result(Float.NaN, 0f);
        }

        StructureStats statsA = analyzeStructure(a, aw, ah);
        StructureStats statsB = analyzeStructure(b, bw, bh);
        if (!statsA.isValid2dStructure() || !statsB.isValid2dStructure()) {
            return new Result(Float.NaN, 0f);
        }

        int steps = Math.max(1, Math.round((maxScale - minScale) / 0.05f) + 1);
        float[] scales = new float[steps];
        float[] scores = new float[steps];
        int count = 0;

        float best = Float.NaN;
        float bestScore = -1f;
        for (float s = minScale; s <= maxScale + 0.001f && count < steps; s += 0.05f) {
            float score = similarityWithHandheldShift(a, aw, ah, b, bw, bh, s);
            scales[count] = s;
            scores[count] = score;
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
            count++;
        }

        if (Float.isNaN(best) || bestScore < 0.64f) {
            return new Result(Float.NaN, 0f);
        }

        // Compare peak against non-neighboring scales (|s - best| >= 0.14f) so broad/flat plateaus are detected
        float secondNonNeighbor = -1f;
        boolean hadNonNeighbor = false;
        for (int i = 0; i < count; i++) {
            if (Math.abs(scales[i] - best) >= 0.14f) {
                hadNonNeighbor = true;
                if (scores[i] > secondNonNeighbor) {
                    secondNonNeighbor = scores[i];
                }
            }
        }

        float margin = hadNonNeighbor ? (bestScore - Math.max(0f, secondNonNeighbor)) : (bestScore - 0.64f);
        if (hadNonNeighbor && margin < 0.025f) {
            // Flat / ambiguous correlation curve: do not allow confirmation
            float weakConf = Math.max(0f, Math.min(0.35f, (bestScore - 0.64f) * 0.8f));
            return new Result(best, weakConf);
        }

        float conf = Math.max(0f, Math.min(1f, (bestScore - 0.58f) * 1.9f + margin * 3.2f));
        return new Result(best, conf);
    }

    /**
     * Evaluates similarity at candidate `scale` while allowing small 2D translations (ox, oy)
     * caused by natural hand movement when operating the zoom controls on a physical phone.
     */
    private static float similarityWithHandheldShift(byte[] a, int aw, int ah, byte[] b, int bw, int bh, float scale) {
        float zeroShiftScore = similarityAtOffset(a, aw, ah, b, bw, bh, scale, 0f, 0f, 2);
        float bestScore = zeroShiftScore;
        float bestOx = 0f, bestOy = 0f;

        // Coarse search (±6 px in steps of 2 px in `a` space) if zero-shift is not already near-perfect
        if (bestScore < 0.88f) {
            for (int oy = -6; oy <= 6; oy += 2) {
                for (int ox = -6; ox <= 6; ox += 2) {
                    if (ox == 0 && oy == 0) continue;
                    float sc = similarityAtOffset(a, aw, ah, b, bw, bh, scale, ox, oy, 3);
                    if (sc > bestScore) {
                        bestScore = sc;
                        bestOx = ox;
                        bestOy = oy;
                    }
                }
            }
            // 1px refinement around best coarse shift
            float centerOx = bestOx, centerOy = bestOy;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    float ox = centerOx + dx;
                    float oy = centerOy + dy;
                    float sc = similarityAtOffset(a, aw, ah, b, bw, bh, scale, ox, oy, 2);
                    if (sc > bestScore) {
                        bestScore = sc;
                    }
                }
            }
        }
        return bestScore;
    }

    private static float similarityAtOffset(
            byte[] a, int aw, int ah, byte[] b, int bw, int bh,
            float scale, float ox, float oy, int step) {
        if (scale <= 0.1f) return 0f;
        int halfW = Math.min(48, Math.min(aw, bw) / 2 - 4);
        int halfH = Math.min(36, Math.min(ah, bh) / 2 - 4);
        if (halfW < 6 || halfH < 6) return 0f;

        float acx = aw * 0.5f + ox;
        float acy = ah * 0.5f + oy;
        float bcx = bw * 0.5f;
        float bcy = bh * 0.5f;

        int n = 0;
        double sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0;
        double gDot = 0, gNormA = 0, gNormB = 0;

        for (int dy = -halfH; dy <= halfH; dy += step) {
            int by = Math.round(bcy + dy);
            float ay = acy + (dy / scale);
            if (by < 2 || by >= bh - 2 || ay < 2f || ay >= ah - 3f) continue;

            for (int dx = -halfW; dx <= halfW; dx += step) {
                int bx = Math.round(bcx + dx);
                float ax = acx + (dx / scale);
                if (bx < 2 || bx >= bw - 2 || ax < 2f || ax >= aw - 3f) continue;

                // Signed gradients in a (scaled by 1/scale in step) and b
                float gxA = sampleBilinear(a, aw, ah, ax + 1f / scale, ay) - sampleBilinear(a, aw, ah, ax - 1f / scale, ay);
                float gyA = sampleBilinear(a, aw, ah, ax, ay + 1f / scale) - sampleBilinear(a, aw, ah, ax, ay - 1f / scale);
                int bIdx = by * bw + bx;
                float gxB = (b[bIdx + 1] & 0xFF) - (b[bIdx - 1] & 0xFF);
                float gyB = (b[bIdx + bw] & 0xFF) - (b[bIdx - bw] & 0xFF);

                float magA = (float) Math.hypot(gxA, gyA);
                float magB = (float) Math.hypot(gxB, gyB);

                sa += magA;
                sb += magB;
                saa += magA * magA;
                sbb += magB * magB;
                sab += magA * magB;

                gDot += gxA * gxB + gyA * gyB;
                gNormA += gxA * gxA + gyA * gyA;
                gNormB += gxB * gxB + gyB * gyB;
                n++;
            }
        }

        if (n < 20) return 0f;
        double da = n * saa - sa * sa;
        double db = n * sbb - sb * sb;
        if (da <= 1e-5 || db <= 1e-5 || gNormA <= 1e-5 || gNormB <= 1e-5) return 0f;

        double magCorr = (n * sab - sa * sb) / Math.sqrt(da * db);
        double vecCorr = gDot / Math.sqrt(gNormA * gNormB);
        if (vecCorr <= 0.05 || magCorr <= 0.05) return 0f;

        double combined = 0.55 * vecCorr + 0.45 * magCorr;
        return (float) Math.max(0.0, Math.min(1.0, combined * 0.5 + 0.5));
    }

    private static float sampleBilinear(byte[] p, int w, int h, float x, float y) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = Math.min(w - 1, x0 + 1);
        int y1 = Math.min(h - 1, y0 + 1);
        x0 = Math.max(0, Math.min(w - 1, x0));
        y0 = Math.max(0, Math.min(h - 1, y0));
        float fx = x - x0;
        float fy = y - y0;
        float v00 = p[y0 * w + x0] & 0xFF;
        float v10 = p[y0 * w + x1] & 0xFF;
        float v01 = p[y1 * w + x0] & 0xFF;
        float v11 = p[y1 * w + x1] & 0xFF;
        float top = v00 + fx * (v10 - v00);
        float bot = v01 + fx * (v11 - v01);
        return top + fy * (bot - top);
    }

    private static StructureStats analyzeStructure(byte[] p, int w, int h) {
        StructureStats s = new StructureStats();
        int qMask = 0;
        int midX = w / 2;
        int midY = h / 2;
        int marginX = Math.max(1, w / 8);
        int marginY = Math.max(1, h / 8);

        for (int y = marginY; y < h - marginY; y += 2) {
            for (int x = marginX; x < w - marginX; x += 2) {
                int i = y * w + x;
                int gx = Math.abs((p[i + 1] & 0xFF) - (p[i - 1] & 0xFF));
                int gy = Math.abs((p[i + w] & 0xFF) - (p[i - w] & 0xFF));
                s.sumGx += gx;
                s.sumGy += gy;
                if (gx + gy > 32) {
                    s.edgeCount++;
                    int q = (x >= midX ? 1 : 0) + (y >= midY ? 2 : 0);
                    qMask |= (1 << q);
                }
                if (gx > 11 && gy > 11) {
                    s.corner2dCount++;
                }
            }
        }
        s.activeQuadrants = Integer.bitCount(qMask);
        return s;
    }

    private ScaleEstimator() {}
}
