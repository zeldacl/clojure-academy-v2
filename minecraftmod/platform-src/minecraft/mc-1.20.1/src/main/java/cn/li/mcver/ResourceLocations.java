package cn.li.mcver;

import net.minecraft.resources.ResourceLocation;

/**
 * Version seam for ResourceLocation construction.
 * Contract matches 1.21 factory methods; 1.20.1 implements via constructors.
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
    /** Native id class ({@link ResourceLocation}) for type checks. */
    public static Class<?> idClass() {
        return ResourceLocation.class;
    }
}
