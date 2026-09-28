package com.rodrigo.ultrazoom.diagnostic;

import java.util.Locale;

/**
 * Per-Stage Computational Photography Performance & Memory Profiler.
 *
 * Implements Sections 19, 20, 28:
 * - Tracks execution time (ms) of every pipeline stage:
 *   qualityAnalysisMs, registrationMs, srReconstructionMs, chromaDenoiseMs, deconvSharpenMs, totalMs
 * - Tracks peak working memory (MB), active ROI dimensions, tile count, and CPU worker threads.
 */
public final class PerformanceProfiler {

    public static final class ProfileReport {
        public final long qualityAnalysisMs;
        public final long registrationMs;
        public final long srReconstructionMs;
        public final long chromaDenoiseMs;
        public final long deconvSharpenMs;
        public final long totalMs;
        public final int cpuThreadsUsed;
        public final int tilesProcessed;
        public final float workingBuffersMb;
        public final float jvmAllocatedMb;

        public ProfileReport(
                long qualityAnalysisMs,
                long registrationMs,
                long srReconstructionMs,
                long chromaDenoiseMs,
                long deconvSharpenMs,
                long totalMs,
                int cpuThreadsUsed,
                int tilesProcessed,
                float workingBuffersMb,
                float jvmAllocatedMb) {
            this.qualityAnalysisMs = qualityAnalysisMs;
            this.registrationMs = registrationMs;
            this.srReconstructionMs = srReconstructionMs;
            this.chromaDenoiseMs = chromaDenoiseMs;
            this.deconvSharpenMs = deconvSharpenMs;
            this.totalMs = totalMs;
            this.cpuThreadsUsed = cpuThreadsUsed;
            this.tilesProcessed = tilesProcessed;
            this.workingBuffersMb = workingBuffersMb;
            this.jvmAllocatedMb = jvmAllocatedMb;
        }

        public String toCompactSummary() {
            return String.format(
                    Locale.US,
                    "%dms (Reg %d • SR %d • Den %d • Dec %d) • %dT/%dTiles • Buf %.1fMB",
                    totalMs,
                    registrationMs,
                    srReconstructionMs,
                    chromaDenoiseMs,
                    deconvSharpenMs,
                    cpuThreadsUsed,
                    tilesProcessed,
                    workingBuffersMb);
        }
    }

    public static float currentJvmAllocatedMb() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        return used / (1024f * 1024f);
    }

    public static float estimateWorkingBufferMb(int frameCount, int roiW, int roiH) {
        // Each ARGB int[] buffer of size roiW * roiH takes 4 bytes per pixel + 2 output/working buffers
        long bytes = (long) (frameCount + 2) * roiW * roiH * 4L;
        return bytes / (1024f * 1024f);
    }

    private PerformanceProfiler() {}
}
