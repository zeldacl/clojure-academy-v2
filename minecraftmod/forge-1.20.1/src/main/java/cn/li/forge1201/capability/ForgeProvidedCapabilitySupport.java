package cn.li.forge1201.capability;

import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

import java.util.List;

/**
 * Forge ships built-in capabilities through {@link ForgeCapabilities} tokens.
 * Those interfaces are already registered before mod {@code RegisterCapabilitiesEvent}
 * handlers run; mods attach handlers and map tokens via {@link CapabilityRegistry},
 * but must not call {@code RegisterCapabilitiesEvent#register} for them again.
 */
public final class ForgeProvidedCapabilitySupport {

    private static final List<Capability<?>> FORGE_PROVIDED = List.of(
            ForgeCapabilities.ENERGY,
            ForgeCapabilities.FLUID_HANDLER,
            ForgeCapabilities.FLUID_HANDLER_ITEM,
            ForgeCapabilities.ITEM_HANDLER
    );

    private ForgeProvidedCapabilitySupport() {
    }

    /**
     * Returns true when {@code capabilityType} is a Forge-provided capability interface
     * already registered through {@link ForgeCapabilities}.
     */
    public static boolean isForgeProvidedCapabilityType(Class<?> capabilityType) {
        return forgeProvidedCapabilityForType(capabilityType) != null;
    }

    /**
     * Resolve the {@link ForgeCapabilities} token for a Forge-provided interface, if any.
     */
    public static Capability<?> forgeProvidedCapabilityForType(Class<?> capabilityType) {
        if (capabilityType == null) {
            return null;
        }
        String typeName = capabilityType.getName();
        for (Capability<?> provided : FORGE_PROVIDED) {
            if (provided.isRegistered() && provided.getName().equals(typeName)) {
                return provided;
            }
        }
        return null;
    }
}
