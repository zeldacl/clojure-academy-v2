package cn.li.forge1201.capability;

import clojure.lang.RT;
import clojure.lang.Var;
import net.minecraft.core.Direction;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Encapsulates Forge capability caching and lifecycle for scripted block entities.
 */
public final class ForgeCapabilityHandler {
    private final Map<String, LazyOptional<Object>> capCache = new ConcurrentHashMap<>();

    @Nonnull
    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap,
                                             @Nullable Direction side,
                                             String tileId,
                                             Object owner) {
        String key = CapabilityRegistry.getKey(cap);
        if (key == null) {
            return LazyOptional.empty();
        }

        LazyOptional<Object> cached = capCache.get(key);
        if (cached != null && cached.isPresent()) {
            return cached.cast();
        }

        try {
            Var getCapFn = RT.var("cn.li.mcmod.block.tile-logic", "get-capability");
            Object handler = getCapFn.invoke(tileId, key, owner, side);
            if (handler != null) {
                LazyOptional<Object> lazyOptional = LazyOptional.of(() -> handler);
                capCache.put(key, lazyOptional);
                return lazyOptional.cast();
            }
        } catch (Exception ignored) {
            // fall through to empty; caller may delegate to super capability path
        }
        return LazyOptional.empty();
    }

    public void invalidate() {
        capCache.values().forEach(LazyOptional::invalidate);
        capCache.clear();
    }

    public void revive() {
        // No eager work. Capabilities are recreated lazily on first query.
    }
}
