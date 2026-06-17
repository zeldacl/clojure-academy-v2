package cn.li.mc1201.client.font.msdf;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Multi-page RGBA atlas for MSDF glyphs with shelf packing, async MTSDF generation,
 * and LRU glyph-slot eviction.
 */
public final class MsdfAtlas {

    public static final int PAGE_SIZE = 512;
    public static final int DEFAULT_PX_RANGE = 8;
    public static final int MAX_CACHED_GLYPHS = 4096;
    /** Conservative outer pad so bold/outline/glow never clip; stable per-glyph cache key. */
    public static final int BAKE_PADDING =
            DEFAULT_PX_RANGE + (int) Math.ceil(MsdfTextFx.maxBakeFieldOffset() * DEFAULT_PX_RANGE) + 2;
    private static final String NAMESPACE = "my_mod";

    private final TextureManager textureManager;
    private final List<AtlasPage> pages = new ArrayList<>();
    private final Map<Integer, BakedSlot> bakedCache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<Integer, BakedSlot> eldest) {
            return size() > MAX_CACHED_GLYPHS;
        }
    };
    private final ConcurrentHashMap<Integer, CompletableFuture<MsdfEngine.MsdfPixels>> pendingPixels =
            new ConcurrentHashMap<>();
    private final ExecutorService bakeExecutor = Executors.newSingleThreadExecutor(new BakeThreadFactory());

    public MsdfAtlas(final TextureManager textureManager) {
        this.textureManager = textureManager;
    }

    public record BakedSlot(
            ResourceLocation textureId,
            int pageIndex,
            float u0, float v0, float u1, float v1,
            float left, float right, float up, float down,
            int pixelWidth, int pixelHeight) {
    }

    public void prefetchGlyph(final MsdfFontFace face, final int glyphIndex) {
        if (glyphIndex == 0 || bakedCache.containsKey(glyphIndex)) {
            return;
        }
        pendingPixels.computeIfAbsent(glyphIndex, idx ->
                CompletableFuture.supplyAsync(
                        () -> MsdfEngine.generate(face, idx, DEFAULT_PX_RANGE, BAKE_PADDING),
                        bakeExecutor));
    }

    public BakedSlot bakeGlyph(final MsdfFontFace face, final int glyphIndex) {
        final BakedSlot cached = bakedCache.get(glyphIndex);
        if (cached != null) {
            touchGlyph(glyphIndex);
            return cached;
        }

        final MsdfEngine.MsdfPixels pixels = resolvePixels(face, glyphIndex);
        return uploadPixels(face, glyphIndex, pixels);
    }

    private void touchGlyph(final int glyphIndex) {
        final BakedSlot slot = bakedCache.remove(glyphIndex);
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

    private BakedSlot uploadPixels(
            final MsdfFontFace face,
            final int glyphIndex,
            final MsdfEngine.MsdfPixels pixels) {
        final int w = pixels.width;
        final int h = pixels.height;

        AtlasPage page = currentPageFor(w, h);
        if (page == null) {
            page = new AtlasPage(pages.size());
            pages.add(page);
        }

        final int x = page.cursorX;
        final int y = page.cursorY;
        page.upload(x, y, w, h, pixels.rgba);
        page.advanceCursor(w, h);

        final float invW = 1.0f / page.texture.getPixels().getWidth();
        final float invH = 1.0f / page.texture.getPixels().getHeight();
        final float u0 = x * invW;
        final float v0 = y * invH;
        final float u1 = (x + w) * invW;
        final float v1 = (y + h) * invH;

        final float lsb = face.getLeftSideBearing(glyphIndex);
        final float ascent = face.ascent() * face.scale();
        final int pad = BAKE_PADDING;

        final float left = lsb - pad;
        final float right = w - pad - lsb;
        final float up = ascent + pad;
        final float down = h - pad - ascent;

        final BakedSlot slot = new BakedSlot(
                page.textureId, page.index,
                u0, v0, u1, v1,
                left, right, up, down,
                w, h);
        bakedCache.put(glyphIndex, slot);
        return slot;
    }

    public int cachedGlyphCount() {
        return bakedCache.size();
    }

    public void shutdown() {
        bakeExecutor.shutdownNow();
        pendingPixels.clear();
    }

    public ResourceLocation textureIdForPage(final int pageIndex) {
        if (pageIndex < 0 || pageIndex >= pages.size()) {
            if (pages.isEmpty()) {
                throw new IllegalStateException("MSDF atlas has no pages");
            }
            return pages.get(0).textureId;
        }
        return pages.get(pageIndex).textureId;
    }

    private AtlasPage currentPageFor(final int w, final int h) {
        for (final AtlasPage page : pages) {
            if (page.canFit(w, h)) {
                return page;
            }
        }
        if (w <= PAGE_SIZE && h <= PAGE_SIZE) {
            return null;
        }
        throw new IllegalStateException("Glyph too large for atlas page: " + w + "x" + h);
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

    private final class AtlasPage {
        final int index;
        final ResourceLocation textureId;
        final DynamicTexture texture;
        int cursorX;
        int cursorY;
        int rowHeight;

        AtlasPage(final int index) {
            this.index = index;
            this.textureId = new ResourceLocation(NAMESPACE, "textures/font/msdf/page_" + index);
            final NativeImage image = new NativeImage(NativeImage.Format.RGBA, PAGE_SIZE, PAGE_SIZE, false);
            this.texture = new DynamicTexture(image);
            textureManager.register(textureId, texture);
        }

        boolean canFit(final int w, final int h) {
            if (w > PAGE_SIZE || h > PAGE_SIZE) {
                return false;
            }
            if (cursorX + w > PAGE_SIZE) {
                cursorX = 0;
                cursorY += rowHeight;
                rowHeight = 0;
            }
            return cursorY + h <= PAGE_SIZE;
        }

        void advanceCursor(final int w, final int h) {
            cursorX += w;
            rowHeight = Math.max(rowHeight, h);
        }

        void upload(final int x, final int y, final int w, final int h, final byte[] rgba) {
            final NativeImage image = texture.getPixels();
            for (int row = 0; row < h; row++) {
                for (int col = 0; col < w; col++) {
                    final int src = (row * w + col) * 4;
                    final int r = rgba[src] & 0xFF;
                    final int g = rgba[src + 1] & 0xFF;
                    final int b = rgba[src + 2] & 0xFF;
                    final int a = rgba[src + 3] & 0xFF;
                    image.setPixelRGBA(x + col, y + row, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            texture.upload();
        }
    }
}
