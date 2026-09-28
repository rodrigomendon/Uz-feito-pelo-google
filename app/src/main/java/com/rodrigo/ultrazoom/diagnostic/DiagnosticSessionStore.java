package com.rodrigo.ultrazoom.diagnostic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Persistent diagnostic session store for:
 * - Layer B (Observed preview frame pairs z1 -> z2 and per-level evidence)
 * - Layer C (Inferred cross-level evidence from voluntarily captured JPEGs)
 *
 * Guarantees:
 * - UNKNOWN never revokes CONFIRMED or PARTIAL.
 * - FAIL is absorbing and causes global REVOKED when combined.
 * - Crop + upscale without real fine detail preservation is rejected in Layer C.
 */
public final class DiagnosticSessionStore {
    public static final int MAX_SAMPLES_PER_LEVEL = 100;
    public static final int MAX_TOTAL_SAMPLES = 1000;
    public static final float MIN_OVERLAP_RATIO = 0.20f;

    public static final class LevelRecord {
        public final float zoom;
        public ZoomConfidenceEngine.State state = ZoomConfidenceEngine.State.UNKNOWN;
        public int samples;
        public int good;
        public int contradictions;

        public LevelRecord(float zoom) {
            this.zoom = zoom;
        }
    }

    public static final class PairRecord {
        public final float fromZoom;
        public final float toZoom;
        public ZoomConfidenceEngine.State state = ZoomConfidenceEngine.State.UNKNOWN;
        public int samples;
        public int good;
        public int contradictions;
        public float lastExpectedScale;
        public float lastObservedScale = Float.NaN;
        public float lastConfidence;

        public PairRecord(float fromZoom, float toZoom) {
            this.fromZoom = fromZoom;
            this.toZoom = toZoom;
            this.lastExpectedScale = toZoom / Math.max(0.01f, fromZoom);
        }

        public String key() {
            return pairKey(fromZoom, toZoom);
        }
    }

    public static final class JpegRecord {
        public final float zoom;
        public int width;
        public int height;
        public float fineDetail;
        public float coarseDetail;
        public float acutanceRatio;
        public int edgeCount;
        public byte[] thumbGray;
        public int thumbW;
        public int thumbH;
        public ZoomConfidenceEngine.State state = ZoomConfidenceEngine.State.UNKNOWN;

        public JpegRecord(float zoom) {
            this.zoom = zoom;
        }
    }

    private final Map<Float, LevelRecord> levels = new LinkedHashMap<Float, LevelRecord>();
    private final Map<String, PairRecord> pairs = new LinkedHashMap<String, PairRecord>();
    private final Map<Float, JpegRecord> jpegs = new LinkedHashMap<Float, JpegRecord>();

    private int totalSamples;
    private int totalPairsStored;
    private float lastSampleZoom = 1f;
    private float lastObservedScale = Float.NaN;
    private float lastObservedConfidence = 0f;
    private ZoomConfidenceEngine.State layerBState = ZoomConfidenceEngine.State.UNKNOWN;
    private ZoomConfidenceEngine.State layerCState = ZoomConfidenceEngine.State.UNKNOWN;

    public static float normalizeZoom(float z) {
        return Math.round(Math.max(0.5f, z) * 10f) / 10f;
    }

    public static String pairKey(float z1, float z2) {
        float low = normalizeZoom(Math.min(z1, z2));
        float high = normalizeZoom(Math.max(z1, z2));
        return String.format(Locale.US, "%.1f->%.1f", low, high);
    }

    public static boolean hasSufficientOverlap(float z1, float z2) {
        float low = Math.min(z1, z2);
        float high = Math.max(z1, z2);
        if (low < 0.99f || high <= low) return false;
        float expected = high / low;
        if (expected < 1.15f) return false;
        return (1f / expected) >= MIN_OVERLAP_RATIO;
    }

    /**
     * Records an observed preview comparison between z1 and z2.
     * Inconclusive observations (NaN or low confidence) are treated as UNKNOWN
     * and never revoke existing CONFIRMED or PARTIAL states.
     */
    public synchronized ZoomConfidenceEngine.State recordObservedPair(
            float z1, float z2, float observedScale, float confidence) {
        totalSamples++;
        lastSampleZoom = z2;
        lastObservedScale = observedScale;
        lastObservedConfidence = confidence;

        if (!hasSufficientOverlap(z1, z2) || totalPairsStored >= MAX_TOTAL_SAMPLES) {
            recomputeLayerBState(normalizeZoom(Math.max(z1, z2)));
            return layerBState;
        }

        float low = normalizeZoom(Math.min(z1, z2));
        float high = normalizeZoom(Math.max(z1, z2));
        float expected = high / Math.max(0.1f, low);

        LevelRecord level = getOrCreateLevel(high);
        if (level.samples >= MAX_SAMPLES_PER_LEVEL) {
            recomputeLayerBState(high);
            return layerBState;
        }

        String pKey = pairKey(low, high);
        PairRecord pair = pairs.get(pKey);
        if (pair == null) {
            pair = new PairRecord(low, high);
            pairs.put(pKey, pair);
        }

        // Inconclusive evidence -> UNKNOWN. Never increment contradictions or downgrade existing state.
        if (Float.isNaN(observedScale) || confidence < 0.45f) {
            recomputeLayerBState(high);
            return layerBState;
        }

        totalPairsStored++;
        level.samples++;
        pair.samples++;
        pair.lastExpectedScale = expected;
        pair.lastObservedScale = observedScale;
        pair.lastConfidence = confidence;

        float tol = Math.max(0.18f, expected * 0.12f);
        float contradictionThreshold = Math.max(0.28f, expected * 0.22f);

        ZoomConfidenceEngine.State obsState = ZoomConfidenceEngine.State.UNKNOWN;
        if (confidence >= 0.64f) {
            if (Math.abs(observedScale - expected) <= tol) {
                level.good++;
                pair.good++;
                if (pair.good >= 2 && pair.contradictions == 0) {
                    obsState = ZoomConfidenceEngine.State.CONFIRMED;
                } else {
                    obsState = ZoomConfidenceEngine.State.PARTIAL;
                }
            } else if (Math.abs(observedScale - expected) >= contradictionThreshold) {
                level.contradictions++;
                pair.contradictions++;
                if (pair.contradictions >= 2 || level.contradictions >= 2) {
                    obsState = ZoomConfidenceEngine.State.FAIL;
                }
            }
        } else if (Math.abs(observedScale - expected) <= tol) {
            obsState = ZoomConfidenceEngine.State.PARTIAL;
        }

        pair.state = ZoomConfidenceEngine.transition(pair.state, obsState);
        if (obsState == ZoomConfidenceEngine.State.FAIL) {
            level.state = ZoomConfidenceEngine.State.FAIL;
        } else if (level.good >= 2 && level.contradictions == 0) {
            level.state = ZoomConfidenceEngine.transition(level.state, ZoomConfidenceEngine.State.CONFIRMED);
        } else if (level.good > 0) {
            level.state = ZoomConfidenceEngine.transition(level.state, ZoomConfidenceEngine.State.PARTIAL);
        }

        recomputeLayerBState(high);
        return layerBState;
    }

    private void recomputeLayerBState(float activeLevel) {
        for (LevelRecord r : levels.values()) {
            if (r.state == ZoomConfidenceEngine.State.FAIL || r.state == ZoomConfidenceEngine.State.REVOKED) {
                layerBState = ZoomConfidenceEngine.State.FAIL;
                return;
            }
        }
        for (PairRecord r : pairs.values()) {
            if (r.state == ZoomConfidenceEngine.State.FAIL || r.state == ZoomConfidenceEngine.State.REVOKED) {
                layerBState = ZoomConfidenceEngine.State.FAIL;
                return;
            }
        }
        LevelRecord current = levels.get(normalizeZoom(activeLevel));
        if (current != null && current.state != ZoomConfidenceEngine.State.UNKNOWN) {
            layerBState = ZoomConfidenceEngine.transition(layerBState, current.state);
            return;
        }
        boolean anyConfirmed = false, anyPartial = false;
        for (LevelRecord r : levels.values()) {
            if (r.state == ZoomConfidenceEngine.State.CONFIRMED) anyConfirmed = true;
            else if (r.state == ZoomConfidenceEngine.State.PARTIAL) anyPartial = true;
        }
        for (PairRecord r : pairs.values()) {
            if (r.state == ZoomConfidenceEngine.State.CONFIRMED) anyConfirmed = true;
            else if (r.state == ZoomConfidenceEngine.State.PARTIAL) anyPartial = true;
        }
        if (anyConfirmed) {
            layerBState = ZoomConfidenceEngine.transition(layerBState, ZoomConfidenceEngine.State.CONFIRMED);
        } else if (anyPartial) {
            layerBState = ZoomConfidenceEngine.transition(layerBState, ZoomConfidenceEngine.State.PARTIAL);
        }
    }

    /**
     * Records diagnostic evidence from a JPEG voluntarily captured by the user.
     * Compares across zoom levels to verify real detail vs artificial crop+upscale.
     */
    public synchronized ZoomConfidenceEngine.State recordJpegEvidence(
            float zoom, int width, int height,
            float fineDetail, float coarseDetail, int edgeCount,
            byte[] thumbGray, int thumbW, int thumbH) {
        float key = normalizeZoom(zoom);
        JpegRecord rec = jpegs.get(key);
        if (rec == null) {
            rec = new JpegRecord(key);
            jpegs.put(key, rec);
        }
        rec.width = width;
        rec.height = height;
        rec.fineDetail = fineDetail;
        rec.coarseDetail = Math.max(1e-3f, coarseDetail);
        rec.acutanceRatio = fineDetail / rec.coarseDetail;
        rec.edgeCount = edgeCount;
        rec.thumbGray = thumbGray;
        rec.thumbW = thumbW;
        rec.thumbH = thumbH;

        if (layerCState == ZoomConfidenceEngine.State.FAIL || layerCState == ZoomConfidenceEngine.State.REVOKED) {
            return layerCState;
        }

        // Compare against all other captured JPEG zoom levels (supports up to 12x hardware zoom span)
        for (JpegRecord other : jpegs.values()) {
            if (Math.abs(other.zoom - rec.zoom) < 0.15f) continue;
            JpegRecord low = other.zoom < rec.zoom ? other : rec;
            JpegRecord high = other.zoom < rec.zoom ? rec : other;
            float expected = high.zoom / Math.max(0.1f, low.zoom);
            if (expected < 1.15f || expected > 12.0f) continue;

            // Only evaluate detail when both JPEGs have meaningful scene structure
            if (low.edgeCount < 18 || high.edgeCount < 18 || low.fineDetail < 4f) {
                continue;
            }

            boolean scaleMatched = false;
            if (expected <= 4.2f && low.thumbGray != null && high.thumbGray != null &&
                low.thumbW >= 32 && low.thumbH >= 32 && high.thumbW >= 32 && high.thumbH >= 32) {
                ScaleEstimator.Result sr = ScaleEstimator.estimate(
                        low.thumbGray, low.thumbW, low.thumbH,
                        high.thumbGray, high.thumbW, high.thumbH,
                        Math.max(1f, expected * 0.65f), Math.min(5f, expected * 1.35f));
                if (!Float.isNaN(sr.scale) && sr.confidence >= 0.64f) {
                    float tol = Math.max(0.20f, expected * 0.14f);
                    float failTol = Math.max(0.30f, expected * 0.24f);
                    if (Math.abs(sr.scale - expected) >= failTol) {
                        high.state = ZoomConfidenceEngine.State.FAIL;
                        layerCState = ZoomConfidenceEngine.State.FAIL;
                        return layerCState;
                    }
                    if (Math.abs(sr.scale - expected) <= tol) {
                        scaleMatched = true;
                    }
                }
            }

            // Detect crop + bilinear upscale:
            // Pure crop+upscale smears 1px transitions over `expected` pixels, causing fineDetail
            // and acutanceRatio (fine/coarse) to collapse sharply relative to the 1x JPEG.
            float acutanceRetention = high.acutanceRatio / Math.max(1e-3f, low.acutanceRatio);
            float fineRetention = high.fineDetail / Math.max(1e-3f, low.fineDetail);
            float upscaleCollapseLimit = Math.min(0.42f, 0.58f / Math.min(5.0f, expected));

            if (expected >= 1.5f && (acutanceRetention < upscaleCollapseLimit || fineRetention < 0.22f)) {
                // Severe loss of native pixel acutance typical of software crop+upscale
                high.state = ZoomConfidenceEngine.State.FAIL;
                layerCState = ZoomConfidenceEngine.State.FAIL;
                return layerCState;
            }

            boolean detailPreserved = acutanceRetention >= Math.max(0.44f, 0.76f / Math.min(5.0f, expected))
                    && fineRetention >= 0.42f
                    && high.acutanceRatio >= 0.30f;

            if (detailPreserved && (scaleMatched || fineRetention >= 0.65f)) {
                high.state = ZoomConfidenceEngine.transition(high.state, ZoomConfidenceEngine.State.CONFIRMED);
                layerCState = ZoomConfidenceEngine.transition(layerCState, ZoomConfidenceEngine.State.CONFIRMED);
            } else if (detailPreserved) {
                high.state = ZoomConfidenceEngine.transition(high.state, ZoomConfidenceEngine.State.PARTIAL);
                layerCState = ZoomConfidenceEngine.transition(layerCState, ZoomConfidenceEngine.State.PARTIAL);
            }
        }

        return layerCState;
    }

    private LevelRecord getOrCreateLevel(float z) {
        float key = normalizeZoom(z);
        LevelRecord r = levels.get(key);
        if (r == null) {
            r = new LevelRecord(key);
            levels.put(key, r);
        }
        return r;
    }

    public synchronized ZoomConfidenceEngine.State getLayerBState() {
        return layerBState;
    }

    public synchronized ZoomConfidenceEngine.State getLayerCState() {
        return layerCState;
    }

    public synchronized ZoomConfidenceEngine.State getLevelState(float zoom) {
        LevelRecord r = levels.get(normalizeZoom(zoom));
        return r == null ? ZoomConfidenceEngine.State.UNKNOWN : r.state;
    }

    public synchronized ZoomConfidenceEngine.State getPairState(float z1, float z2) {
        PairRecord r = pairs.get(pairKey(z1, z2));
        return r == null ? ZoomConfidenceEngine.State.UNKNOWN : r.state;
    }

    public synchronized Map<Float, LevelRecord> getLevels() {
        return Collections.unmodifiableMap(new LinkedHashMap<Float, LevelRecord>(levels));
    }

    public synchronized Map<String, PairRecord> getPairs() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, PairRecord>(pairs));
    }

    public synchronized Map<Float, JpegRecord> getJpegs() {
        return Collections.unmodifiableMap(new LinkedHashMap<Float, JpegRecord>(jpegs));
    }

    public synchronized int getTotalSamples() { return totalSamples; }
    public synchronized int getTotalPairsStored() { return totalPairsStored; }
    public synchronized float getLastSampleZoom() { return lastSampleZoom; }
    public synchronized float getLastObservedScale() { return lastObservedScale; }
    public synchronized float getLastObservedConfidence() { return lastObservedConfidence; }

    public synchronized int getConfirmedLevelsCount() {
        int c = 0;
        for (LevelRecord r : levels.values()) if (r.state == ZoomConfidenceEngine.State.CONFIRMED) c++;
        return c;
    }

    public synchronized int getPartialLevelsCount() {
        int c = 0;
        for (LevelRecord r : levels.values()) if (r.state == ZoomConfidenceEngine.State.PARTIAL) c++;
        return c;
    }

    public synchronized int getRevokedLevelsCount() {
        int c = 0;
        for (LevelRecord r : levels.values()) {
            if (r.state == ZoomConfidenceEngine.State.FAIL || r.state == ZoomConfidenceEngine.State.REVOKED) c++;
        }
        return c;
    }

    public synchronized String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append("HEADER,")
          .append(totalSamples).append(',')
          .append(totalPairsStored).append(',')
          .append(String.format(Locale.US, "%.3f", lastSampleZoom)).append(',')
          .append(Float.isNaN(lastObservedScale) ? -1f : lastObservedScale).append(',')
          .append(String.format(Locale.US, "%.3f", lastObservedConfidence)).append(',')
          .append(layerBState.name()).append(',')
          .append(layerCState.name()).append('\n');

        for (LevelRecord r : levels.values()) {
            sb.append("LEVEL,")
              .append(String.format(Locale.US, "%.1f", r.zoom)).append(',')
              .append(r.state.name()).append(',')
              .append(r.samples).append(',')
              .append(r.good).append(',')
              .append(r.contradictions).append('\n');
        }
        for (PairRecord p : pairs.values()) {
            sb.append("PAIR,")
              .append(String.format(Locale.US, "%.1f", p.fromZoom)).append(',')
              .append(String.format(Locale.US, "%.1f", p.toZoom)).append(',')
              .append(p.state.name()).append(',')
              .append(p.samples).append(',')
              .append(p.good).append(',')
              .append(p.contradictions).append(',')
              .append(Float.isNaN(p.lastObservedScale) ? -1f : p.lastObservedScale).append(',')
              .append(String.format(Locale.US, "%.3f", p.lastConfidence)).append('\n');
        }
        for (JpegRecord j : jpegs.values()) {
            sb.append("JPEG,")
              .append(String.format(Locale.US, "%.1f", j.zoom)).append(',')
              .append(j.width).append(',')
              .append(j.height).append(',')
              .append(String.format(Locale.US, "%.3f", j.fineDetail)).append(',')
              .append(String.format(Locale.US, "%.3f", j.coarseDetail)).append(',')
              .append(j.edgeCount).append(',')
              .append(j.state.name()).append('\n');
        }
        return sb.toString();
    }

    public synchronized void deserialize(String data) {
        levels.clear();
        pairs.clear();
        jpegs.clear();
        totalSamples = 0;
        totalPairsStored = 0;
        lastSampleZoom = 1f;
        lastObservedScale = Float.NaN;
        lastObservedConfidence = 0f;
        layerBState = ZoomConfidenceEngine.State.UNKNOWN;
        layerCState = ZoomConfidenceEngine.State.UNKNOWN;

        if (data == null || data.trim().isEmpty()) return;
        String[] lines = data.split("\\n");
        for (String line : lines) {
            String[] p = line.trim().split(",");
            if (p.length == 0) continue;
            try {
                if ("HEADER".equals(p[0]) && p.length >= 8) {
                    totalSamples = Integer.parseInt(p[1]);
                    totalPairsStored = Integer.parseInt(p[2]);
                    lastSampleZoom = Float.parseFloat(p[3]);
                    float obs = Float.parseFloat(p[4]);
                    lastObservedScale = obs < 0f ? Float.NaN : obs;
                    lastObservedConfidence = Float.parseFloat(p[5]);
                    layerBState = ZoomConfidenceEngine.fromLabel(p[6]);
                    layerCState = ZoomConfidenceEngine.fromLabel(p[7]);
                } else if ("LEVEL".equals(p[0]) && p.length >= 6) {
                    float z = normalizeZoom(Float.parseFloat(p[1]));
                    LevelRecord r = new LevelRecord(z);
                    r.state = ZoomConfidenceEngine.fromLabel(p[2]);
                    r.samples = Integer.parseInt(p[3]);
                    r.good = Integer.parseInt(p[4]);
                    r.contradictions = Integer.parseInt(p[5]);
                    levels.put(z, r);
                } else if ("PAIR".equals(p[0]) && p.length >= 9) {
                    float z1 = normalizeZoom(Float.parseFloat(p[1]));
                    float z2 = normalizeZoom(Float.parseFloat(p[2]));
                    PairRecord r = new PairRecord(z1, z2);
                    r.state = ZoomConfidenceEngine.fromLabel(p[3]);
                    r.samples = Integer.parseInt(p[4]);
                    r.good = Integer.parseInt(p[5]);
                    r.contradictions = Integer.parseInt(p[6]);
                    float obs = Float.parseFloat(p[7]);
                    r.lastObservedScale = obs < 0f ? Float.NaN : obs;
                    r.lastConfidence = Float.parseFloat(p[8]);
                    pairs.put(r.key(), r);
                } else if ("JPEG".equals(p[0]) && p.length >= 8) {
                    float z = normalizeZoom(Float.parseFloat(p[1]));
                    JpegRecord j = new JpegRecord(z);
                    j.width = Integer.parseInt(p[2]);
                    j.height = Integer.parseInt(p[3]);
                    j.fineDetail = Float.parseFloat(p[4]);
                    j.coarseDetail = Math.max(1e-3f, Float.parseFloat(p[5]));
                    j.acutanceRatio = j.fineDetail / j.coarseDetail;
                    j.edgeCount = Integer.parseInt(p[6]);
                    j.state = ZoomConfidenceEngine.fromLabel(p[7]);
                    jpegs.put(z, j);
                }
            } catch (Exception ignored) { }
        }
    }
}
