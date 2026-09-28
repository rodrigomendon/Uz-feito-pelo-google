package com.rodrigo.ultrazoom.diagnostic;

/**
 * PSF Deconvolution, Region-Aware Adaptive Sharpening & Hallucination/Artifact Control Engine.
 *
 * Implements Sections 15, 16, 17, 29:
 * - Section 16 (Deconvolution): Models optical + sampling PSF and evaluates 3 candidates
 *   (No Deconvolution, Mild RL Deconvolution, Strong RL Deconvolution), selecting the candidate
 *   that maximizes genuine edge acutance without increasing ringing or flat-patch noise.
 * - Section 17 (Adaptive Sharpening): Differentiates Strong Edges, Fine Textures, Smooth/Flat regions,
 *   and Low-Confidence/Noise regions using local gradient coherence and variance.
 * - Section 15 (Hallucination / Artifact Detector): Measures ringing overshoot, artificial high-frequency
 *   amplification (oversharpening), and structural ghosting against the aligned reference image,
 *   automatically blending back toward the conservative base when artifacts exceed safe limits.
 */
public final class DeconvolutionEngine {

    public enum DeconvMode {
        NONE("OFF (CONSERVADOR)"),
        MILD("RL-PSF LEVE (1 ITER)"),
        STRONG("RL-PSF FORTE (2 ITER)");

        public final String label;

        DeconvMode(String label) {
            this.label = label;
        }
    }

    public static final class ArtifactReport {
        public final DeconvMode selectedDeconvMode;
        public final float ringingScore;
        public final float oversharpenScore;
        public final float ghostingScore;
        public final boolean artifactRollbackApplied;
        public final float rollbackBlendRatio;

        public ArtifactReport(
                DeconvMode selectedDeconvMode,
                float ringingScore,
                float oversharpenScore,
                float ghostingScore,
                boolean artifactRollbackApplied,
                float rollbackBlendRatio) {
            this.selectedDeconvMode = selectedDeconvMode;
            this.ringingScore = ringingScore;
            this.oversharpenScore = oversharpenScore;
            this.ghostingScore = ghostingScore;
            this.artifactRollbackApplied = artifactRollbackApplied;
            this.rollbackBlendRatio = rollbackBlendRatio;
        }
    }

    /**
     * Applies SNR-gated PSF deconvolution + region-aware adaptive sharpening + artifact hallucination control
     * in-place on `fusedPixels`, comparing against `conservativeRefPixels`.
     */
    public static ArtifactReport processWithArtifactControl(
            int[] fusedPixels,
            int[] conservativeRefPixels,
            int w,
            int h,
            float baseStrength,
            float subpixelCoverage) {

        if (fusedPixels == null || w < 8 || h < 8 || fusedPixels.length < w * h) {
            return new ArtifactReport(DeconvMode.NONE, 0f, 0f, 0f, false, 0f);
        }

        // Keep a snapshot of the clean post-fusion / post-chroma-denoise image before deconvolution/sharpening
        int[] cleanFused = new int[w * h];
        System.arraycopy(fusedPixels, 0, cleanFused, 0, w * h);

        // Extract luminance Y [0..255]
        float[] lumBase = new float[w * h];
        for (int i = 0; i < w * h; i++) {
            lumBase[i] = lum(cleanFused[i]);
        }

        // Choose PSF deconvolution mode conservatively based on scene SNR and sub-pixel phase benefit
        DeconvMode chosenMode = DeconvMode.NONE;
        float[] lumWorking = lumBase;

        if (baseStrength > 0.08f && subpixelCoverage >= 0.15f) {
            float[] lumMild = dampedRichardsonLucyStep(lumBase, w, h, 0.55f);
            double utilNone = evaluateCandidateUtility(lumBase, lumBase, w, h);
            double utilMild = evaluateCandidateUtility(lumMild, lumBase, w, h);

            if (utilMild > utilNone * 1.03) {
                chosenMode = DeconvMode.MILD;
                lumWorking = lumMild;

                if (baseStrength >= 0.28f && subpixelCoverage >= 0.42f) {
                    float[] lumStrong = dampedRichardsonLucyStep(lumMild, w, h, 0.42f);
                    double utilStrong = evaluateCandidateUtility(lumStrong, lumBase, w, h);
                    if (utilStrong > utilMild * 1.02) {
                        chosenMode = DeconvMode.STRONG;
                        lumWorking = lumStrong;
                    }
                }
            }
        }

        // Region-Aware Adaptive Sharpening (Edge vs Texture vs Flat vs Noise)
        applyRegionAwareSharpening(fusedPixels, cleanFused, lumWorking, lumBase, w, h, baseStrength);

        // Hallucination & Artifact Detection against cleanFused and conservativeRefPixels
        int[] refForAudit = (conservativeRefPixels != null && conservativeRefPixels.length >= w * h)
                ? conservativeRefPixels
                : cleanFused;

        float ringing = computeRingingMetric(fusedPixels, cleanFused, w, h);
        float oversharpen = computeOversharpenMetric(fusedPixels, cleanFused, w, h);
        float ghosting = computeGhostingMetric(fusedPixels, refForAudit, w, h);

        boolean rollback = false;
        float blendBack = 0f;

        // If ringing or oversharpening exceeds safe perceptual thresholds, automatically dial back
        if (ringing > 0.22f || oversharpen > 0.35f) {
            rollback = true;
            blendBack = Math.min(0.75f, Math.max((ringing - 0.16f) * 1.8f, (oversharpen - 0.25f) * 1.4f));
            int invWeight = Math.round((1f - blendBack) * 256f);
            int cleanWeight = 256 - invWeight;
            for (int i = 0; i < w * h; i++) {
                int cSharp = fusedPixels[i];
                int cClean = cleanFused[i];
                int r = (((cSharp >> 16) & 0xFF) * invWeight + ((cClean >> 16) & 0xFF) * cleanWeight) >> 8;
                int g = (((cSharp >> 8) & 0xFF) * invWeight + ((cClean >> 8) & 0xFF) * cleanWeight) >> 8;
                int b = ((cSharp & 0xFF) * invWeight + (cClean & 0xFF) * cleanWeight) >> 8;
                fusedPixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            ringing = computeRingingMetric(fusedPixels, cleanFused, w, h);
            oversharpen = computeOversharpenMetric(fusedPixels, cleanFused, w, h);
        }

        return new ArtifactReport(chosenMode, ringing, oversharpen, ghosting, rollback, blendBack);
    }

    /**
     * One damped Richardson-Lucy / regularized PSF deconvolution iteration with SNR noise gate.
     * Models a 3x3 optical + sampling Gaussian PSF: [1 2 1; 2 4 2; 1 2 1] / 16.
     */
    private static float[] dampedRichardsonLucyStep(float[] lumIn, int w, int h, float dampingGain) {
        float[] blurred = gaussian3x3(lumIn, w, h);
        float[] ratio = new float[w * h];
        for (int i = 0; i < w * h; i++) {
            ratio[i] = (lumIn[i] + 1.0f) / (blurred[i] + 1.0f);
        }
        float[] corr = gaussian3x3(ratio, w, h);
        float[] out = new float[w * h];
        System.arraycopy(lumIn, 0, out, 0, w * h);

        for (int y = 1; y < h - 1; y++) {
            int row = y * w;
            for (int x = 1; x < w - 1; x++) {
                int idx = row + x;
                float c = lumIn[idx];
                float l = lumIn[idx - 1], r = lumIn[idx + 1];
                float u = lumIn[idx - w], d = lumIn[idx + w];
                float grad = Math.abs(r - l) + Math.abs(d - u);

                // Damped RL noise gate: flat regions (grad <= 6) receive zero deconvolution to avoid noise amplification
                if (grad <= 6f) continue;
                float snrGate = Math.min(1.0f, (grad - 6f) / 18f);
                float rlUpdate = c * corr[idx] - c;

                // Clamp update within local 3x3 bounds to prevent PSF ringing oscillation
                float minN = Math.min(c, Math.min(Math.min(l, r), Math.min(u, d)));
                float maxN = Math.max(c, Math.max(Math.max(l, r), Math.max(u, d)));
                float target = c + rlUpdate * dampingGain * snrGate;
                out[idx] = Math.max(minN - 2f, Math.min(maxN + 2f, target));
            }
        }
        return out;
    }

    private static float[] gaussian3x3(float[] src, int w, int h) {
        float[] dst = new float[w * h];
        System.arraycopy(src, 0, dst, 0, w * h);
        for (int y = 1; y < h - 1; y++) {
            int row = y * w;
            for (int x = 1; x < w - 1; x++) {
                int i = row + x;
                float sum = 4f * src[i]
                        + 2f * (src[i - 1] + src[i + 1] + src[i - w] + src[i + w])
                        + (src[i - w - 1] + src[i - w + 1] + src[i + w - 1] + src[i + w + 1]);
                dst[i] = sum * 0.0625f;
            }
        }
        return dst;
    }

    private static double evaluateCandidateUtility(float[] candidate, float[] base, int w, int h) {
        int step = Math.max(1, Math.min(w, h) / 96);
        double edgeGain = 0.0;
        double ringingPenalty = 0.0;
        double flatNoisePenalty = 0.0;
        int n = 0;

        for (int y = 2; y < h - 2; y += step) {
            int row = y * w;
            for (int x = 2; x < w - 2; x += step) {
                int i = row + x;
                float gxB = Math.abs(base[i + 1] - base[i - 1]);
                float gyB = Math.abs(base[i + w] - base[i - w]);
                float gxC = Math.abs(candidate[i + 1] - candidate[i - 1]);
                float gyC = Math.abs(candidate[i + w] - candidate[i - w]);

                float gradB = gxB + gyB;
                float gradC = gxC + gyC;

                if (gradB >= 16f) {
                    edgeGain += gradC;
                } else if (gradB < 6f) {
                    flatNoisePenalty += Math.abs( gradC - gradB ) * 3.5;
                }

                float minB = Math.min(base[i], Math.min(Math.min(base[i - 1], base[i + 1]), Math.min(base[i - w], base[i + w])));
                float maxB = Math.max(base[i], Math.max(Math.max(base[i - 1], base[i + 1]), Math.max(base[i - w], base[i + w])));
                if (candidate[i] > maxB + 3f) ringingPenalty += (candidate[i] - maxB) * 4.0;
                if (candidate[i] < minB - 3f) ringingPenalty += (minB - candidate[i]) * 4.0;
                n++;
            }
        }
        return n > 0 ? (edgeGain - ringingPenalty - flatNoisePenalty) / n : 0.0;
    }

    /**
     * Region-Aware Adaptive Sharpening:
     * Classifies each pixel into Strong Edge, Fine Texture, or Flat/Noise and applies tailored gain
     * with strict anti-halo overshoot clamping.
     */
    private static void applyRegionAwareSharpening(
            int[] dstPixels,
            int[] basePixels,
            float[] deconvLum,
            float[] baseLum,
            int w,
            int h,
            float strength) {

        float edgeGain = Math.min(1.20f, strength * 1.35f);
        float textureGain = Math.min(0.85f, strength * 1.05f);
        float microContrastGain = Math.min(0.38f, strength * 0.45f);

        for (int y = 2; y < h - 2; y++) {
            int row = y * w;
            for (int x = 2; x < w - 2; x++) {
                int i = row + x;
                float c = deconvLum[i];
                float u = deconvLum[i - w], d = deconvLum[i + w];
                float l = deconvLum[i - 1], r = deconvLum[i + 1];
                float ul = deconvLum[i - w - 1], ur = deconvLum[i - w + 1];
                float dl = deconvLum[i + w - 1], dr = deconvLum[i + w + 1];

                float gx = r - l;
                float gy = d - u;
                float gradMag = (float) Math.hypot(gx, gy);

                // Region 1: Flat / Smooth surface or low-amplitude sensor noise -> zero sharpening
                if (gradMag < 5.5f) continue;

                float blur3 = (4f * c + 2f * (u + d + l + r) + (ul + ur + dl + dr)) * 0.0625f;
                float fineDetail = c - blur3;

                float outer5 = (deconvLum[i - 2 * w] + deconvLum[i + 2 * w] + deconvLum[i - 2] + deconvLum[i + 2]
                        + deconvLum[i - 2 * w - 2] + deconvLum[i - 2 * w + 2]
                        + deconvLum[i + 2 * w - 2] + deconvLum[i + 2 * w + 2]) * 0.125f;
                float midDetail = blur3 - outer5;

                // Distinguish strong edge (high directional gradient) vs fine isotropic texture
                boolean isStrongEdge = gradMag >= 32f;
                float activeFineGain = isStrongEdge ? edgeGain : textureGain;
                float noiseGate = Math.min(1.0f, (gradMag - 5.5f) / 14.0f);

                float deconvDelta = c - baseLum[i];
                float totalDelta = deconvDelta + (fineDetail * activeFineGain + midDetail * microContrastGain) * noiseGate;

                // Strict anti-halo clamp relative to original base neighborhood extrema
                float bC = baseLum[i];
                float bMin = Math.min(bC, Math.min(Math.min(baseLum[i - 1], baseLum[i + 1]), Math.min(baseLum[i - w], baseLum[i + w])));
                float bMax = Math.max(bC, Math.max(Math.max(baseLum[i - 1], baseLum[i + 1]), Math.max(baseLum[i - w], baseLum[i + w])));
                float haloMargin = isStrongEdge ? Math.max(4f, (bMax - bMin) * 0.16f) : Math.max(5f, (bMax - bMin) * 0.24f);

                float newLum = Math.max(bMin - haloMargin, Math.min(bMax + haloMargin, bC + totalDelta));
                int deltaInt = Math.round(newLum - bC);

                int orig = basePixels[i];
                int rr = clamp255(((orig >> 16) & 0xFF) + deltaInt);
                int gg = clamp255(((orig >> 8) & 0xFF) + deltaInt);
                int bb = clamp255((orig & 0xFF) + deltaInt);
                dstPixels[i] = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
            }
        }
    }

    /**
     * Measures ringing / halo overshoot relative to local 3x3 extrema of the pre-sharpened image.
     */
    public static float computeRingingMetric(int[] processed, int[] base, int w, int h) {
        if (processed == null || base == null || w < 6 || h < 6) return 0f;
        int step = Math.max(1, Math.min(w, h) / 96);
        int edgePixels = 0;
        double overshootSum = 0.0;

        for (int y = 2; y < h - 2; y += step) {
            int row = y * w;
            for (int x = 2; x < w - 2; x += step) {
                int i = row + x;
                int bC = lum(base[i]);
                int bL = lum(base[i - 1]), bR = lum(base[i + 1]);
                int bU = lum(base[i - w]), bD = lum(base[i + w]);
                int span = Math.max(Math.abs(bR - bL), Math.abs(bD - bU));
                if (span < 20) continue;

                edgePixels++;
                int bMin = Math.min(bC, Math.min(Math.min(bL, bR), Math.min(bU, bD)));
                int bMax = Math.max(bC, Math.max(Math.max(bL, bR), Math.max(bU, bD)));
                int pC = lum(processed[i]);
                int allow = Math.max(4, span / 5);
                if (pC > bMax + allow) {
                    overshootSum += (pC - (bMax + allow)) / (double) Math.max(16, span);
                } else if (pC < bMin - allow) {
                    overshootSum += ((bMin - allow) - pC) / (double) Math.max(16, span);
                }
            }
        }
        return edgePixels > 0 ? (float) Math.min(1.0, overshootSum / edgePixels) : 0f;
    }

    /**
     * Measures artificial high-frequency energy amplification (hallucinated texture / oversharpening).
     */
    public static float computeOversharpenMetric(int[] processed, int[] base, int w, int h) {
        if (processed == null || base == null || w < 6 || h < 6) return 0f;
        int step = Math.max(1, Math.min(w, h) / 96);
        double lapProc = 0.0;
        double lapBase = 0.0;
        for (int y = 2; y < h - 2; y += step) {
            int row = y * w;
            for (int x = 2; x < w - 2; x += step) {
                int i = row + x;
                int lp = Math.abs(4 * lum(processed[i]) - lum(processed[i - 1]) - lum(processed[i + 1]) - lum(processed[i - w]) - lum(processed[i + w]));
                int lb = Math.abs(4 * lum(base[i]) - lum(base[i - 1]) - lum(base[i + 1]) - lum(base[i - w]) - lum(base[i + w]));
                lapProc += lp;
                lapBase += lb;
            }
        }
        if (lapBase < 1e-3) return 0f;
        double ratio = lapProc / lapBase;
        // Ratio up to 1.85 is natural detail restoration; above 1.85 indicates progressive oversharpening
        return (float) Math.max(0.0, Math.min(1.0, (ratio - 1.85) / 1.5));
    }

    /**
     * Measures structural ghosting / double-edge divergence between processed output and reference frame.
     */
    public static float computeGhostingMetric(int[] processed, int[] reference, int w, int h) {
        if (processed == null || reference == null || w < 6 || h < 6 || processed.length != reference.length) return 0f;
        int step = Math.max(1, Math.min(w, h) / 96);
        int ghosts = 0;
        int total = 0;
        for (int y = 2; y < h - 2; y += step) {
            int row = y * w;
            for (int x = 2; x < w - 2; x += step) {
                int i = row + x;
                int diff = Math.abs(lum(processed[i]) - lum(reference[i]));
                if (diff > 38) ghosts++;
                total++;
            }
        }
        return total > 0 ? (float) ghosts / total : 0f;
    }

    private static int lum(int argb) {
        return (((argb >> 16) & 0xFF) * 77 + ((argb >> 8) & 0xFF) * 150 + (argb & 0xFF) * 29) >> 8;
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private DeconvolutionEngine() {}
}
