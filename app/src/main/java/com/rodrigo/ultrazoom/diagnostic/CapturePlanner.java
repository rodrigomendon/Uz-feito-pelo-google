package com.rodrigo.ultrazoom.diagnostic;

import java.util.Locale;

/**
 * Adaptive Computational Photography Capture & Exposure Planner.
 *
 * Implements:
 * - Section 8: Motion Blur / Exposure Policy (zoom + focal length + motion + OIS + ISO + shutter speed)
 * - Section 11: Multi-Frame HDR Scene Detection & Exposure Bracketing (-2 EV, 0 EV, +2 EV)
 * - Section 12: Low-Light / Night Computational Mode adaptation
 * - Section 13: Adaptive Computational Zoom Regimes (1x-2x, 2x-5x, 5x-10x, 10x-20x, 20x-30x)
 * - Section 14: Honest HyperZoom Useful Ceiling Estimator (determines maximum zoom with real detail gain)
 * - Section 22 & 23: Smart Capture Frame Count & Tripod / Static Stability Detector
 */
public final class CapturePlanner {

    public enum ZoomRegime {
        NATIVE_1X_2X("1×–2× NATIVO", "Prioridade à fidelidade nativa do sensor 12.6MP 4:3"),
        CROP_2X_5X("2×–5× CROP+FUSÃO", "Crop óptico/sensor + fusão multi-frame moderada"),
        TELE_5X_10X("5×–10× TELE MULTI-FRAME", "Limite óptico/hardware + máxima fusão sub-pixel"),
        SUPER_RES_10X_20X("10×–20× HYPERZOOM SR", "Hardware 10× + Drizzle Sub-Pixel + Fluxo 4×3"),
        EXTREME_SR_20X_30X("20×–30× EXTREME SR", "Hardware 10× + Drizzle + Deconvolução PSF RL");

        public final String label;
        public final String description;

        ZoomRegime(String label, String description) {
            this.label = label;
            this.description = description;
        }
    }

    public enum StabilityLevel {
        TRIPOD("TRIPÉ / ESTÁTICO"),
        STABLE("MÃO FIRME"),
        MODERATE_HANDHELD("MÃO MODERADA"),
        HIGH_MOTION("TREMOR ALTO");

        public final String label;

        StabilityLevel(String label) {
            this.label = label;
        }
    }

    public static final class CapturePlan {
        public final ZoomRegime regime;
        public final StabilityLevel stability;
        public final int targetFrames;
        public final int interFrameDelayMs;
        public final boolean hdrBracketingActive;
        public final int[] evBrackets;
        public final int recommendedAeCompEv;
        public final long maxSafeShutterNs;
        public final float effectiveMaxUsefulZoom;
        public final float srFactor;
        public final String planTelemetry;

        public CapturePlan(
                ZoomRegime regime,
                StabilityLevel stability,
                int targetFrames,
                int interFrameDelayMs,
                boolean hdrBracketingActive,
                int[] evBrackets,
                int recommendedAeCompEv,
                long maxSafeShutterNs,
                float effectiveMaxUsefulZoom,
                float srFactor,
                String planTelemetry) {
            this.regime = regime;
            this.stability = stability;
            this.targetFrames = targetFrames;
            this.interFrameDelayMs = interFrameDelayMs;
            this.hdrBracketingActive = hdrBracketingActive;
            this.evBrackets = evBrackets;
            this.recommendedAeCompEv = recommendedAeCompEv;
            this.maxSafeShutterNs = maxSafeShutterNs;
            this.effectiveMaxUsefulZoom = effectiveMaxUsefulZoom;
            this.srFactor = srFactor;
            this.planTelemetry = planTelemetry;
        }
    }

    public static ZoomRegime classifyRegime(float totalZoom, float maxHardwareZoom) {
        float hw = Math.max(1.0f, maxHardwareZoom);
        if (totalZoom >= 20.0f) return ZoomRegime.EXTREME_SR_20X_30X;
        if (totalZoom > hw + 0.05f) return ZoomRegime.SUPER_RES_10X_20X;
        if (totalZoom >= 5.0f) return ZoomRegime.TELE_5X_10X;
        if (totalZoom >= 2.0f) return ZoomRegime.CROP_2X_5X;
        return ZoomRegime.NATIVE_1X_2X;
    }

    /**
     * Estimates scene and hand stability from recent preview frame motion, shutter speed, zoom, and OIS.
     */
    public static StabilityLevel estimateSceneStability(
            float recentShiftPx,
            long exposureTimeNs,
            float totalZoom,
            boolean oisActive) {
        float expMs = exposureTimeNs > 0 ? (exposureTimeNs / 1_000_000f) : 14f;
        float effectiveMotion = recentShiftPx * (oisActive ? 0.78f : 1.15f);
        if (effectiveMotion <= 0.22f && expMs <= 35f) {
            return StabilityLevel.TRIPOD;
        }
        if (effectiveMotion >= 1.85f || (totalZoom >= 15f && expMs >= 28f && !oisActive)) {
            return StabilityLevel.HIGH_MOTION;
        }
        if (effectiveMotion <= 0.65f) {
            return StabilityLevel.STABLE;
        }
        return StabilityLevel.MODERATE_HANDHELD;
    }

    /**
     * Detects whether the current preview histogram warrants multi-exposure HDR bracketing.
     */
    public static boolean detectHdrScene(
            float highlightClipFraction,
            float shadowCrushFraction,
            float dynamicRangeStops) {
        return (highlightClipFraction >= 0.03f && shadowCrushFraction >= 0.05f)
                || dynamicRangeStops >= 9.4f;
    }

    /**
     * Computes the honest maximum zoom where real spatial information can still be recovered
     * given hardware zoom limit, current stability, ISO noise, and OIS state.
     */
    public static float computeUsefulZoomCeiling(
            float maxHardwareZoom,
            StabilityLevel stability,
            int iso,
            boolean oisActive) {
        float hw = Math.max(1.0f, maxHardwareZoom);
        float baseSrGain;
        switch (stability) {
            case TRIPOD:
                baseSrGain = 2.75f; // Up to ~27.5x on 10x hardware when on tripod/support
                break;
            case STABLE:
                baseSrGain = oisActive ? 2.25f : 1.85f; // ~22.5x handheld with OIS
                break;
            case MODERATE_HANDHELD:
                baseSrGain = oisActive ? 1.85f : 1.50f; // ~18.5x moderate handheld
                break;
            default:
                baseSrGain = oisActive ? 1.35f : 1.15f; // ~13.5x under high motion
                break;
        }

        // Penalize high ISO noise floor where sensor shot noise swamps high frequencies
        float isoPenalty = 1.0f;
        if (iso > 1600) isoPenalty = 0.72f;
        else if (iso > 800) isoPenalty = 0.85f;
        else if (iso > 400) isoPenalty = 0.94f;

        float usefulSr = Math.max(1.10f, Math.min(3.0f, baseSrGain * isoPenalty));
        return Math.round(hw * usefulSr * 10f) / 10f;
    }

    /**
     * Builds the complete adaptive capture & processing plan for the current shot.
     */
    public static CapturePlan planCapture(
            float requestedZoom,
            float maxHardwareZoom,
            float focalLengthMm,
            long currentExposureNs,
            int currentIso,
            boolean oisActive,
            StabilityLevel stability,
            boolean nightModeRequested,
            boolean hdrDetected,
            int maxReaderImages,
            int workW,
            int workH) {

        float hwZoom = Math.min(Math.max(1.0f, maxHardwareZoom), Math.max(1.0f, requestedZoom));
        float srFactor = requestedZoom > hwZoom ? (requestedZoom / hwZoom) : 1.0f;
        ZoomRegime regime = classifyRegime(requestedZoom, maxHardwareZoom);

        // Reciprocal rule for focal length + OIS stop benefit
        float eqFocalMm = Math.max(24f, (focalLengthMm > 0 ? focalLengthMm : 4.5f) * 5.2f * requestedZoom);
        float oisFactor = oisActive ? 3.5f : 1.0f;
        long maxSafeShutterNs = Math.round(1_000_000_000.0 / Math.max(35.0, eqFocalMm / oisFactor));
        maxSafeShutterNs = Math.max(2_000_000L, Math.min(33_333_333L, maxSafeShutterNs));

        // Motion Blur / Exposure Policy (Section 8):
        // If current shutter is slower than maxSafeShutterNs at medium/high zoom, recommend negative AE EV
        // so the camera HAL chooses a faster shutter speed to freeze edges, recovering SNR via multi-frame fusion.
        int recommendedAeEv = 0;
        if (!nightModeRequested && requestedZoom >= 4.0f && currentExposureNs > maxSafeShutterNs * 1.35) {
            recommendedAeEv = (currentExposureNs > maxSafeShutterNs * 2.2 || stability == StabilityLevel.HIGH_MOTION)
                    ? -2
                    : -1;
        }

        boolean isHdrActive = !nightModeRequested
                && hdrDetected
                && srFactor <= 1.35f
                && stability != StabilityLevel.HIGH_MOTION;

        int desiredFrames;
        int delayMs;
        if (nightModeRequested) {
            desiredFrames = stability == StabilityLevel.HIGH_MOTION ? 4 : 5;
            delayMs = 80;
        } else if (regime == ZoomRegime.EXTREME_SR_20X_30X || regime == ZoomRegime.SUPER_RES_10X_20X) {
            desiredFrames = (stability == StabilityLevel.TRIPOD || stability == StabilityLevel.STABLE) ? 6 : 5;
            delayMs = 38;
        } else if (isHdrActive) {
            desiredFrames = 4;
            delayMs = 45;
        } else if (regime == ZoomRegime.TELE_5X_10X || regime == ZoomRegime.CROP_2X_5X) {
            desiredFrames = stability == StabilityLevel.HIGH_MOTION ? 3 : 4;
            delayMs = 40;
        } else {
            desiredFrames = stability == StabilityLevel.HIGH_MOTION ? 2 : 3;
            delayMs = 35;
        }

        int safeFrames = MemoryPolicy.safeTargetFrames(
                desiredFrames,
                Math.max(2, maxReaderImages),
                8,
                Math.max(64, workW),
                Math.max(64, workH),
                MemoryPolicy.FULL_RES_FRAME_BUDGET_BYTES);

        int[] evBrackets = new int[safeFrames];
        if (isHdrActive && safeFrames >= 3) {
            evBrackets[0] = 0;
            evBrackets[1] = -2;
            evBrackets[2] = 2;
            for (int i = 3; i < safeFrames; i++) evBrackets[i] = 0;
        }

        float usefulMax = computeUsefulZoomCeiling(maxHardwareZoom, stability, currentIso, oisActive);
        String telemetry = String.format(
                Locale.US,
                "%s • %s • %dF%s • Teto útil %.1f× • EV %+d",
                regime.label,
                stability.label,
                safeFrames,
                isHdrActive ? " (HDR -2/0/+2)" : "",
                usefulMax,
                recommendedAeEv);

        return new CapturePlan(
                regime,
                stability,
                safeFrames,
                delayMs,
                isHdrActive,
                evBrackets,
                recommendedAeEv,
                maxSafeShutterNs,
                usefulMax,
                srFactor,
                telemetry);
    }

    private CapturePlanner() {}
}
