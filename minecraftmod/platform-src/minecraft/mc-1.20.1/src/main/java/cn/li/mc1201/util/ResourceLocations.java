package cn.li.mc1201.util;

import net.minecraft.resources.ResourceLocation;

/**
 * ResourceLocation helpers compatible with both Forge mojmap and Fabric 1.20.1
 * (where {@code fromNamespaceAndPath}/{@code parse} may be absent).
 */
public final class ResourceLocations {
    private ResourceLocations() {
    }

    public static ResourceLocation of(String namespace, String path) {
        return new ResourceLocation(namespace, path);
    }

    public static ResourceLocation parse(String id) {
        return new ResourceLocation(id);
    }
}
