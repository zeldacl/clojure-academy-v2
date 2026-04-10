package cn.li.forge1201.capability;

import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Named capability registry that replaces the old anonymous-slot pool.
 *
 * <p>Wireless capabilities are bound to stable key strings registered at platform init.
 * Non-wireless capabilities (e.g. "wireless-energy") receive a single anonymous
 * {@link CapabilityToken} created on first use.
 *
 * <p>All four named wireless capabilities are registered at platform init via
 * {@link #register(String, Capability)} before any block entity resolves them.
 */
public final class NamedCapabilityRegistry {

    private static final Map<String, Capability<?>> KEY_TO_CAP = new ConcurrentHashMap<>();
    private static final Map<Capability<?>, String> CAP_TO_KEY = new IdentityHashMap<>();

    private NamedCapabilityRegistry() {}

    /**
     * Create one anonymous CapabilityToken-backed capability without a type variable in
     * the generic signature. Using method type parameter T here can produce signature
     * TT; and fail at runtime during Forge capability token introspection.
     */
    private static Capability<Object> createAnonymousCapability() {
        return CapabilityManager.get(new CapabilityToken<>() {});
    }

    /**
    * Bind a logical key string to a capability instance.
    * Called at platform init before block entities resolve capabilities.
     */
    public static synchronized <T> void register(String key, Capability<T> cap) {
        KEY_TO_CAP.put(key, cap);
        CAP_TO_KEY.put(cap, key);
    }

    /**
     * Get (or lazily create) a capability for a key.
     * For well-known wireless keys this returns the pre-registered named constant.
     * For other keys a single anonymous-token capability is created once and cached.
     */
    @SuppressWarnings("unchecked")
    public static synchronized <T> Capability<T> getOrCreate(String key) {
        Capability<?> existing = KEY_TO_CAP.get(key);
        if (existing != null) return (Capability<T>) existing;
        Capability<T> cap = (Capability<T>) createAnonymousCapability();
        register(key, cap);
        return cap;
    }

    /**
     * Reverse lookup: Capability object → logical key string.
     * Used by {@code ScriptedBlockEntity.getCapability} to dispatch into Clojure.
     *
     * @return the key, or {@code null} if this capability was not registered
     */
    public static synchronized String getKey(Capability<?> cap) {
        return CAP_TO_KEY.get(cap);
    }

    /**
     * Forward lookup: logical key string → Capability object.
     * Used by the Clojure platform layer to obtain a Capability for querying a block entity.
     *
     * @return the Capability, or {@code null} if the key has not been registered yet
     */
    @SuppressWarnings("unchecked")
    public static <T> Capability<T> get(String key) {
        return (Capability<T>) KEY_TO_CAP.get(key);
    }
}
