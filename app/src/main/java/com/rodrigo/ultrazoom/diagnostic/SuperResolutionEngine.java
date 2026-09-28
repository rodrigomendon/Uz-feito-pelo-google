package com.rodrigo.ultrazoom.diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Full Computational Photography & Multi-Frame Super-Resolution Engine.
 *
 * Implements Sections 3, 4, 5, 6, 7, 11, 12, 15, 16, 17, 18, 19, 20, 21, 24, 28, 29, 39:
 * 1. FrameQualityAnalyzer: 0-100 scoring, outlier rejection, and 4x3 local sharpness weighting `peso(frame, x, y)`.
 * 2. LocalRegistration: Global sub-pixel shift + 4x3 Local Optical Flow (`MotionField4x3`) + dynamic object masking.
 * 3. Sub-Pixel Phase Diversity Verification (`subpixelCoverage`): distinguishes true complementary sub-pixel
 *    Super-Resolution (Case C) from redundant frame averaging (Case B) or single-frame resize (Case A).
 * 4. Phase-Weighted Drizzle + Lanczos-3 Reconstruction: weights each frame at target coordinate (x, y) by
 *    its sub-pixel phase proximity to native integer sensor samples, unfolding aliased high frequencies.
 * 5. Multi-Core Tile Parallelism: processes horizontal tiles across CPU cores with zero seam boundary error.
 * 6. YCbCr Luminance/Chrominance Separation: aggressive bilateral chroma denoise + luminance edge preservation.
 * 7. DeconvolutionEngine: SNR-gated Richardson-Lucy PSF deconvolution + Region-Aware Adaptive Sharpening +
 *    Hallucination / Ringing / Ghosting Artifact Rollback.
 * 8. QualityMetrics & PerformanceProfiler: objective comparison against single-frame baseline.
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
        public final int usedFrames;
        public final int rejectedFrames;
        public final float subpixelCoverage;
        public final boolean lowSrBenefit;
        public final int dynamicBlocksDetected;
        public final DeconvolutionEngine.ArtifactReport artifactReport;
        public final QualityMetrics.ComparisonReport qualityComparison;
        public final PerformanceProfiler.ProfileReport profileReport;
        public final String diagnostics;
        public final String scientificTelemetry;

        public BurstResult(
                int[] pixels,
                int width,
                int height,
                float lastDx,
                float lastDy,
                int usedFrames,
                int rejectedFrames,
                float subpixelCoverage,
                boolean lowSrBenefit,
                int dynamicBlocksDetected,
                DeconvolutionEngine.ArtifactReport artifactReport,
                QualityMetrics.ComparisonReport qualityComparison,
                PerformanceProfiler.ProfileReport profileReport,
                String diagnostics,
                String scientificTelemetry) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
            this.lastDx = lastDx;
            this.lastDy = lastDy;
            this.usedFrames = usedFrames;
            this.rejectedFrames = rejectedFrames;
            this.subpixelCoverage = subpixelCoverage;
            this.lowSrBenefit = lowSrBenefit;
            this.dynamicBlocksDetected = dynamicBlocksDetected;
            this.artifactReport = artifactReport;
            this.qualityComparison = qualityComparison;
            this.profileReport = profileReport;
            this.diagnostics = diagnostics;
            this.scientificTelemetry = scientificTelemetry;
        }
    }

    public static BurstResult processBurst(
            List<int[]> frames,
            int srcW,
            int srcH,
            float srFactor,
            float totalZoom,
            boolean nightMode) {
        return processBurstFull(frames, srcW, srcH, srFactor, srcW, srcH, totalZoom, nightMode, false);
    }

    public static BurstResult processBurstFull(
            final List<int[]> frames,
            final int srcW,
            final int srcH,
            final float srFactor,
            final int outW,
            final int outH,
            final float totalZoom,
            final boolean nightMode,
            final boolean hdrMode) {

        long tStart = System.currentTimeMillis();
        if (frames == null || frames.isEmpty() || srcW <= 0 || srcH <= 0) {
            return new BurstResult(
                    new int[0], srcW, srcH, 0f, 0f, 0, 0, 0f, true, 0,
                    null, null, null, "SR FALHA", "NENHUM FRAME");
        }

        final float clampedSr = Math.max(1.0f, Math.min(4.0f, srFactor));
        final int targetW = outW > 0 ? outW : srcW;
        final int targetH = outH > 0 ? outH : srcH;

        // STAGE 1: Frame Quality Analysis (0..100 score, outlier rejection, 4x3 regional sharpness map)
        long t0 = System.currentTimeMillis();
        final FrameQualityAnalyzer.BurstQualityReport qualityReport =
                FrameQualityAnalyzer.analyzeBurst(frames, srcW, srcH);
        final int refIdx = qualityReport.referenceIndex;
        final int[] ref = frames.get(refIdx);
        long tQualityMs = System.currentTimeMillis() - t0;

        // STAGE 2: Global + Local 4x3 Block Optical Flow Registration & Dynamic Scene Detection
        t0 = System.currentTimeMillis();
        final int frameCount = frames.size();
        final List<LocalRegistration.MotionField4x3> motionFields =
                new ArrayList<LocalRegistration.MotionField4x3>(frameCount);
        float lastDx = 0f;
        float lastDy = 0f;
        int totalDynamicBlocks = 0;

        for (int i = 0; i < frameCount; i++) {
            if (i == refIdx || qualityReport.scores[i].rejectedOutlier) {
                motionFields.add(LocalRegistration.MotionField4x3.identity());
            } else {
                LocalRegistration.MotionField4x3 mf =
                        LocalRegistration.estimateMotionField(ref, frames.get(i), srcW, srcH);
                motionFields.add(mf);
                lastDx = mf.globalDx;
                lastDy = mf.globalDy;
                totalDynamicBlocks += mf.dynamicBlocksCount;
            }
        }

        final float subpixelCoverage = LocalRegistration.computeSubpixelPhaseCoverage(motionFields, refIdx);
        final boolean superResolving = clampedSr > 1.01f || targetW != srcW || targetH != srcH;
        final boolean lowSrBenefit = superResolving && (qualityReport.usedFramesCount < 2 || subpixelCoverage < 0.18f);
        long tRegMs = System.currentTimeMillis() - t0;

        // STAGE 3: Phase-Weighted Sub-Pixel Drizzle + Lanczos-3 Reconstruction (Tile-Parallelized)
        t0 = System.currentTimeMillis();
        final int[] fused = new int[targetW * targetH];
        final int[] singleFrameBaseline = new int[targetW * targetH];

        final float roiW = srcW / clampedSr;
        final float roiH = srcH / clampedSr;
        final float startX = (srcW - roiW) * 0.5f;
        final float startY = (srcH - roiH) * 0.5f;
        final float stepX = roiW / Math.max(1, targetW);
        final float stepY = roiH / Math.max(1, targetH);

        int availCores = Math.max(1, Runtime.getRuntime().availableProcessors());
        final int numTiles = (targetW * targetH >= 160 * 120) ? Math.min(6, availCores) : 1;

        if (numTiles <= 1) {
            reconstructTileBand(
                    0, targetH, targetW, targetH, srcW, srcH,
                    startX, startY, stepX, stepY, clampedSr, superResolving, hdrMode,
                    frames, ref, refIdx, qualityReport, motionFields,
                    fused, singleFrameBaseline);
        } else {
            ExecutorService pool = Executors.newFixedThreadPool(numTiles);
            try {
                List<Future<Void>> futures = new ArrayList<Future<Void>>(numTiles);
                final int rowsPerTile = (targetH + numTiles - 1) / numTiles;
                for (int t = 0; t < numTiles; t++) {
                    final int yStart = t * rowsPerTile;
                    final int yEnd = Math.min(targetH, yStart + rowsPerTile);
                    if (yStart >= yEnd) continue;
                    futures.add(pool.submit(new Callable<Void>() {
                        @Override
                        public Void call() {
                            reconstructTileBand(
                                    yStart, yEnd, targetW, targetH, srcW, srcH,
                                    startX, startY, stepX, stepY, clampedSr, superResolving, hdrMode,
                                    frames, ref, refIdx, qualityReport, motionFields,
                                    fused, singleFrameBaseline);
                            return null;
                        }
                    }));
                }
                for (Future<Void> f : futures) {
                    f.get();
                }
            } catch (Exception e) {
                reconstructTileBand(
                        0, targetH, targetW, targetH, srcW, srcH,
                        startX, startY, stepX, stepY, clampedSr, superResolving, hdrMode,
                        frames, ref, refIdx, qualityReport, motionFields,
                        fused, singleFrameBaseline);
            } finally {
                pool.shutdown();
            }
        }
        long tSrMs = System.currentTimeMillis() - t0;

        // STAGE 4: YCbCr Chroma Denoising (preserves luminance detail while cleaning chroma blotches)
        t0 = System.currentTimeMillis();
        denoiseChromaYCbCr(fused, targetW, targetH);
        long tDenoiseMs = System.currentTimeMillis() - t0;

        // STAGE 5: PSF Deconvolution + Region-Aware Adaptive Sharpening + Hallucination Control
        t0 = System.currentTimeMillis();
        float baseSharpen = totalZoom > 8f ? 0.34f : (totalZoom > 3f ? 0.25f : 0.18f);
        if (lowSrBenefit) {
            // Section 39: When frames lack complementary sub-pixel phases, fall back to conservative sharpening
            baseSharpen *= 0.65f;
        }
        DeconvolutionEngine.ArtifactReport artifactReport = DeconvolutionEngine.processWithArtifactControl(
                fused,
                singleFrameBaseline,
                targetW,
                targetH,
                baseSharpen,
                subpixelCoverage);

        // STAGE 6: Night / Low-Light Tone Mapping if active
        if (nightMode) {
            applyNightToneMapping(fused, targetW, targetH);
        }
        long tDeconvMs = System.currentTimeMillis() - t0;
        long totalMs = System.currentTimeMillis() - tStart;

        // STAGE 7: Objective Quality Evaluation & Performance Telemetry
        QualityMetrics.ComparisonReport comparison =
                QualityMetrics.compare(singleFrameBaseline, fused, targetW, targetH);
        PerformanceProfiler.ProfileReport profile = new PerformanceProfiler.ProfileReport(
                tQualityMs,
                tRegMs,
                tSrMs,
                tDenoiseMs,
                tDeconvMs,
                totalMs,
                numTiles,
                numTiles,
                PerformanceProfiler.estimateWorkingBufferMb(frameCount, srcW, srcH),
                PerformanceProfiler.currentJvmAllocatedMb());

        String srStateTag = lowSrBenefit
                ? "SR BENEFÍCIO BAIXO (CONSERVADOR)"
                : String.format(Locale.US, "SR FASE %.0f%% (%s)", subpixelCoverage * 100f, artifactReport.selectedDeconvMode.name());

        String diag = String.format(
                Locale.US,
                "SR DRIZZLE+LANCZOS3 (%d/%dF, ref#%d) • %s • Detalhe %+.1f%% • %dms",
                qualityReport.usedFramesCount,
                frameCount,
                refIdx + 1,
                srStateTag,
                comparison.acutanceGainPct,
                totalMs);

        String sciTelemetry = String.format(
                Locale.US,
                "Usados %d/%dF (Rej %d) • Fluxo 4×3 Δ(%.2f,%.2f) Din=%d • FaseSR=%.0f%% • Deconv=%s • Ring=%.2f • %s",
                qualityReport.usedFramesCount,
                frameCount,
                qualityReport.rejectedFramesCount,
                lastDx,
                lastDy,
                totalDynamicBlocks,
                subpixelCoverage * 100f,
                artifactReport.selectedDeconvMode.label,
                artifactReport.ringingScore,
                profile.toCompactSummary());

        return new BurstResult(
                fused,
                targetW,
                targetH,
                lastDx,
                lastDy,
                qualityReport.usedFramesCount,
                qualityReport.rejectedFramesCount,
                subpixelCoverage,
                lowSrBenefit,
                totalDynamicBlocks,
                artifactReport,
                comparison,
                profile,
                diag,
                sciTelemetry);
    }

    /**
     * Reconstructs a horizontal tile band `[yStart, yEnd)` using Phase-Weighted Sub-Pixel Drizzle +
     * Local 4x3 Optical Flow + Local Sharpness Weighting `peso(frame, x, y)`.
     */
    private static void reconstructTileBand(
            int yStart, int yEnd, int targetW, int targetH, int srcW, int srcH,
            float startX, float startY, float stepX, float stepY,
            float clampedSr, boolean superResolving, boolean hdrMode,
            List<int[]> frames, int[] ref, int refIdx,
            FrameQualityAnalyzer.BurstQualityReport qualityReport,
            List<LocalRegistration.MotionField4x3> motionFields,
            int[] outFused, int[] outBaseline) {

        int frameCount = frames.size();
        float[] motionSample = new float[3]; // [dx, dy, blockConfidence]

        for (int y = yStart; y < yEnd; y++) {
            float srcY = superResolving ? (startY + (y + 0.5f) * stepY - 0.5f) : y;
            int outRow = y * targetW;

            for (int x = 0; x < targetW; x++) {
                float srcX = superResolving ? (startX + (x + 0.5f) * stepX - 0.5f) : x;

                // Single-frame baseline sample (Case A: reference frame only)
                int refInterpColor = superResolving
                        ? sampleLanczos3(ref, srcW, srcH, srcX, srcY)
                        : ref[Math.min(srcH - 1, Math.max(0, y)) * srcW + Math.min(srcW - 1, Math.max(0, x))];
                outBaseline[outRow + x] = refInterpColor;

                int refR = (refInterpColor >> 16) & 0xFF;
                int refG = (refInterpColor >> 8) & 0xFF;
                int refB = refInterpColor & 0xFF;
                int refLum = (refR * 77 + refG * 150 + refB * 29) >> 8;

                // Reference frame Sub-Pixel Phase Proximity weight:
                // When superResolving, how close is (srcX, srcY) to an exact integer sensor pixel in `ref`?
                int nearRefX = Math.max(0, Math.min(srcW - 1, Math.round(srcX)));
                int nearRefY = Math.max(0, Math.min(srcH - 1, Math.round(srcY)));
                float dRefX = srcX - nearRefX;
                float dRefY = srcY - nearRefY;
                float refDist2 = dRefX * dRefX + dRefY * dRefY;

                // When superResolving, blend direct discrete sensor pixel `ref[nearRefY, nearRefX]`
                // with `refInterpColor` according to sub-pixel proximity so exact integer hits are never blurred
                int refDirect = ref[nearRefY * srcW + nearRefX];
                float directPhaseAlpha = superResolving ? (float) Math.exp(-refDist2 / (2.0 * 0.19 * 0.19)) : 1.0f;
                float r0 = ((refDirect >> 16) & 0xFF) * directPhaseAlpha + refR * (1f - directPhaseAlpha);
                float g0 = ((refDirect >> 8) & 0xFF) * directPhaseAlpha + refG * (1f - directPhaseAlpha);
                float b0 = (refDirect & 0xFF) * directPhaseAlpha + refB * (1f - directPhaseAlpha);

                float refPhaseW = superResolving
                        ? (0.32f + 0.95f * (float) Math.exp(-refDist2 / (2.0 * 0.24 * 0.24)))
                        : 1.25f;
                float refLocalSharpW = FrameQualityAnalyzer.sampleLocalSharpnessWeight(
                        qualityReport.scores[refIdx], srcX, srcY, srcW, srcH);
                float w0 = Math.max(0.25f, refPhaseW * refLocalSharpW);

                float sumR = r0 * w0;
                float sumG = g0 * w0;
                float sumB = b0 * w0;
                float totalW = w0;

                // Accumulate complementary sub-pixel samples across all aligned burst frames
                for (int f = 0; f < frameCount; f++) {
                    if (f == refIdx) continue;
                    FrameQualityAnalyzer.FrameScore fScore = qualityReport.scores[f];
                    if (fScore == null || fScore.rejectedOutlier) continue;

                    LocalRegistration.MotionField4x3 mf = motionFields.get(f);
                    mf.sample(srcX, srcY, srcW, srcH, motionSample);
                    float localConf = motionSample[2];
                    if (localConf < 0.12f) continue; // Reject dynamic / moving-object block

                    // Continuous coordinate in frame `f`'s sensor grid corresponding to `(srcX, srcY)` in `ref`
                    float fx = srcX + motionSample[0];
                    float fy = srcY + motionSample[1];
                    if (fx < 1f || fx >= srcW - 2f || fy < 1f || fy >= srcH - 2f) continue;

                    int[] fPixels = frames.get(f);
                    int nearX = Math.max(0, Math.min(srcW - 1, Math.round(fx)));
                    int nearY = Math.max(0, Math.min(srcH - 1, Math.round(fy)));
                    float dxSub = fx - nearX;
                    float dySub = fy - nearY;
                    float subDist2 = dxSub * dxSub + dySub * dySub;

                    // Drizzle Sub-Pixel Kernel:
                    // When (fx, fy) lands close to an integer sensor pixel `(nearX, nearY)` in frame `f`,
                    // frame `f` provides a direct physical sensor measurement at that sub-pixel location!
                    int directSample = fPixels[nearY * srcW + nearX];
                    int interpSample = superResolving
                            ? sampleBicubicFast(fPixels, srcW, srcH, fx, fy)
                            : sampleBilinearRgb(fPixels, srcW, srcH, fx, fy);

                    float dropAlpha = superResolving ? (float) Math.exp(-subDist2 / (2.0 * 0.22 * 0.22)) : 0.4f;
                    float sr = ((directSample >> 16) & 0xFF) * dropAlpha + ((interpSample >> 16) & 0xFF) * (1f - dropAlpha);
                    float sg = ((directSample >> 8) & 0xFF) * dropAlpha + ((interpSample >> 8) & 0xFF) * (1f - dropAlpha);
                    float sb = (directSample & 0xFF) * dropAlpha + (interpSample & 0xFF) * (1f - dropAlpha);
                    int sLum = Math.round(0.299f * sr + 0.587f * sg + 0.114f * sb);

                    // Photometric Huber/Tukey robustness against moving objects / occlusion
                    int diff = Math.abs(sLum - refLum)
                            + (Math.abs(Math.round(sr) - refR) + Math.abs(Math.round(sb) - refB)) / 4;
                    int maxDiff = hdrMode ? 92 : 48;
                    if (diff > maxDiff) continue;
                    float photoW = diff <= 14 ? 1.0f : (maxDiff - diff) / (float) (maxDiff - 14);

                    // Phase proximity weight: strongly boosts frames whose physical sensor grid hits this sub-pixel point
                    float phaseW = superResolving
                            ? (0.20f + 1.15f * (float) Math.exp(-subDist2 / (2.0 * 0.24 * 0.24)))
                            : 1.0f;

                    // Local sharpness weight `peso(frame, x, y)` from 4x3 regional sharpness map
                    float localSharpW = FrameQualityAnalyzer.sampleLocalSharpnessWeight(fScore, fx, fy, srcW, srcH);

                    // Optional HDR Mertens well-exposedness weight
                    float hdrExpW = 1.0f;
                    if (hdrMode) {
                        float dMid = (sLum - 128f) / 58f;
                        hdrExpW = 0.35f + 0.90f * (float) Math.exp(-0.5 * dMid * dMid);
                    }

                    float w = localConf * photoW * photoW * phaseW * localSharpW * hdrExpW;
                    sumR += sr * w;
                    sumG += sg * w;
                    sumB += sb * w;
                    totalW += w;
                }

                int outR = clamp255(Math.round(sumR / totalW));
                int outG = clamp255(Math.round(sumG / totalW));
                int outB = clamp255(Math.round(sumB / totalW));
                outFused[outRow + x] = 0xFF000000 | (outR << 16) | (outG << 8) | outB;
            }
        }
    }

    /**
     * Legacy-compatible overload returning `int[]` directly.
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
        BurstResult r = processBurstFull(frames, srcW, srcH, srFactor, outW, outH, sharpenAmount * 25f, nightMode, false);
        return r.pixels;
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
        return FrameQualityAnalyzer.analyzeBurst(frames, w, h).referenceIndex;
    }

    /**
     * Hierarchical coarse-to-fine sub-pixel shift estimator (0.05-pixel parabolic precision).
     */
    public static SubpixelShift estimateSubpixelShift(int[] ref, int[] other, int w, int h) {
        if (ref == null || other == null || w < 16 || h < 16 || ref.length < w * h || other.length < w * h) {
            return new SubpixelShift(0f, 0f, 0f);
        }

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
                for (int n = 0; n < 9; n++) {
                    int nc = neighbors[n];
                    int nr = (nc >> 16) & 0xFF;
                    int ng = (nc >> 8) & 0xFF;
                    int nb = nc & 0xFF;
                    int ny = (77 * nr + 150 * ng + 29 * nb) >> 8;
                    int dy = Math.abs(ny - y0);
                    if (dy > 26) continue;
                    int wgt = (n == 4) ? 6 : (dy < 10 ? 3 : 1);
                    int ncb = nb - ny;
                    int ncr = nr - ny;
                    sumCb += ncb * wgt;
                    sumCr += ncr * wgt;
                    sumW += wgt;
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
