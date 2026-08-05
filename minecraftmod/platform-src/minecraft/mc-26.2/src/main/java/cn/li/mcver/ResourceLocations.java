package cn.li.mcver;

import net.minecraft.resources.Identifier;

/**
 * Version seam for identifier construction.
 * 26.2 renamed {@code Identifier} to {@link Identifier}; the factory
 * methods ({@link Identifier#fromNamespaceAndPath}, {@link Identifier#parse})
 * kept the same names.
 */
public final class ResourceLocations {
    private ResourceLocations() {
    }

    public static Identifier of(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    public static Identifier parse(String id) {
        return Identifier.parse(id);
    }
}
