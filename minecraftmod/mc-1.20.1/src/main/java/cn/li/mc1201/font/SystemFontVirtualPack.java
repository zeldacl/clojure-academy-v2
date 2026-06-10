package cn.li.mc1201.font;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * A virtual {@link PackResources} that injects a TrueType system font into the
 * Minecraft resource system at runtime.
 *
 * <p>MC 1.20's {@code stbtt_InitFont} cannot parse TTC files.  When a
 * {@code .ttc} (TrueType Collection) is detected, this class reconstructs the
 * first sub-font as a standalone TTF with relocated table offsets.</p>
 */
public final class SystemFontVirtualPack implements PackResources {

    private static final String NAMESPACE = "my_mod";

    private final byte[] fontJsonBytes;
    private final byte[] fontFileBytes;
    private final String fontExt;
    private final String fontResourcePath;

    public SystemFontVirtualPack(final Path systemFontPath,
                                 final String fontExt,
                                 final String fontJson) throws IOException {
        this.fontExt = fontExt;
        this.fontJsonBytes = fontJson.getBytes(StandardCharsets.UTF_8);
        this.fontFileBytes = extractFirstTtf(Files.readAllBytes(systemFontPath));
        this.fontResourcePath = "font/system_font." + fontExt;
    }

    // =========================================================================
    // TTC → TTF extraction with pointer relocation
    // =========================================================================

    private static byte[] extractFirstTtf(final byte[] raw) {
        if (raw.length < 12) {
            return raw;
        }
        final String tag = new String(raw, 0, 4, StandardCharsets.US_ASCII);
        if (!"ttcf".equals(tag)) {
            return raw;
        }

        final ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        final int firstOffset = buf.getInt(12);
        if (firstOffset <= 0 || firstOffset >= raw.length) {
            return raw;
        }

        // --- read TTF header at firstOffset ---
        final int numTables = Short.toUnsignedInt(buf.getShort(firstOffset + 4));
        final int headerSize = 12 + 16 * numTables;

        // --- compute total TTF size: max table end ---
        int maxEnd = headerSize;
        for (int i = 0; i < numTables; i++) {
            final int recordBase = firstOffset + 12 + i * 16;
            final int tableOff = buf.getInt(recordBase + 8);
            final int tableLen = buf.getInt(recordBase + 12);
            final int end = (tableOff + tableLen) - firstOffset;
            if (end > maxEnd) {
                maxEnd = end;
            }
        }

        // --- build standalone TTF ---
        final byte[] result = new byte[maxEnd];
        // Copy header (table records still have absolute offsets)
        System.arraycopy(raw, firstOffset, result, 0, headerSize);

        final ByteBuffer resultBuf = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN);
        // Relocate table offsets and copy table data
        for (int i = 0; i < numTables; i++) {
            final int recordBase = 12 + i * 16;
            final int origTableOff = buf.getInt(firstOffset + recordBase + 8);
            final int tableLen = buf.getInt(firstOffset + recordBase + 12);
            final int newOff = origTableOff - firstOffset;
            resultBuf.putInt(recordBase + 8, newOff);
            System.arraycopy(raw, origTableOff, result, newOff, tableLen);
        }

        return result;
    }

    // =========================================================================
    // Resource lookup
    // =========================================================================

    @Override
    public IoSupplier<InputStream> getRootResource(final String... elements) {
        return null;
    }

    @Override
    public IoSupplier<InputStream> getResource(final PackType type,
                                               final ResourceLocation location) {
        if (!NAMESPACE.equals(location.getNamespace())) {
            return null;
        }
        final String path = location.getPath();
        if ("font/ac_normal.json".equals(path)) {
            return () -> new ByteArrayInputStream(fontJsonBytes);
        }
        if (fontResourcePath.equals(path)) {
            return () -> new ByteArrayInputStream(fontFileBytes);
        }
        return null;
    }

    @Override
    public void listResources(final PackType type,
                              final String namespace,
                              final String prefix,
                              final ResourceOutput output) {
        if (!NAMESPACE.equals(namespace)) {
            return;
        }
        if ("font".equals(prefix) || "font/".equals(prefix)) {
            output.accept(
                new ResourceLocation(NAMESPACE, "font/ac_normal.json"),
                () -> new ByteArrayInputStream(fontJsonBytes));
            output.accept(
                new ResourceLocation(NAMESPACE, fontResourcePath),
                () -> new ByteArrayInputStream(fontFileBytes));
        }
    }

    @Override
    public Set<String> getNamespaces(final PackType type) {
        if (type == PackType.CLIENT_RESOURCES) {
            final Set<String> ns = new HashSet<>(2);
            ns.add(NAMESPACE);
            return Collections.unmodifiableSet(ns);
        }
        return Collections.emptySet();
    }

    @Override
    public String packId() {
        return "my_mod/system_font_virtual";
    }

    @Override
    public <T> T getMetadataSection(
            final net.minecraft.server.packs.metadata.MetadataSectionSerializer<T> serializer) {
        return null;
    }

    @Override
    public boolean isHidden() {
        return true;
    }

    @Override
    public void close() {
    }
}
