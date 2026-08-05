package cn.li.neoforge262.capability;

import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.ItemCapability;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

import javax.annotation.Nullable;

/**
 * NeoForge 26.2 built-in capabilities use the transfer API
 * ({@link EnergyHandler}, {@link ResourceHandler}).
 *
 * <p>{@link EnergyHandler} and legacy {@code IItemHandler} map onto the built-in
 * Energy/Item BLOCK tokens. Fluid still uses {@link #fluidBlock()} explicitly
 * because {@link ResourceHandler} is shared by item+fluid. Prefer
 * {@link #energyBlock()}, {@link #fluidBlock()}, {@link #itemBlock()} when
 * registering transfer-facing providers.</p>
 */
public final class ForgeProvidedCapabilitySupport {

    private ForgeProvidedCapabilitySupport() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static BlockCapability<EnergyHandler, Direction> energyBlock() {
        return Capabilities.Energy.BLOCK;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static BlockCapability<ResourceHandler<FluidResource>, Direction> fluidBlock() {
        return Capabilities.Fluid.BLOCK;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static BlockCapability<ResourceHandler<ItemResource>, Direction> itemBlock() {
        return Capabilities.Item.BLOCK;
    }

    public static boolean isForgeProvidedCapabilityType(Class<?> capabilityType) {
        return blockCapabilityForType(capabilityType) != null
            || itemCapabilityForType(capabilityType) != null;
    }

    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static BlockCapability<?, Direction> blockCapabilityForType(Class<?> capabilityType) {
        if (capabilityType == null) {
            return null;
        }
        if (capabilityType == EnergyHandler.class) {
            return Capabilities.Energy.BLOCK;
        }
        // Legacy IItemHandler factories are wrapped to ResourceHandler in ForgeCapabilityHandler.
        if (capabilityType == IItemHandler.class) {
            return Capabilities.Item.BLOCK;
        }
        // ResourceHandler is shared by item+fluid; callers must use fluidBlock()/itemBlock().
        return null;
    }

    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static ItemCapability<?, Void> itemCapabilityForType(Class<?> capabilityType) {
        return null;
    }

    @Deprecated
    @Nullable
    public static BlockCapability<?, Direction> forgeProvidedCapabilityForType(Class<?> capabilityType) {
        return blockCapabilityForType(capabilityType);
    }
}
