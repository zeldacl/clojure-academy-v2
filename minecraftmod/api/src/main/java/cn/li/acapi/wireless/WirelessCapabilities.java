package cn.li.acapi.wireless;

import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Public, named Forge Capability constants for the wireless energy system.
 *
 * <p>External mods use these constants to query wireless capabilities from block entities:
 * <pre>{@code
 * // In your mod code:
 * LazyOptional<IWirelessNode> opt =
 *     blockEntity.getCapability(WirelessCapabilities.NODE, side);
 * opt.ifPresent(node -> {
 *     double energy = node.getEnergy();
 *     // ...
 * });
 * }</pre>
 *
 * <p>Each constant is a stable, typed {@link Capability} that Forge can discover
 * without requiring any magic strings or internal knowledge of this mod's internals.
 *
 * <p><b>Capability lifetime:</b> These objects are live once the {@code RegisterCapabilitiesEvent}
 * has fired (i.e., after mod construction). They are safe to cache in {@code static final} fields.
 *
 * <p><b>Lazy Initialization:</b> Capabilities are initialized on first access, not at class load time,
 * to avoid triggering Minecraft bootstrap during checkClojure or static analysis phases.
 */
public final class WirelessCapabilities {

    private static final AtomicReference<Capability<IWirelessMatrix>> MATRIX_REF = new AtomicReference<>();
    private static final AtomicReference<Capability<IWirelessNode>> NODE_REF = new AtomicReference<>();
    private static final AtomicReference<Capability<IWirelessGenerator>> GENERATOR_REF = new AtomicReference<>();
    private static final AtomicReference<Capability<IWirelessReceiver>> RECEIVER_REF = new AtomicReference<>();

    /**
     * Get the Capability for wireless matrix blocks (network center).
     * Lazily initialized on first call to avoid bootstrap during static analysis.
     */
    public static Capability<IWirelessMatrix> getMatrix() {
        Capability<IWirelessMatrix> cap = MATRIX_REF.get();
        if (cap == null) {
            cap = CapabilityManager.get(new CapabilityToken<IWirelessMatrix>() {});
            MATRIX_REF.set(cap);
        }
        return cap;
    }

    /**
     * Get the Capability for wireless node blocks (energy storage hub).
     * Lazily initialized on first call to avoid bootstrap during static analysis.
     */
    public static Capability<IWirelessNode> getNode() {
        Capability<IWirelessNode> cap = NODE_REF.get();
        if (cap == null) {
            cap = CapabilityManager.get(new CapabilityToken<IWirelessNode>() {});
            NODE_REF.set(cap);
        }
        return cap;
    }

    /**
     * Get the Capability for wireless generator blocks (energy source).
     * Lazily initialized on first call to avoid bootstrap during static analysis.
     */
    public static Capability<IWirelessGenerator> getGenerator() {
        Capability<IWirelessGenerator> cap = GENERATOR_REF.get();
        if (cap == null) {
            cap = CapabilityManager.get(new CapabilityToken<IWirelessGenerator>() {});
            GENERATOR_REF.set(cap);
        }
        return cap;
    }

    /**
     * Get the Capability for wireless receiver blocks (energy consumer).
     * Lazily initialized on first call to avoid bootstrap during static analysis.
     */
    public static Capability<IWirelessReceiver> getReceiver() {
        Capability<IWirelessReceiver> cap = RECEIVER_REF.get();
        if (cap == null) {
            cap = CapabilityManager.get(new CapabilityToken<IWirelessReceiver>() {});
            RECEIVER_REF.set(cap);
        }
        return cap;
    }

    // Legacy static field accessors for backward compatibility
    /** Deprecated: use getMatrix() instead. */
    @Deprecated
    public static final Capability<IWirelessMatrix> MATRIX = null; // Will be initialized lazily

    /** Deprecated: use getNode() instead. */
    @Deprecated
    public static final Capability<IWirelessNode> NODE = null; // Will be initialized lazily

    /** Deprecated: use getGenerator() instead. */
    @Deprecated
    public static final Capability<IWirelessGenerator> GENERATOR = null; // Will be initialized lazily

    /** Deprecated: use getReceiver() instead. */
    @Deprecated
    public static final Capability<IWirelessReceiver> RECEIVER = null; // Will be initialized lazily

    private WirelessCapabilities() {}
}
