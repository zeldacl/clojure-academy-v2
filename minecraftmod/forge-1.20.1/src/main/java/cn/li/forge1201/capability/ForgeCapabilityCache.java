package cn.li.forge1201.capability;

import net.minecraftforge.common.util.LazyOptional;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache lifecycle for resolved Forge capability handlers.
 */
public final class ForgeCapabilityCache {

    private final Map<String, LazyOptional<Object>> cache = new ConcurrentHashMap<>();

    public LazyOptional<Object> get(String key) {
        return cache.get(key);
    }

    public void put(String key, LazyOptional<Object> value) {
        cache.put(key, value);
    }

    public void invalidate() {
        cache.values().forEach(LazyOptional::invalidate);
        cache.clear();
    }

    public void revive() {
        // No eager work. Capabilities are recreated lazily on first query.
    }
}