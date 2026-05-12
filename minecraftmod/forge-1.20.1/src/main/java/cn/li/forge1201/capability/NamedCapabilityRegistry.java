package cn.li.forge1201.capability;

import net.minecraftforge.common.capabilities.Capability;

/**
 * Compatibility facade kept for callers that still reference the old class name.
 *
 * @deprecated use {@link CapabilityRegistry} directly.
 */
@Deprecated
public final class NamedCapabilityRegistry {

    private NamedCapabilityRegistry() {}

    public static synchronized <T> void register(String key, Capability<T> cap) {
        CapabilityRegistry.register(key, cap);
    }

    public static synchronized <T> Capability<T> getOrCreate(String key) {
        return CapabilityRegistry.getOrCreate(key);
    }

    public static synchronized String getKey(Capability<?> cap) {
        return CapabilityRegistry.getKey(cap);
    }

    public static <T> Capability<T> get(String key) {
        return CapabilityRegistry.get(key);
    }
}

