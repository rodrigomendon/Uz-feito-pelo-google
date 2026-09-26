package com.rodrigo.ultrazoom.diagnostic;

import java.util.List;

/**
 * Multi-Frame Computational Photography & Super-Resolution Engine (Drizzle + Lanczos-3 + Optical ISP).
 *
 * Agents Architecture:
 * - Lucky Imaging Reference Selector: picks the sharpest frame in the burst to defeat shutter-tap shake.
 * - Sub-Pixel Pyramid Registrator: aligns frames to 1/4-pixel precision using parabolic error refinement.
 * - Drizzle + Lanczos-3 Reconstructor: fuses sub-pixel shifted samples with photometric outlier rejection.
 * - Optical Post-ISP: YCbCr chroma denoising + multi-scale micro-contrast + anti-halo deconvolution sharpening.
 */
public final class SuperResolutionEngine {
    private static final int LUT_SCALE = 512;
    private static final int LUT_SIZE = 3 * LUT_SCALE + 2;
    private static final float[] LANCZOS3_LUT = new float[LUT_SIZE];

    static {
        LANCZOS3_LUT[0] = 1.0f;
        for (int i = 1; i < LUT_SIZE; i++) {
            double x = (double) i / LUT_SCALE;
            if (x >= 3.0) {
                LANCZOS3_LUT[i] = 0f;
            } else {
                double pix = Math.PI * x;
                double w = (Math.sin(pix) / pix) * (Math.sin(pix / 3.0) / (pix / 3.0));
                LANCZOS3_LUT[i] = (float) w;
            }
        }
    }

    public static final class SubpixelShift {
        public final float dx;
        public final float dy;
        public final float confidence;

        public SubpixelShift(float dx, float dy, float confidence) {
            this.dx = dx;
            this.dy = dy;
            this.confidence = confidence;
        }
    }

    public static final class BurstResult {
        public final int[] pixels;
        public final int width;
        public final int height;
        public final float lastDx;
        public final float lastDy;
        public final String diagnostics;

        public BurstResult(int[] pixels, int width, int height, float lastDx, float lastDy, String diagnostics) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
            this.lastDx = lastDx;
            this.lastDy = lastDy;
            this.diagnostics = diagnostics;
        }
    }

    public static BurstResult processBurst(
            List<int[]> frames,
            int srcW,
            int srcH,
            float srFactor,
            float totalZoom,
            boolean nightMode) {
        if (frames == null || frames.isEmpty() || srcW <= 0 || srcH <= 0) {
            return new BurstResult(new int[0], srcW, srcH, 0f, 0f, "SR FALHA");
        }
        int refIdx = selectSharpestReferenceIndex(frames, srcW, srcH);
        float lastDx = 0f;
        float lastDy = 0f;
        if (frames.size() > 1) {
            int otherIdx = (refIdx == frames.size() - 1) ? 0 : (frames.size() - 1);
            SubpixelShift sh = estimateSubpixelShift(frames.get(refIdx), frames.get(otherIdx), srcW, srcH);
            lastDx = sh.dx;
            lastDy = sh.dy;
        }
        float sharpen = totalZoom > 8f ? 0.34f : (totalZoom > 3f ? 0.26f : 0.18f);
        int[] outPx = processBurst(frames, srcW, srcH, srFactor, srcW, srcH, sharpen, nightMode);
        String diag = String.format(java.util.Locale.US,
                "SR DRIZZLE+LANCZOS3 (%dF, ref#%d) • SR %.2f× • Δ(%.2f,%.2f)",
                frames.size(), refIdx + 1, Math.max(1f, srFactor), lastDx, lastDy);
        return new BurstResult(outPx, srcW, srcH, lastDx, lastDy, diag);
    }

    public static float lanczos3(float x) {
        float ax = Math.abs(x);
        if (ax >= 3.0f) return 0f;
        int idx = (int) (ax * LUT_SCALE);
        if (idx >= LUT_SIZE - 1) return 0f;
        float frac = ax * LUT_SCALE - idx;
        return LANCZOS3_LUT[idx] + frac * (LANCZOS3_LUT[idx + 1] - LANCZOS3_LUT[idx]);
    }

    /**
     * Computes Laplacian + gradient acutance score on the central ROI of an ARGB image.
     */
    public static double computeSharpnessScore(int[] pixels, int w, int h) {
        if (pixels == null || w < 8 || h < 8 || pixels.length < w * h) return 0.0;
        int roiW = Math.min(256, (w * 3) / 4);
        int roiH = Math.min(256, (h * 3) / 4);
        int startX = Math.max(1, (w - roiW) / 2);
        int startY = Math.max(1, (h - roiH) / 2);
        int endX = Math.min(w - 2, startX + roiW);
        int endY = Math.min(h - 2, startY + roiH);
        int step = Math.max(1, Math.min(roiW, roiH) / 96);

        double sum = 0.0;
        double sum2 = 0.0;
        int n = 0;
        for (int y = startY; y < endY; y += step) {
            int row = y * w;
            for (int x = startX; x < endX; x += step) {
                int idx = row + x;
                int c = lum(pixels[idx]);
                int l = lum(pixels[idx - 1]);
                int r = lum(pixels[idx + 1]);
                int u = lum(pixels[idx - w]);
                int d = lum(pixels[idx + w]);
                double lap = 4.0 * c - l - r - u - d;
                sum += lap;
                sum2 += lap * lap;
                n++;
            }
        }
        if (n <= 1) return 0.0;
        double mean = sum / n;
        return Math.max(0.0, (sum2 / n) - mean * mean);
    }

    /**
     * Lucky Imaging: selects the index of the sharpest frame in the burst.
     */
    public static int selectSharpestReferenceIndex(List<int[]> frames, int w, int h) {
        if (frames == null || frames.isEmpty()) return 0;
        int bestIdx = 0;
        double bestScore = -1.0;
        for (int i = 0; i < frames.size(); i++) {
            int[] px = frames.get(i);
            if (px == null || px.length < w * h) continue;
            double score = computeSharpnessScore(px, w, h);
            if (score > bestScore) {
                bestScore = score;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    /**
     * Hierarchical coarse-to-fine sub-pixel shift estimator (0.25-pixel precision).
     */
    public static SubpixelShift estimateSubpixelShift(int[] ref, int[] other, int w, int h) {
        if (ref == null || other == null || w < 16 || h < 16 || ref.length < w * h || other.length < w * h) {
            return new SubpixelShift(0f, 0f, 0f);
        }

        // Extract central ROI luminance for fast, translation-accurate registration
        int roiW = Math.min(220, (w * 2) / 3);
        int roiH = Math.min(220, (h * 2) / 3);
        int ox = (w - roiW) / 2;
        int oy = (h - roiH) / 2;

        byte[] lumA = new byte[roiW * roiH];
        byte[] lumB = new byte[roiW * roiH];
        for (int y = 0; y < roiH; y++) {
            int srcRow = (oy + y) * w + ox;
            int dstRow = y * roiW;
            for (int x = 0; x < roiW; x++) {
                lumA[dstRow + x] = (byte) lum(ref[srcRow + x]);
                lumB[dstRow + x] = (byte) lum(other[srcRow + x]);
            }
        }

        int maxSearch = Math.min(12, Math.min(roiW, roiH) / 6);
        int margin = maxSearch + 2;
        int bestDx = 0;
        int bestDy = 0;
        double bestErr = Double.MAX_VALUE;

        // Level 1: Coarse search (step 2)
        for (int dy = -maxSearch; dy <= maxSearch; dy += 2) {
            for (int dx = -maxSearch; dx <= maxSearch; dx += 2) {
                double err = patchError(lumA, lumB, roiW, roiH, dx, dy, margin, 3);
                if (err < bestErr) {
                    bestErr = err;
                    bestDx = dx;
                    bestDy = dy;
                }
            }
        }

        // Level 2: 1-pixel refinement around coarse optimum
        int centerDx = bestDx;
        int centerDy = bestDy;
        bestErr = Double.MAX_VALUE;
        for (int dy = Math.max(-maxSearch, centerDy - 2); dy <= Math.min(maxSearch, centerDy + 2); dy++) {
            for (int dx = Math.max(-maxSearch, centerDx - 2); dx <= Math.min(maxSearch, centerDx + 2); dx++) {
                double err = patchError(lumA, lumB, roiW, roiH, dx, dy, margin, 2);
                if (err < bestErr) {
                    bestErr = err;
                    bestDx = dx;
                    bestDy = dy;
                }
            }
        }

        // Level 3: Parabolic sub-pixel interpolation along X and Y
        float subX = bestDx;
        float subY = bestDy;
        if (Math.abs(bestDx) < maxSearch) {
            double eLeft = patchError(lumA, lumB, roiW, roiH, bestDx - 1, bestDy, margin, 2);
            double eCenter = bestErr;
            double eRight = patchError(lumA, lumB, roiW, roiH, bestDx + 1, bestDy, margin, 2);
            double denom = 2.0 * (eLeft - 2.0 * eCenter + eRight);
            if (Math.abs(denom) > 1e-4) {
                double delta = (eLeft - eRight) / denom;
                if (Math.abs(delta) <= 0.65) {
                    subX += (float) delta;
                }
            }
        }
        if (Math.abs(bestDy) < maxSearch) {
            double eUp = patchError(lumA, lumB, roiW, roiH, bestDx, bestDy - 1, margin, 2);
            double eCenter = bestErr;
            double eDown = patchError(lumA, lumB, roiW, roiH, bestDx, bestDy + 1, margin, 2);
            double denom = 2.0 * (eUp - 2.0 * eCenter + eDown);
            if (Math.abs(denom) > 1e-4) {
                double delta = (eUp - eDown) / denom;
                if (Math.abs(delta) <= 0.65) {
                    subY += (float) delta;
                }
            }
        }

        float conf = (float) Math.max(0.0, Math.min(1.0, 1.0 - Math.sqrt(bestErr) / 64.0));
        return new SubpixelShift(subX, subY, conf);
    }

    private static double patchError(byte[] a, byte[] b, int w, int h, int dx, int dy, int margin, int step) {
        double sum = 0.0;
        int n = 0;
        for (int y = margin; y < h - margin; y += step) {
            int by = y + dy;
            if (by < 1 || by >= h - 1) continue;
            int rowA = y * w;
            int rowB = by * w;
            for (int x = margin; x < w - margin; x += step) {
                int bx = x + dx;
                if (bx < 1 || bx >= w - 1) continue;
                int dLum = (a[rowA + x] & 0xFF) - (b[rowB + bx] & 0xFF);
                int gxA = (a[rowA + x + 1] & 0xFF) - (a[rowA + x - 1] & 0xFF);
                int gxB = (b[rowB + bx + 1] & 0xFF) - (b[rowB + bx - 1] & 0xFF);
                int dGrad = gxA - gxB;
                sum += dLum * dLum + 0.35 * dGrad * dGrad;
                n++;
            }
        }
        return n == 0 ? Double.MAX_VALUE : (sum / n);
    }

    /**
     * Full multi-frame super-resolution, sub-pixel Drizzle fusion, YCbCr chroma denoising,
     * and edge-directed anti-halo deconvolution sharpening.
     */
    public static int[] processBurst(
            List<int[]> frames,
            int srcW,
            int srcH,
            float srFactor,
            int outW,
            int outH,
            float sharpenAmount,
            boolean nightMode) {
        if (frames == null || frames.isEmpty() || srcW <= 0 || srcH <= 0) return new int[0];
        float clampedSr = Math.max(1.0f, Math.min(4.0f, srFactor));
        int targetW = outW > 0 ? outW : srcW;
        int targetH = outH > 0 ? outH : srcH;

        int refIdx = selectSharpestReferenceIndex(frames, srcW, srcH);
        int[] ref = frames.get(refIdx);
        double refSharpness = Math.max(1.0, computeSharpnessScore(ref, srcW, srcH));

        int frameCount = frames.size();
        float[] shiftX = new float[frameCount];
        float[] shiftY = new float[frameCount];
        float[] frameWeight = new float[frameCount];

        for (int i = 0; i < frameCount; i++) {
            int[] frm = frames.get(i);
            if (frm == null || frm.length < srcW * srcH) {
                frameWeight[i] = 0f;
                continue;
            }
            if (i == refIdx) {
                shiftX[i] = 0f;
                shiftY[i] = 0f;
                frameWeight[i] = 1.25f;
            } else {
                double sh = computeSharpnessScore(frm, srcW, srcH);
                if (sh < refSharpness * 0.42) {
                    // Reject motion-blurred outlier frame
                    frameWeight[i] = 0f;
                    continue;
                }
                SubpixelShift s = estimateSubpixelShift(ref, frm, srcW, srcH);
                shiftX[i] = s.dx;
                shiftY[i] = s.dy;
                frameWeight[i] = s.confidence >= 0.25f ? (float) (s.confidence * Math.min(1.1, sh / refSharpness)) : 0f;
            }
        }

        int[] fused = new int[targetW * targetH];
        boolean superResolving = clampedSr > 1.01f || targetW != srcW || targetH != srcH;

        float roiW = srcW / clampedSr;
        float roiH = srcH / clampedSr;
        float startX = (srcW - roiW) * 0.5f;
        float startY = (srcH - roiH) * 0.5f;
        float stepX = roiW / Math.max(1, targetW);
        float stepY = roiH / Math.max(1, targetH);

        for (int y = 0; y < targetH; y++) {
            float srcY = superResolving ? (startY + (y + 0.5f) * stepY - 0.5f) : y;
            int outRow = y * targetW;
            for (int x = 0; x < targetW; x++) {
                float srcX = superResolving ? (startX + (x + 0.5f) * stepX - 0.5f) : x;

                // Base sample from sharpest reference frame (Lanczos-3 when super-resolving, direct when 1:1)
                int refColor = superResolving
                        ? sampleLanczos3(ref, srcW, srcH, srcX, srcY)
                        : ref[Math.min(srcH - 1, Math.max(0, y)) * srcW + Math.min(srcW - 1, Math.max(0, x))];

                int refR = (refColor >> 16) & 0xFF;
                int refG = (refColor >> 8) & 0xFF;
                int refB = refColor & 0xFF;
                int refLum = (refR * 77 + refG * 150 + refB * 29) >> 8;

                float sumR = refR * 1.30f;
                float sumG = refG * 1.30f;
                float sumB = refB * 1.30f;
                float totalW = 1.30f;

                // Sub-pixel Drizzle accumulation across aligned burst frames
                for (int f = 0; f < frameCount; f++) {
                    if (f == refIdx || frameWeight[f] <= 0.01f) continue;
                    float fx = srcX + shiftX[f];
                    float fy = srcY + shiftY[f];
                    if (fx < 1f || fx >= srcW - 2f || fy < 1f || fy >= srcH - 2f) continue;

                    int sample = superResolving && clampedSr >= 1.35f
                            ? sampleBicubicFast(frames.get(f), srcW, srcH, fx, fy)
                            : sampleBilinearRgb(frames.get(f), srcW, srcH, fx, fy);

                    int sr = (sample >> 16) & 0xFF;
                    int sg = (sample >> 8) & 0xFF;
                    int sb = sample & 0xFF;
                    int sLum = (sr * 77 + sg * 150 + sb * 29) >> 8;

                    // Photometric Huber/Tukey weight to prevent ghosting on moving details
                    int diff = Math.abs(sLum - refLum) + (Math.abs(sr - refR) + Math.abs(sb - refB)) / 4;
                    if (diff > 46) continue;
                    float photoW = diff <= 12 ? 1.0f : (46f - diff) / 34f;
                    float w = frameWeight[f] * photoW * photoW;

                    sumR += sr * w;
                    sumG += sg * w;
                    sumB += sb * w;
                    totalW += w;
                }

                int outR = clamp255(Math.round(sumR / totalW));
                int outG = clamp255(Math.round(sumG / totalW));
                int outB = clamp255(Math.round(sumB / totalW));
                fused[outRow + x] = 0xFF000000 | (outR << 16) | (outG << 8) | outB;
            }
        }

        // Optical ISP Post-Processing:
        // 1. Chroma-only YCbCr denoising (removes color blotches without softening luminance edges)
        denoiseChromaYCbCr(fused, targetW, targetH);

        // 2. Multi-scale micro-contrast + anti-halo edge deconvolution
        float effectiveSharpen = Math.max(0.18f, sharpenAmount + (clampedSr > 1.05f ? 0.28f * Math.min(2.0f, clampedSr - 1.0f) : 0.12f));
        enhanceAcutanceAndMicroContrast(fused, targetW, targetH, effectiveSharpen);

        // 3. Night tone curve if requested
        if (nightMode) {
            applyNightToneMapping(fused, targetW, targetH);
        }

        return fused;
    }

    /**
     * High-order Lanczos-3 (6x6 tap) spatial reconstruction for super-resolution.
     */
    public static int sampleLanczos3(int[] px, int w, int h, float x, float y) {
        int cx = (int) Math.floor(x);
        int cy = (int) Math.floor(y);
        if (cx < 3 || cx >= w - 4 || cy < 3 || cy >= h - 4) {
            return sampleBilinearRgb(px, w, h, x, y);
        }

        float[] wx = new float[6];
        float[] wy = new float[6];
        for (int i = 0; i < 6; i++) {
            wx[i] = lanczos3(x - (cx - 2 + i));
            wy[i] = lanczos3(y - (cy - 2 + i));
        }

        float sumR = 0f, sumG = 0f, sumB = 0f, sumW = 0f;
        int minR = 255, maxR = 0, minG = 255, maxG = 0, minB = 255, maxB = 0;

        for (int j = 0; j < 6; j++) {
            int yy = cy - 2 + j;
            int row = yy * w;
            float wyj = wy[j];
            for (int i = 0; i < 6; i++) {
                int xx = cx - 2 + i;
                float weight = wyj * wx[i];
                int c = px[row + xx];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                if (i >= 2 && i <= 3 && j >= 2 && j <= 3) {
                    if (r < minR) minR = r;
                    if (r > maxR) maxR = r;
                    if (g < minG) minG = g;
                    if (g > maxG) maxG = g;
                    if (b < minB) minB = b;
                    if (b > maxB) maxB = b;
                }
                sumR += r * weight;
                sumG += g * weight;
                sumB += b * weight;
                sumW += weight;
            }
        }

        if (Math.abs(sumW) < 1e-5f) return sampleBilinearRgb(px, w, h, x, y);
        // Anti-ringing clamp within 2x2 neighborhood bounds (+ small detail margin)
        int r = clamp(Math.round(sumR / sumW), Math.max(0, minR - 6), Math.min(255, maxR + 6));
        int g = clamp(Math.round(sumG / sumW), Math.max(0, minG - 6), Math.min(255, maxG + 6));
        int b = clamp(Math.round(sumB / sumW), Math.max(0, minB - 6), Math.min(255, maxB + 6));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * Fast Catmull-Rom 4x4 bicubic sampler for auxiliary aligned burst frames.
     */
    public static int sampleBicubicFast(int[] px, int w, int h, float x, float y) {
        int cx = (int) Math.floor(x);
        int cy = (int) Math.floor(y);
        if (cx < 1 || cx >= w - 3 || cy < 1 || cy >= h - 3) {
            return sampleBilinearRgb(px, w, h, x, y);
        }
        float fx = x - cx;
        float fy = y - cy;
        float[] wx = new float[]{
                cubicWeight(-1f - fx), cubicWeight(-fx), cubicWeight(1f - fx), cubicWeight(2f - fx)
        };
        float[] wy = new float[]{
                cubicWeight(-1f - fy), cubicWeight(-fy), cubicWeight(1f - fy), cubicWeight(2f - fy)
        };
        float r = 0f, g = 0f, b = 0f, wSum = 0f;
        for (int j = 0; j < 4; j++) {
            int row = (cy - 1 + j) * w;
            float wyj = wy[j];
            for (int i = 0; i < 4; i++) {
                float wt = wyj * wx[i];
                int c = px[row + cx - 1 + i];
                r += ((c >> 16) & 0xFF) * wt;
                g += ((c >> 8) & 0xFF) * wt;
                b += (c & 0xFF) * wt;
                wSum += wt;
            }
        }
        if (Math.abs(wSum) < 1e-5f) return sampleBilinearRgb(px, w, h, x, y);
        return 0xFF000000
                | (clamp255(Math.round(r / wSum)) << 16)
                | (clamp255(Math.round(g / wSum)) << 8)
                | clamp255(Math.round(b / wSum));
    }

    private static float cubicWeight(float t) {
        float at = Math.abs(t);
        if (at <= 1f) return 1.5f * at * at * at - 2.5f * at * at + 1f;
        if (at < 2f) return -0.5f * at * at * at + 2.5f * at * at - 4f * at + 2f;
        return 0f;
    }

    public static int sampleBilinearRgb(int[] px, int w, int h, float x, float y) {
        int x0 = clamp((int) Math.floor(x), 0, w - 1);
        int y0 = clamp((int) Math.floor(y), 0, h - 1);
        int x1 = Math.min(w - 1, x0 + 1);
        int y1 = Math.min(h - 1, y0 + 1);
        float fx = Math.max(0f, Math.min(1f, x - x0));
        float fy = Math.max(0f, Math.min(1f, y - y0));

        int c00 = px[y0 * w + x0];
        int c10 = px[y0 * w + x1];
        int c01 = px[y1 * w + x0];
        int c11 = px[y1 * w + x1];

        float w00 = (1f - fx) * (1f - fy);
        float w10 = fx * (1f - fy);
        float w01 = (1f - fx) * fy;
        float w11 = fx * fy;

        int r = clamp255(Math.round(((c00 >> 16) & 0xFF) * w00 + ((c10 >> 16) & 0xFF) * w10 + ((c01 >> 16) & 0xFF) * w01 + ((c11 >> 16) & 0xFF) * w11));
        int g = clamp255(Math.round(((c00 >> 8) & 0xFF) * w00 + ((c10 >> 8) & 0xFF) * w10 + ((c01 >> 8) & 0xFF) * w01 + ((c11 >> 8) & 0xFF) * w11));
        int b = clamp255(Math.round((c00 & 0xFF) * w00 + (c10 & 0xFF) * w10 + (c01 & 0xFF) * w01 + (c11 & 0xFF) * w11));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * Luminance-guided chroma denoising in YCbCr space: removes color noise without blurring luminance Y.
     */
    public static void denoiseChromaYCbCr(int[] px, int w, int h) {
        if (px == null || w < 4 || h < 4 || px.length < w * h) return;
        int[] prevRow = new int[w];
        int[] curRow = new int[w];
        int[] nextRow = new int[w];
        System.arraycopy(px, 0, curRow, 0, w);
        System.arraycopy(px, w, nextRow, 0, w);

        for (int y = 1; y < h - 1; y++) {
            int[] tmp = prevRow;
            prevRow = curRow;
            curRow = nextRow;
            nextRow = tmp;
            System.arraycopy(px, (y + 1) * w, nextRow, 0, w);
            int rowOffset = y * w;

            for (int x = 1; x < w - 1; x++) {
                int c = curRow[x];
                int r0 = (c >> 16) & 0xFF;
                int g0 = (c >> 8) & 0xFF;
                int b0 = c & 0xFF;
                int y0 = (77 * r0 + 150 * g0 + 29 * b0) >> 8;

                int sumCb = 0, sumCr = 0, sumW = 0;
                int[] neighbors = new int[]{
                        prevRow[x - 1], prevRow[x], prevRow[x + 1],
                        curRow[x - 1], c, curRow[x + 1],
                        nextRow[x - 1], nextRow[x], nextRow[x + 1]
                };
                for (int i = 0; i < 9; i++) {
                    int nc = neighbors[i];
                    int nr = (nc >> 16) & 0xFF;
                    int ng = (nc >> 8) & 0xFF;
                    int nb = nc & 0xFF;
                    int ny = (77 * nr + 150 * ng + 29 * nb) >> 8;
                    int dy = Math.abs(ny - y0);
                    int wt = (i == 4) ? 4 : (dy < 18 ? 2 : (dy < 36 ? 1 : 0));
                    if (wt > 0) {
                        int ncb = nb - ny;
                        int ncr = nr - ny;
                        sumCb += ncb * wt;
                        sumCr += ncr * wt;
                        sumW += wt;
                    }
                }
                if (sumW > 0) {
                    int cb = sumCb / sumW;
                    int cr = sumCr / sumW;
                    int outR = clamp255(y0 + cr);
                    int outB = clamp255(y0 + cb);
                    int outG = clamp255((y0 * 256 - 77 * outR - 29 * outB) / 150);
                    px[rowOffset + x] = 0xFF000000 | (outR << 16) | (outG << 8) | outB;
                }
            }
        }
    }

    /**
     * Multi-scale edge-directed unsharp masking + local micro-contrast with anti-halo clamping.
     */
    public static void enhanceAcutanceAndMicroContrast(int[] px, int w, int h, float strength) {
        if (px == null || w < 5 || h < 5 || strength <= 0f || px.length < w * h) return;
        float fineGain = Math.min(1.45f, strength * 1.65f);
        float microContrastGain = Math.min(0.45f, strength * 0.55f);

        int[] r0 = new int[w];
        int[] r1 = new int[w];
        int[] r2 = new int[w];
        int[] r3 = new int[w];
        int[] r4 = new int[w];

        System.arraycopy(px, 0, r1, 0, w);
        System.arraycopy(px, w, r2, 0, w);
        System.arraycopy(px, 2 * w, r3, 0, w);
        System.arraycopy(px, 3 * w, r4, 0, w);

        for (int y = 2; y < h - 2; y++) {
            int[] tmp = r0;
            r0 = r1;
            r1 = r2;
            r2 = r3;
            r3 = r4;
            r4 = tmp;
            System.arraycopy(px, (y + 2) * w, r4, 0, w);
            int rowOffset = y * w;

            for (int x = 2; x < w - 2; x++) {
                int c = r2[x];
                int lumC = lum(c);

                int lumU = lum(r1[x]);
                int lumD = lum(r3[x]);
                int lumL = lum(r2[x - 1]);
                int lumR = lum(r2[x + 1]);
                int lumUL = lum(r1[x - 1]);
                int lumUR = lum(r1[x + 1]);
                int lumDL = lum(r3[x - 1]);
                int lumDR = lum(r3[x + 1]);

                // 3x3 Gaussian blur for fine acutance detail
                int blur3 = (4 * lumC + 2 * (lumU + lumD + lumL + lumR) + (lumUL + lumUR + lumDL + lumDR) + 8) >> 4;
                int fineDetail = lumC - blur3;

                // 5x5 outer ring for medium local micro-contrast (clarity)
                int outer = (lum(r0[x]) + lum(r4[x]) + lum(r2[x - 2]) + lum(r2[x + 2])
                        + lum(r0[x - 2]) + lum(r0[x + 2]) + lum(r4[x - 2]) + lum(r4[x + 2]) + 4) >> 3;
                int midDetail = blur3 - outer;

                // Soft noise gate so flat regions are never noisy
                int absFine = Math.abs(fineDetail);
                if (absFine <= 2 && Math.abs(midDetail) <= 2) continue;

                float edgeFactor = absFine <= 2 ? 0f : Math.min(1.0f, (absFine - 2f) / 10f);
                float deltaLum = fineDetail * fineGain * edgeFactor + midDetail * microContrastGain;

                // Anti-halo overshoot clamp around local 3x3 extrema
                int min3 = Math.min(lumC, Math.min(Math.min(lumU, lumD), Math.min(lumL, lumR)));
                int max3 = Math.max(lumC, Math.max(Math.max(lumU, lumD), Math.max(lumL, lumR)));
                int haloMargin = Math.max(6, (max3 - min3) / 4);
                int newLum = clamp(Math.round(lumC + deltaLum), Math.max(0, min3 - haloMargin), Math.min(255, max3 + haloMargin));
                int appliedDelta = newLum - lumC;

                int rr = clamp255(((c >> 16) & 0xFF) + appliedDelta);
                int gg = clamp255(((c >> 8) & 0xFF) + appliedDelta);
                int bb = clamp255((c & 0xFF) + appliedDelta);
                px[rowOffset + x] = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
            }
        }
    }

    public static void applyNightToneMapping(int[] px, int w, int h) {
        if (px == null) return;
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int r = nightCurve((c >> 16) & 0xFF);
            int g = nightCurve((c >> 8) & 0xFF);
            int b = nightCurve(c & 0xFF);
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    private static int nightCurve(int v) {
        double n = v / 255.0;
        double lifted = Math.pow(Math.min(1.0, n * 1.26), 0.78);
        return clamp255((int) Math.round(lifted * 255.0));
    }

    private static int lum(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (77 * r + 150 * g + 29 * b) >> 8;
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private SuperResolutionEngine() {}
}
