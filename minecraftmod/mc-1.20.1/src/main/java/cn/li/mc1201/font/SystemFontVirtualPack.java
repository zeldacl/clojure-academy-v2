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
 * A virtual {@link PackResources} that injects TrueType system font variants
 * (regular, bold, italic) into the Minecraft resource system at runtime.
 */
public final class SystemFontVirtualPack implements PackResources {

    private static final String NAMESPACE = "my_mod";

    private final byte[] fontFileBytes;
    private final byte[] normalJsonBytes;
    private final byte[] boldJsonBytes;
    private final byte[] italicJsonBytes;
    private final String fontResourcePath;

    public SystemFontVirtualPack(final Path systemFontPath,
                                 final String fontExt,
                                 final byte[] normalJsonBytes,
                                 final byte[] boldJsonBytes,
                                 final byte[] italicJsonBytes) throws IOException {
        this.fontFileBytes = extractFirstTtf(Files.readAllBytes(systemFontPath));
        this.normalJsonBytes = normalJsonBytes;
        this.boldJsonBytes = boldJsonBytes;
        this.italicJsonBytes = italicJsonBytes;
        this.fontResourcePath = "font/system_font." + fontExt;
    }

    // =========================================================================
    // TTC → TTF extraction with pointer relocation
    // =========================================================================

    private static byte[] extractFirstTtf(final byte[] raw) {
        if (raw.length < 12) return raw;
        final String tag = new String(raw, 0, 4, StandardCharsets.US_ASCII);
        if (!"ttcf".equals(tag)) return raw;
        final ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        final int firstOffset = buf.getInt(12);
        if (firstOffset <= 0 || firstOffset >= raw.length) return raw;
        final int numTables = Short.toUnsignedInt(buf.getShort(firstOffset + 4));
        final int headerSize = 12 + 16 * numTables;
        int maxEnd = headerSize;
        for (int i = 0; i < numTables; i++) {
            final int rb = firstOffset + 12 + i * 16;
            final int end = (buf.getInt(rb + 8) + buf.getInt(rb + 12)) - firstOffset;
            if (end > maxEnd) maxEnd = end;
        }
        final byte[] result = new byte[maxEnd];
        System.arraycopy(raw, firstOffset, result, 0, headerSize);
        final ByteBuffer resultBuf = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < numTables; i++) {
            final int rb = 12 + i * 16;
            final int origOff = buf.getInt(firstOffset + rb + 8);
            final int len = buf.getInt(firstOffset + rb + 12);
            final int newOff = origOff - firstOffset;
            resultBuf.putInt(rb + 8, newOff);
            System.arraycopy(raw, origOff, result, newOff, len);
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
        if (!NAMESPACE.equals(location.getNamespace())) return null;

        final String path = location.getPath();
        final byte[] jsonBytes = jsonBytesFor(path);
        if (jsonBytes != null) {
            return () -> new ByteArrayInputStream(jsonBytes);
        }
        if (fontResourcePath.equals(path)) {
            return () -> new ByteArrayInputStream(fontFileBytes);
        }
        return null;
    }

    private byte[] jsonBytesFor(final String path) {
        switch (path) {
            case "font/ac_normal.json": return normalJsonBytes;
            case "font/ac_bold.json":   return boldJsonBytes;
            case "font/ac_italic.json": return italicJsonBytes;
            default: return null;
        }
    }

    @Override
    public void listResources(final PackType type,
                              final String namespace,
                              final String prefix,
                              final ResourceOutput output) {
        if (!NAMESPACE.equals(namespace)) return;
        if ("font".equals(prefix) || "font/".equals(prefix)) {
            output.accept(new ResourceLocation(NAMESPACE, "font/ac_normal.json"),
                          () -> new ByteArrayInputStream(normalJsonBytes));
            output.accept(new ResourceLocation(NAMESPACE, "font/ac_bold.json"),
                          () -> new ByteArrayInputStream(boldJsonBytes));
            output.accept(new ResourceLocation(NAMESPACE, "font/ac_italic.json"),
                          () -> new ByteArrayInputStream(italicJsonBytes));
            output.accept(new ResourceLocation(NAMESPACE, fontResourcePath),
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
    public String packId() { return "my_mod/system_font_virtual"; }

    @Override
    public <T> T getMetadataSection(
            final net.minecraft.server.packs.metadata.MetadataSectionSerializer<T> ser) {
        return null;
    }

    public boolean isHidden() { return true; }

    @Override
    public void close() {}
}
