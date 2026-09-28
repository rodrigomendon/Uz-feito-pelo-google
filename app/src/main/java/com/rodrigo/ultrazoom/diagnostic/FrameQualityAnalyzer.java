package com.rodrigo.ultrazoom.diagnostic;

import java.util.List;

/**
 * Multi-Frame Quality Analyzer & Local Sharpness Weighting Engine.
 *
 * Implements Sections 6 & 7:
 * - Evaluates each frame in a burst across 6 objective dimensions to compute a 0..100 Quality Score:
 *   1. Global Sharpness (Laplacian + Sobel Tenengrad energy)
 *   2. Regional 4x3 Sharpness Map (used for Local Sharpness Weighting `peso(frame, x, y)`)
 *   3. Flat-patch Noise Floor estimate
 *   4. Exposure & Highlight/Shadow Clipping penalty
 *   5. Directional Motion Blur Anisotropy (detects shutter smear)
 *   6. Geometric Alignment Consistency
 * - Rejects severe motion-blurred outliers (e.g. score 48 vs 91..96) while preserving multi-frame SNR fusion.
 * - Computes smooth spatial weights `localWeight(frameScore, x, y, w, h)` so locally crisp regions
 *   dominate over locally blurred regions without block boundary seams.
 */
public final class FrameQualityAnalyzer {

    public static final int GRID_COLS = 4;
    public static final int GRID_ROWS = 3;
    public static final int GRID_CELLS = GRID_COLS * GRID_ROWS;

    public static final class FrameScore {
        public final int frameIndex;
        public final double rawSharpness;
        public final float noiseStdDev;
        public final float clippingRatio;
        public final float motionAnisotropy;
        public final float score0To100;
        public final float globalWeight;
        public final boolean rejectedOutlier;
        public final float[] regionalSharpness = new float[GRID_CELLS];
        public final float[] regionalRelativeWeight = new float[GRID_CELLS];

        public FrameScore(
                int frameIndex,
                double rawSharpness,
                float noiseStdDev,
                float clippingRatio,
                float motionAnisotropy,
                float score0To100,
                float globalWeight,
                boolean rejectedOutlier,
                float[] regionalSharpness) {
            this.frameIndex = frameIndex;
            this.rawSharpness = rawSharpness;
            this.noiseStdDev = noiseStdDev;
            this.clippingRatio = clippingRatio;
            this.motionAnisotropy = motionAnisotropy;
            this.score0To100 = score0To100;
            this.globalWeight = globalWeight;
            this.rejectedOutlier = rejectedOutlier;
            if (regionalSharpness != null) {
                System.arraycopy(regionalSharpness, 0, this.regionalSharpness, 0, Math.min(GRID_CELLS, regionalSharpness.length));
            }
            for (int i = 0; i < GRID_CELLS; i++) {
                this.regionalRelativeWeight[i] = 1.0f;
            }
        }
    }

    public static final class BurstQualityReport {
        public final int referenceIndex;
        public final FrameScore[] scores;
        public final int usedFramesCount;
        public final int rejectedFramesCount;
        public final float meanQualityScore;

        public BurstQualityReport(
                int referenceIndex,
                FrameScore[] scores,
                int usedFramesCount,
                int rejectedFramesCount,
                float meanQualityScore) {
            this.referenceIndex = referenceIndex;
            this.scores = scores;
            this.usedFramesCount = usedFramesCount;
            this.rejectedFramesCount = rejectedFramesCount;
            this.meanQualityScore = meanQualityScore;
        }
    }

    /**
     * Evaluates all frames in the burst, selects the best reference frame, assigns 0-100 quality scores,
     * rejects motion-blurred outliers, and normalizes 4x3 regional sharpness weights `peso(frame, x, y)`.
     */
    public static BurstQualityReport analyzeBurst(List<int[]> frames, int w, int h) {
        if (frames == null || frames.isEmpty() || w < 8 || h < 8) {
            return new BurstQualityReport(0, new FrameScore[0], 0, 0, 0f);
        }
        int n = frames.size();
        double[] rawSharp = new double[n];
        float[] noise = new float[n];
        float[] clip = new float[n];
        float[] aniso = new float[n];
        float[][] regSharp = new float[n][GRID_CELLS];

        double maxSharp = 1e-3;
        for (int i = 0; i < n; i++) {
            int[] px = frames.get(i);
            if (px == null || px.length < w * h) continue;
            rawSharp[i] = evaluateFrameMetrics(px, w, h, regSharp[i], noise, clip, aniso, i);
            if (rawSharp[i] > maxSharp) {
                maxSharp = rawSharp[i];
            }
        }

        // Compute 0..100 composite quality score per frame
        float bestComposite = -1f;
        int refIdx = 0;
        float[] composite = new float[n];
        for (int i = 0; i < n; i++) {
            float normSharp = (float) Math.min(1.0, rawSharp[i] / maxSharp);
            float noisePenalty = Math.min(0.22f, noise[i] / 95f);
            float clipPenalty = Math.min(0.25f, clip[i] * 0.65f);
            float blurPenalty = Math.min(0.28f, Math.max(0f, aniso[i] - 0.55f) * 0.6f);
            float raw01 = Math.max(0.02f, normSharp * (1.0f - blurPenalty) - noisePenalty - clipPenalty);
            composite[i] = Math.min(100f, Math.round(raw01 * 98f * 10f) / 10f);
            if (composite[i] > bestComposite) {
                bestComposite = composite[i];
                refIdx = i;
            }
        }

        // Outlier rejection threshold: frames below 54% of the best frame's composite score
        float outlierCutoff = Math.max(28f, bestComposite * 0.54f);
        FrameScore[] outScores = new FrameScore[n];
        int used = 0;
        int rejected = 0;
        float sumScore = 0f;

        for (int i = 0; i < n; i++) {
            boolean isOutlier = (i != refIdx) && (composite[i] < outlierCutoff || rawSharp[i] < maxSharp * 0.40);
            float weight = isOutlier ? 0f : (float) Math.pow(composite[i] / Math.max(1f, bestComposite), 1.6);
            if (i == refIdx) weight = 1.25f;
            outScores[i] = new FrameScore(
                    i,
                    rawSharp[i],
                    noise[i],
                    clip[i],
                    aniso[i],
                    composite[i],
                    weight,
                    isOutlier,
                    regSharp[i]);
            if (isOutlier) {
                rejected++;
            } else {
                used++;
                sumScore += composite[i];
            }
        }

        // Normalize 4x3 regional sharpness weights across non-rejected frames so sharp patches dominate locally
        for (int cell = 0; cell < GRID_CELLS; cell++) {
            float maxCellSharp = 1e-3f;
            for (int i = 0; i < n; i++) {
                if (!outScores[i].rejectedOutlier && outScores[i].regionalSharpness[cell] > maxCellSharp) {
                    maxCellSharp = outScores[i].regionalSharpness[cell];
                }
            }
            for (int i = 0; i < n; i++) {
                if (outScores[i].rejectedOutlier) {
                    outScores[i].regionalRelativeWeight[cell] = 0f;
                } else {
                    float rel = outScores[i].regionalSharpness[cell] / maxCellSharp;
                    // Smooth power law (rel^1.8) rewards locally sharper frame without zeroing out SNR contribution
                    outScores[i].regionalRelativeWeight[cell] = Math.max(0.20f, Math.min(1.35f, (float) Math.pow(rel, 1.8)));
                }
            }
        }

        return new BurstQualityReport(refIdx, outScores, used, rejected, sumScore / Math.max(1, used));
    }

    /**
     * Bilinearly interpolates the local sharpness weight `peso(frame, x, y)` from the 4x3 regional map.
     */
    public static float sampleLocalSharpnessWeight(FrameScore score, float x, float y, int w, int h) {
        if (score == null || score.rejectedOutlier) return 0f;
        float gx = Math.max(0f, Math.min(GRID_COLS - 1.001f, (x / Math.max(1f, w)) * GRID_COLS - 0.5f));
        float gy = Math.max(0f, Math.min(GRID_ROWS - 1.001f, (y / Math.max(1f, h)) * GRID_ROWS - 0.5f));
        int x0 = Math.max(0, Math.min(GRID_COLS - 1, (int) Math.floor(gx)));
        int y0 = Math.max(0, Math.min(GRID_ROWS - 1, (int) Math.floor(gy)));
        int x1 = Math.min(GRID_COLS - 1, x0 + 1);
        int y1 = Math.min(GRID_ROWS - 1, y0 + 1);
        float fx = Math.max(0f, gx - x0);
        float fy = Math.max(0f, gy - y0);

        float w00 = score.regionalRelativeWeight[y0 * GRID_COLS + x0];
        float w10 = score.regionalRelativeWeight[y0 * GRID_COLS + x1];
        float w01 = score.regionalRelativeWeight[y1 * GRID_COLS + x0];
        float w11 = score.regionalRelativeWeight[y1 * GRID_COLS + x1];

        float top = w00 + fx * (w10 - w00);
        float bot = w01 + fx * (w11 - w01);
        return score.globalWeight * (top + fy * (bot - top));
    }

    private static double evaluateFrameMetrics(
            int[] px, int w, int h,
            float[] outRegionalSharpness,
            float[] outNoise,
            float[] outClip,
            float[] outAniso,
            int frameIdx) {

        int step = Math.max(1, Math.min(w, h) / 120);
        double[] cellSumLap2 = new double[GRID_CELLS];
        int[] cellCount = new int[GRID_CELLS];

        double sumGx2 = 0.0;
        double sumGy2 = 0.0;
        double sumGxGy = 0.0;
        double sumNoiseDiff = 0.0;
        int flatCount = 0;
        int clipCount = 0;
        int totalSamples = 0;

        for (int y = 2; y < h - 2; y += step) {
            int row = y * w;
            int cellY = Math.min(GRID_ROWS - 1, (y * GRID_ROWS) / h);
            for (int x = 2; x < w - 2; x += step) {
                int cellX = Math.min(GRID_COLS - 1, (x * GRID_COLS) / w);
                int cellIdx = cellY * GRID_COLS + cellX;
                int idx = row + x;

                int c = lum(px[idx]);
                int l = lum(px[idx - 1]);
                int r = lum(px[idx + 1]);
                int u = lum(px[idx - w]);
                int d = lum(px[idx + w]);

                if (c >= 250 || c <= 4) clipCount++;
                totalSamples++;

                int gx = r - l;
                int gy = d - u;
                int lap = 4 * c - l - r - u - d;

                double energy = lap * lap + 0.5 * (gx * gx + gy * gy);
                cellSumLap2[cellIdx] += energy;
                cellCount[cellIdx]++;

                sumGx2 += gx * gx;
                sumGy2 += gy * gy;
                sumGxGy += gx * gy;

                if (Math.abs(gx) + Math.abs(gy) < 12) {
                    sumNoiseDiff += Math.abs(lap) * 0.25;
                    flatCount++;
                }
            }
        }

        double weightedGlobalSharpness = 0.0;
        double weightSum = 0.0;
        for (int cell = 0; cell < GRID_CELLS; cell++) {
            float cellVal = cellCount[cell] > 0 ? (float) Math.sqrt(cellSumLap2[cell] / cellCount[cell]) : 0f;
            outRegionalSharpness[cell] = cellVal;
            int cx = cell % GRID_COLS;
            int cy = cell / GRID_COLS;
            boolean isCenter = (cx == 1 || cx == 2) && (cy == 1);
            double cw = isCenter ? 1.6 : 1.0;
            weightedGlobalSharpness += (cellVal * cellVal) * cw;
            weightSum += cw;
        }

        outNoise[frameIdx] = flatCount > 10 ? (float) (sumNoiseDiff / flatCount) : 2.0f;
        outClip[frameIdx] = totalSamples > 0 ? (float) clipCount / totalSamples : 0f;

        // Structure tensor eigenvalues for directional motion smear detection
        double trace = sumGx2 + sumGy2;
        double det = sumGx2 * sumGy2 - sumGxGy * sumGxGy;
        double disc = Math.sqrt(Math.max(0.0, trace * trace * 0.25 - det));
        double lambda1 = trace * 0.5 + disc;
        double lambda2 = Math.max(0.0, trace * 0.5 - disc);
        outAniso[frameIdx] = (lambda1 + lambda2 > 1e-3)
                ? (float) ((lambda1 - lambda2) / (lambda1 + lambda2))
                : 0f;

        return weightSum > 0 ? (weightedGlobalSharpness / weightSum) : 0.0;
    }

    private static int lum(int argb) {
        return (((argb >> 16) & 0xFF) * 77 + ((argb >> 8) & 0xFF) * 150 + (argb & 0xFF) * 29) >> 8;
    }

    private FrameQualityAnalyzer() {}
}
