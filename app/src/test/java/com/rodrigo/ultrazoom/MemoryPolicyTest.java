package com.rodrigo.ultrazoom;

import com.rodrigo.ultrazoom.diagnostic.MemoryPolicy;
import org.junit.Test;
import static org.junit.Assert.*;

public class MemoryPolicyTest {
    @Test
    public void targetNeverExceedsReader() {
        assertEquals(4, MemoryPolicy.safeTargetFrames(5, 4, 4));
        assertEquals(3, MemoryPolicy.safeTargetFrames(3, 6, 4));
    }

    @Test
    public void byteEstimate() {
        assertEquals(48L * 1024 * 1024, MemoryPolicy.estimatedArgbBytes(4096, 3072, 1));
    }

    @Test
    public void memoryBudgetCapsFullResolutionFrames() {
        // 4096x3072 ARGB_8888 is 48 MiB per frame. With a 96 MiB budget, at most 2 full frames fit.
        long budget96MiB = 96L * 1024L * 1024L;
        assertEquals(2, MemoryPolicy.safeTargetFrames(5, 6, 5, 4096, 3072, budget96MiB));
        // With inSampleSize >= 2 recommended when 4 frames of 4096x3072 exceed 64 MiB
        assertTrue(MemoryPolicy.recommendedInSampleSize(4096, 3072, 4, 64L * 1024L * 1024L) >= 2);
    }
}
