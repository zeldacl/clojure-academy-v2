package cn.li.mc1201.client.font.msdf;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Async LRU cache of STB SDF pixel buffers (no GPU atlas — glyphs upload via vanilla {@code FontSet.stitch}).
 */
public final class MsdfAtlas {

    public static final int DEFAULT_PX_RANGE = 8;
    public static final int MAX_CACHED_GLYPHS = 4096;
    /** Conservative outer pad so SDF edges are not clipped when cropped for stitch. */
    public static final int BAKE_PADDING =
            DEFAULT_PX_RANGE + (int) Math.ceil(MsdfTextFx.maxBakeFieldOffset() * DEFAULT_PX_RANGE) + 2;

    private final Map<Integer, MsdfEngine.MsdfPixels> bakedCache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<Integer, MsdfEngine.MsdfPixels> eldest) {
            return size() > MAX_CACHED_GLYPHS;
        }
    };
    private final ConcurrentHashMap<Integer, CompletableFuture<MsdfEngine.MsdfPixels>> pendingPixels =
            new ConcurrentHashMap<>();
    private final ExecutorService bakeExecutor = Executors.newSingleThreadExecutor(new BakeThreadFactory());

    public void prefetchGlyph(final MsdfFontFace face, final int glyphIndex) {
        if (glyphIndex == 0 || bakedCache.containsKey(glyphIndex)) {
            return;
        }
        pendingPixels.computeIfAbsent(glyphIndex, idx ->
                CompletableFuture.supplyAsync(
                        () -> MsdfEngine.generate(face, idx, DEFAULT_PX_RANGE, BAKE_PADDING),
                        bakeExecutor));
    }

    public MsdfEngine.MsdfPixels pixelsFor(final MsdfFontFace face, final int glyphIndex) {
        final MsdfEngine.MsdfPixels cached = bakedCache.get(glyphIndex);
        if (cached != null) {
            touchGlyph(glyphIndex);
            return cached;
        }
        final MsdfEngine.MsdfPixels pixels = resolvePixels(face, glyphIndex);
        bakedCache.put(glyphIndex, pixels);
        return pixels;
    }

    private void touchGlyph(final int glyphIndex) {
        final MsdfEngine.MsdfPixels slot = bakedCache.remove(glyphIndex);
        if (slot != null) {
            bakedCache.put(glyphIndex, slot);
        }
    }

    private MsdfEngine.MsdfPixels resolvePixels(final MsdfFontFace face, final int glyphIndex) {
        final CompletableFuture<MsdfEngine.MsdfPixels> pending = pendingPixels.remove(glyphIndex);
        if (pending != null) {
            if (pending.isDone() && !pending.isCompletedExceptionally()) {
                final MsdfEngine.MsdfPixels ready = pending.getNow(null);
                if (ready != null) {
                    return ready;
                }
            }
            pending.cancel(false);
        }
        return MsdfEngine.generate(face, glyphIndex, DEFAULT_PX_RANGE, BAKE_PADDING);
    }

    public int cachedGlyphCount() {
        return bakedCache.size();
    }

    public void shutdown() {
        bakeExecutor.shutdownNow();
        pendingPixels.clear();
        bakedCache.clear();
    }

    private static final class BakeThreadFactory implements ThreadFactory {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(final Runnable r) {
            final Thread thread = new Thread(r, "my_mod-msdf-bake-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
