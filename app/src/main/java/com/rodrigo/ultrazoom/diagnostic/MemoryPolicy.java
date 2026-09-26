package com.rodrigo.ultrazoom.diagnostic;

public final class MemoryPolicy {
    public static final long DEFAULT_FRAME_BUDGET_BYTES = 64L * 1024L * 1024L;

    public static int safeTargetFrames(int requested, int maxImages, int hardCap) {
        int cap = Math.max(1, Math.min(maxImages, hardCap));
        return Math.max(1, Math.min(requested, cap));
    }

    public static int safeTargetFrames(int requested, int maxImages, int hardCap, int width, int height, long budgetBytes) {
        int readerCap = safeTargetFrames(requested, maxImages, hardCap);
        long perFrame = Math.max(1L, estimatedArgbBytes(width, height, 1));
        long safeBudget = Math.max(perFrame, budgetBytes);
        int budgetCap = (int) Math.max(1L, Math.min((long) readerCap, safeBudget / perFrame));
        return Math.max(1, Math.min(readerCap, budgetCap));
    }

    public static long estimatedArgbBytes(int width, int height, int frames) {
        return Math.max(0L, width) * Math.max(0L, height) * 4L * Math.max(0, frames);
    }

    public static int recommendedInSampleSize(int width, int height, int frames, long budgetBytes) {
        int sample = 1;
        int f = Math.max(1, frames);
        long safeBudget = Math.max(16L * 1024L * 1024L, budgetBytes);
        while (estimatedArgbBytes(Math.max(1, width / sample), Math.max(1, height / sample), f) > safeBudget && sample < 8) {
            sample *= 2;
        }
        return sample;
    }

    private MemoryPolicy() {}
}
