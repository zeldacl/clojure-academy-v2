package my_mod.capability;

import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pre-allocated Forge Capability slot pool.
 *
 * <p>Each anonymous CapabilityToken subclass yields a distinct Capability&lt;Object&gt;.
 * ac code calls {@link #assign(String)} at startup to claim a slot by logical key;
 * {@link #get(String)} retrieves the typed Capability later for external-mod interop.
 *
 * <p>This file is written ONCE and never modified. Add more SLOT_N entries only if
 * the 16-slot pool is exhausted (unlikely given current mod scope).
 */
public final class CapabilitySlots {

    @SuppressWarnings("unchecked")
    private static final Capability<Object>[] SLOTS = new Capability[]{
        CapabilityManager.get(new CapabilityToken<>(){}),  // SLOT_0
        CapabilityManager.get(new CapabilityToken<>(){}),  // SLOT_1
        CapabilityManager.get(new CapabilityToken<>(){}),  // SLOT_2
        CapabilityManager.get(new CapabilityToken<>(){}),  // SLOT_3
        CapabilityManager.get(new CapabilityToken<>(){}),  // SLOT_4
        CapabilityManager.get(new CapabilityToken<>(){}),  // SLOT_5
        CapabilityManager.get(new CapabilityToken<>(){}),  // SLOT_6
        CapabilityManager.get(new CapabilityToken<>(){}),  // SLOT_7
        CapabilityManager.get(new CapabilityToken<>(){}),  // SLOT_8
        CapabilityManager.get(new CapabilityToken<>(){}),  // SLOT_9
        CapabilityManager.get(new CapabilityToken<>(){}),  // SLOT_10
        CapabilityManager.get(new CapabilityToken<>(){}),  // SLOT_11
        CapabilityManager.get(new CapabilityToken<>(){}),  // SLOT_12
        CapabilityManager.get(new CapabilityToken<>(){}),  // SLOT_13
        CapabilityManager.get(new CapabilityToken<>(){}),  // SLOT_14
        CapabilityManager.get(new CapabilityToken<>(){}),  // SLOT_15
    };

    private static final Map<String, Integer> KEY_TO_SLOT = new ConcurrentHashMap<>();
    private static final Map<Capability<?>, String> CAP_TO_KEY = new IdentityHashMap<>();
    private static int nextSlot = 0;

    private CapabilitySlots() {}

    /**
     * Claim the next free slot for the given logical key.
     * Idempotent: calling with the same key twice returns the same Capability.
     *
     * @param key logical capability key (e.g. "wireless-node")
     * @return the Capability object for this key
     * @throws IllegalStateException if all 16 slots are already in use
     */
    @SuppressWarnings("unchecked")
    public static synchronized <T> Capability<T> assign(String key) {
        if (KEY_TO_SLOT.containsKey(key)) {
            return (Capability<T>) SLOTS[KEY_TO_SLOT.get(key)];
        }
        if (nextSlot >= SLOTS.length) {
            throw new IllegalStateException(
                "CapabilitySlots: all " + SLOTS.length + " slots exhausted. key=" + key);
        }
        int slot = nextSlot++;
        KEY_TO_SLOT.put(key, slot);
        synchronized (CAP_TO_KEY) {
            CAP_TO_KEY.put(SLOTS[slot], key);
        }
        return (Capability<T>) SLOTS[slot];
    }

    /**
     * Reverse lookup: Forge Capability → logical key.
     * Used inside ScriptedBlockEntity.getCapability() to dispatch to Clojure.
     */
    public static String getKey(Capability<?> cap) {
        synchronized (CAP_TO_KEY) {
            return CAP_TO_KEY.get(cap);
        }
    }

    /**
     * Forward lookup: logical key → Forge Capability.
     * Used by external mods or tests to obtain the typed Capability object.
     *
     * @return the Capability, or {@code null} if the key has not been assigned yet
     */
    @SuppressWarnings("unchecked")
    public static <T> Capability<T> get(String key) {
        Integer slot = KEY_TO_SLOT.get(key);
        return slot == null ? null : (Capability<T>) SLOTS[slot];
    }

    /** @return true if this key has already been assigned a slot */
    public static boolean isAssigned(String key) {
        return KEY_TO_SLOT.containsKey(key);
    }
}
