package cn.li.acapi.wireless;

import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;

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
 */
public final class WirelessCapabilities {

    /** Capability for wireless matrix blocks (network center). */
    public static final Capability<IWirelessMatrix> MATRIX =
            CapabilityManager.get(new CapabilityToken<IWirelessMatrix>() {});

    /** Capability for wireless node blocks (energy storage hub). */
    public static final Capability<IWirelessNode> NODE =
            CapabilityManager.get(new CapabilityToken<IWirelessNode>() {});

    /** Capability for wireless generator blocks (energy source). */
    public static final Capability<IWirelessGenerator> GENERATOR =
            CapabilityManager.get(new CapabilityToken<IWirelessGenerator>() {});

    /** Capability for wireless receiver blocks (energy consumer). */
    public static final Capability<IWirelessReceiver> RECEIVER =
            CapabilityManager.get(new CapabilityToken<IWirelessReceiver>() {});

    private WirelessCapabilities() {}
}
