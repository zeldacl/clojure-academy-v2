package cn.li.mcver;

import net.minecraft.resources.ResourceLocation;

/**
 * Version seam for ResourceLocation construction.
 * Uses 1.21 factory methods ({@link ResourceLocation#fromNamespaceAndPath},
 * {@link ResourceLocation#parse}).
 */
public final class ResourceLocations {
    private ResourceLocations() {
    }

    public static ResourceLocation of(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    public static ResourceLocation parse(String id) {
        return ResourceLocation.parse(id);
    }
}
