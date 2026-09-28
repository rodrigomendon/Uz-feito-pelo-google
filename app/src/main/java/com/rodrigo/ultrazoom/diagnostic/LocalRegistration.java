package com.rodrigo.ultrazoom.diagnostic;

import java.util.List;

/**
 * Global + Local 4x3 Block Optical Flow Registration & Dynamic Scene Detector.
 *
 * Implements Sections 4, 5, 24, 39:
 * - Level 1: Global sub-pixel registration (2-level coarse-to-fine + parabolic refinement).
 * - Level 2: 4x3 Local Block Motion Field (`MotionField4x3`) capturing handheld roll/rotation,
 *   perspective tilt, and OIS lens shift across the frame.
 * - Level 3: Dynamic object / inconsistent region detection (`dynamicMask[12]`): detects moving
 *   objects (pedestrians, cars, foliage) whose motion or residual error violates rigid-camera
 *   continuity, preventing multi-frame ghosting.
 * - Level 4: Sub-pixel phase diversity analyzer (`computeSubpixelCoverage`): verifies whether
 *   burst frames carry genuinely complementary sub-pixel phases (0.25px, 0.50px, 0.75px) vs
 *   redundant integer shifts.
 */
public final class LocalRegistration {

    public static final int GRID_COLS = 4;
    public static final int GRID_ROWS = 3;
    public static final int GRID_CELLS = GRID_COLS * GRID_ROWS;

    public static final class MotionVector {
        public final float dx;
        public final float dy;
        public final float confidence;
        public final boolean dynamicObject;

        public MotionVector(float dx, float dy, float confidence, boolean dynamicObject) {
            this.dx = dx;
            this.dy = dy;
            this.confidence = confidence;
            this.dynamicObject = dynamicObject;
        }
    }

    public static final class MotionField4x3 {
        public final float globalDx;
        public final float globalDy;
        public final float globalConfidence;
        public final float[] blockDx = new float[GRID_CELLS];
        public final float[] blockDy = new float[GRID_CELLS];
        public final float[] blockConfidence = new float[GRID_CELLS];
        public final boolean[] dynamicBlock = new boolean[GRID_CELLS];
        public final int dynamicBlocksCount;
        public final float maxLocalDeviation;

        public MotionField4x3(
                float globalDx,
                float globalDy,
                float globalConfidence,
                float[] blockDx,
                float[] blockDy,
                float[] blockConfidence,
                boolean[] dynamicBlock,
                int dynamicBlocksCount,
                float maxLocalDeviation) {
            this.globalDx = globalDx;
            this.globalDy = globalDy;
            this.globalConfidence = globalConfidence;
            System.arraycopy(blockDx, 0, this.blockDx, 0, GRID_CELLS);
            System.arraycopy(blockDy, 0, this.blockDy, 0, GRID_CELLS);
            System.arraycopy(blockConfidence, 0, this.blockConfidence, 0, GRID_CELLS);
            System.arraycopy(dynamicBlock, 0, this.dynamicBlock, 0, GRID_CELLS);
            this.dynamicBlocksCount = dynamicBlocksCount;
            this.maxLocalDeviation = maxLocalDeviation;
        }

        /**
         * Identity motion field for the reference frame (zero shift, full confidence).
         */
        public static MotionField4x3 identity() {
            float[] z = new float[GRID_CELLS];
            float[] c = new float[GRID_CELLS];
            boolean[] d = new boolean[GRID_CELLS];
            for (int i = 0; i < GRID_CELLS; i++) c[i] = 1.0f;
            return new MotionField4x3(0f, 0f, 1.0f, z, z, c, d, 0, 0f);
        }

        /**
         * Bilinearly interpolates the local sub-pixel motion vector `(dx(x, y), dy(x, y))`
         * and local alignment confidence at pixel `(x, y)`.
         */
        public void sample(float x, float y, int w, int h, float[] outDxDyConf) {
            float gx = Math.max(0f, Math.min(GRID_COLS - 1.001f, (x / Math.max(1f, w)) * GRID_COLS - 0.5f));
            float gy = Math.max(0f, Math.min(GRID_ROWS - 1.001f, (y / Math.max(1f, h)) * GRID_ROWS - 0.5f));
            int x0 = Math.max(0, Math.min(GRID_COLS - 1, (int) Math.floor(gx)));
            int y0 = Math.max(0, Math.min(GRID_ROWS - 1, (int) Math.floor(gy)));
            int x1 = Math.min(GRID_COLS - 1, x0 + 1);
            int y1 = Math.min(GRID_ROWS - 1, y0 + 1);
            float fx = Math.max(0f, gx - x0);
            float fy = Math.max(0f, gy - y0);

            int i00 = y0 * GRID_COLS + x0;
            int i10 = y0 * GRID_COLS + x1;
            int i01 = y1 * GRID_COLS + x0;
            int i11 = y1 * GRID_COLS + x1;

            float w00 = (1f - fx) * (1f - fy);
            float w10 = fx * (1f - fy);
            float w01 = (1f - fx) * fy;
            float w11 = fx * fy;

            outDxDyConf[0] = blockDx[i00] * w00 + blockDx[i10] * w10 + blockDx[i01] * w01 + blockDx[i11] * w11;
            outDxDyConf[1] = blockDy[i00] * w00 + blockDy[i10] * w10 + blockDy[i01] * w01 + blockDy[i11] * w11;
            outDxDyConf[2] = blockConfidence[i00] * w00 + blockConfidence[i10] * w10 + blockConfidence[i01] * w01 + blockConfidence[i11] * w11;
        }
    }

    /**
     * Estimates both global sub-pixel shift and the 4x3 local optical flow field between `ref` and `other`.
     */
    public static MotionField4x3 estimateMotionField(int[] ref, int[] other, int w, int h) {
        if (ref == null || other == null || w < 24 || h < 24 || ref.length < w * h || other.length < w * h) {
            return MotionField4x3.identity();
        }

        // 1. Global sub-pixel registration on central ROI
        SuperResolutionEngine.SubpixelShift global = SuperResolutionEngine.estimateSubpixelShift(ref, other, w, h);
        int baseDx = Math.round(global.dx);
        int baseDy = Math.round(global.dy);

        float[] bDx = new float[GRID_CELLS];
        float[] bDy = new float[GRID_CELLS];
        float[] bConf = new float[GRID_CELLS];
        double[] bResidual = new double[GRID_CELLS];
        boolean[] bDynamic = new boolean[GRID_CELLS];

        int cellW = w / GRID_COLS;
        int cellH = h / GRID_ROWS;
        int step = Math.max(1, Math.min(cellW, cellH) / 28);
        int localRadius = Math.min(5, Math.max(2, Math.min(cellW, cellH) / 8));

        // 2. Refine local 4x3 blocks around global shift
        for (int cy = 0; cy < GRID_ROWS; cy++) {
            for (int cx = 0; cx < GRID_COLS; cx++) {
                int cellIdx = cy * GRID_COLS + cx;
                int startX = Math.max(localRadius + Math.abs(baseDx) + 2, cx * cellW + cellW / 8);
                int endX = Math.min(w - localRadius - Math.abs(baseDx) - 3, (cx + 1) * cellW - cellW / 8);
                int startY = Math.max(localRadius + Math.abs(baseDy) + 2, cy * cellH + cellH / 8);
                int endY = Math.min(h - localRadius - Math.abs(baseDy) - 3, (cy + 1) * cellH - cellH / 8);

                if (endX <= startX + 6 || endY <= startY + 6) {
                    bDx[cellIdx] = global.dx;
                    bDy[cellIdx] = global.dy;
                    bConf[cellIdx] = global.confidence;
                    continue;
                }

                // Check if block has sufficient gradient structure for local refinement
                double gradEnergy = blockGradientEnergy(ref, w, startX, startY, endX, endY, step);
                if (gradEnergy < 18.0) {
                    // Flat block (e.g., sky): inherit global shift smoothly
                    bDx[cellIdx] = global.dx;
                    bDy[cellIdx] = global.dy;
                    bConf[cellIdx] = global.confidence;
                    bResidual[cellIdx] = blockError(ref, other, w, h, startX, startY, endX, endY, baseDx, baseDy, step);
                    continue;
                }

                int bestDx = baseDx;
                int bestDy = baseDy;
                double bestErr = Double.MAX_VALUE;

                for (int dy = baseDy - localRadius; dy <= baseDy + localRadius; dy++) {
                    for (int dx = baseDx - localRadius; dx <= baseDx + localRadius; dx++) {
                        // Soft regularization toward global shift so noisy textures don't jump randomly
                        double regPenalty = 0.45 * ((dx - global.dx) * (dx - global.dx) + (dy - global.dy) * (dy - global.dy));
                        double err = blockError(ref, other, w, h, startX, startY, endX, endY, dx, dy, step) + regPenalty;
                        if (err < bestErr) {
                            bestErr = err;
                            bestDx = dx;
                            bestDy = dy;
                        }
                    }
                }

                // Parabolic sub-pixel refinement for this block
                float subX = bestDx;
                float subY = bestDy;
                double eCenter = blockError(ref, other, w, h, startX, startY, endX, endY, bestDx, bestDy, step);
                double eLeft = blockError(ref, other, w, h, startX, startY, endX, endY, bestDx - 1, bestDy, step);
                double eRight = blockError(ref, other, w, h, startX, startY, endX, endY, bestDx + 1, bestDy, step);
                double denomX = 2.0 * (eLeft - 2.0 * eCenter + eRight);
                if (Math.abs(denomX) > 1e-4) {
                    double delta = (eLeft - eRight) / denomX;
                    if (Math.abs(delta) <= 0.65) subX += (float) delta;
                }

                double eUp = blockError(ref, other, w, h, startX, startY, endX, endY, bestDx, bestDy - 1, step);
                double eDown = blockError(ref, other, w, h, startX, startY, endX, endY, bestDx, bestDy + 1, step);
                double denomY = 2.0 * (eUp - 2.0 * eCenter + eDown);
                if (Math.abs(denomY) > 1e-4) {
                    double delta = (eUp - eDown) / denomY;
                    if (Math.abs(delta) <= 0.65) subY += (float) delta;
                }

                bDx[cellIdx] = subX;
                bDy[cellIdx] = subY;
                bResidual[cellIdx] = eCenter;
                bConf[cellIdx] = (float) Math.max(0.0, Math.min(1.0, 1.0 - Math.sqrt(eCenter) / 58.0));
            }
        }

        // 3. Detect dynamic / inconsistent blocks (moving objects vs smooth camera rotation/tilt)
        int dynCount = 0;
        float maxDev = 0f;
        for (int i = 0; i < GRID_CELLS; i++) {
            float dev = (float) Math.hypot(bDx[i] - global.dx, bDy[i] - global.dy);
            if (dev > maxDev) maxDev = dev;
            // A block is flagged as a moving object if its residual error after alignment is high (> 520)
            // OR it deviates sharply (> 3.2 px) from global motion with degraded confidence (< 0.52)
            if (bResidual[i] > 520.0 || (dev > 3.2f && bConf[i] < 0.52f)) {
                bDynamic[i] = true;
                bConf[i] = Math.min(bConf[i], 0.08f); // Suppress fusion weight in moving-object block to prevent ghosting
                dynCount++;
            }
        }

        return new MotionField4x3(
                global.dx,
                global.dy,
                global.confidence,
                bDx,
                bDy,
                bConf,
                bDynamic,
                dynCount,
                maxDev);
    }

    /**
     * Quantifies whether the aligned frames provide complementary sub-pixel phase coverage
     * (0.0 = all frames have identical integer phase -> no SR benefit;
     *  1.0 = uniform sub-pixel phase coverage across 1/2 and 1/4 pixel bins).
     */
    public static float computeSubpixelPhaseCoverage(List<MotionField4x3> fields, int refIdx) {
        if (fields == null || fields.size() < 2) return 0f;
        // 4x4 sub-pixel phase bins in [0, 1) x [0, 1)
        boolean[] bins2x2 = new boolean[4];
        boolean[] bins4x4 = new boolean[16];
        bins2x2[0] = true;
        bins4x4[0] = true;

        float sumSubpixelDist = 0f;
        int validCount = 0;

        for (int f = 0; f < fields.size(); f++) {
            if (f == refIdx) continue;
            MotionField4x3 mf = fields.get(f);
            if (mf == null || mf.globalConfidence < 0.20f) continue;

            // Evaluate fractional phase across both global shift and central blocks
            for (int cell : new int[]{4, 5, 6, 7}) {
                float dx = mf.blockDx[cell];
                float dy = mf.blockDy[cell];
                float fracX = dx - (float) Math.floor(dx);
                float fracY = dy - (float) Math.floor(dy);

                int bx2 = Math.min(1, (int) (fracX * 2f));
                int by2 = Math.min(1, (int) (fracY * 2f));
                bins2x2[by2 * 2 + bx2] = true;

                int bx4 = Math.min(3, (int) (fracX * 4f));
                int by4 = Math.min(3, (int) (fracY * 4f));
                bins4x4[by4 * 4 + bx4] = true;

                // Distance from nearest integer grid point
                float distX = Math.min(fracX, 1f - fracX);
                float distY = Math.min(fracY, 1f - fracY);
                sumSubpixelDist += (float) Math.hypot(distX, distY);
                validCount++;
            }
        }

        if (validCount == 0) return 0f;
        int count2x2 = 0;
        for (boolean b : bins2x2) if (b) count2x2++;
        int count4x4 = 0;
        for (boolean b : bins4x4) if (b) count4x4++;

        float avgSubDist = sumSubpixelDist / validCount; // Max possible is sqrt(0.5^2 + 0.5^2) = 0.707
        if (avgSubDist < 0.04f) return 0.05f; // Nearly pure integer shift -> no complementary sub-pixel phase!

        float coverage2 = (count2x2 - 1) / 3.0f;
        float coverage4 = (count4x4 - 1) / 8.0f;
        float distScore = Math.min(1.0f, avgSubDist / 0.35f);
        return Math.max(0f, Math.min(1.0f, 0.45f * coverage2 + 0.30f * Math.min(1f, coverage4) + 0.25f * distScore));
    }

    private static double blockGradientEnergy(int[] px, int w, int startX, int startY, int endX, int endY, int step) {
        double sum = 0.0;
        int n = 0;
        for (int y = startY; y < endY; y += step) {
            int row = y * w;
            for (int x = startX; x < endX; x += step) {
                int idx = row + x;
                int gx = Math.abs(lum(px[idx + 1]) - lum(px[idx - 1]));
                int gy = Math.abs(lum(px[idx + w]) - lum(px[idx - w]));
                sum += gx + gy;
                n++;
            }
        }
        return n > 0 ? (sum / n) : 0.0;
    }

    private static double blockError(
            int[] ref, int[] other, int w, int h,
            int startX, int startY, int endX, int endY,
            int dx, int dy, int step) {
        double sum = 0.0;
        int n = 0;
        for (int y = startY; y < endY; y += step) {
            int by = y + dy;
            if (by < 1 || by >= h - 1) continue;
            int rowA = y * w;
            int rowB = by * w;
            for (int x = startX; x < endX; x += step) {
                int bx = x + dx;
                if (bx < 1 || bx >= w - 1) continue;
                int la = lum(ref[rowA + x]);
                int lb = lum(other[rowB + bx]);
                int dLum = la - lb;
                int gxA = lum(ref[rowA + x + 1]) - lum(ref[rowA + x - 1]);
                int gxB = lum(other[rowB + bx + 1]) - lum(other[rowB + bx - 1]);
                int dGrad = gxA - gxB;
                sum += dLum * dLum + 0.30 * dGrad * dGrad;
                n++;
            }
        }
        return n == 0 ? Double.MAX_VALUE : (sum / n);
    }

    private static int lum(int argb) {
        return (((argb >> 16) & 0xFF) * 77 + ((argb >> 8) & 0xFF) * 150 + (argb & 0xFF) * 29) >> 8;
    }

    private LocalRegistration() {}
}
