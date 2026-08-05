package cn.li.neoforge262.capability;

import cn.li.mc262.block.capability.ScriptedCapabilityResolver;
import cn.li.mc262.block.entity.AbstractScriptedBlockEntity;
import cn.li.mc262.block.entity.BlockEntityRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;

import javax.annotation.Nullable;

/**
 * NeoForge capability registration and resolution for scripted block entities.
 *
 * <p>Providers are registered on {@link RegisterCapabilitiesEvent}; queries return a nullable
 * handler. Call {@link #invalidateAt(Level, BlockPos)} when a block capability
 * appears, disappears, or must be re-resolved.</p>
 */
public final class ForgeCapabilityHandler {

    private ForgeCapabilityHandler() {
    }

    /**
     * Register providers for every known block capability on every scripted block-entity type.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void registerAll(RegisterCapabilitiesEvent event) {
        for (BlockEntityType<?> type : BlockEntityRegistry.allTypes()) {
            for (BlockCapability<?, Direction> cap : CapabilityRegistry.allBlockCapabilities()) {
                BlockCapability blockCap = cap;
                BlockEntityType beType = type;
                event.registerBlockEntity(
                    blockCap,
                    beType,
                    (be, side) -> resolve(be, blockCap, (Direction) side));
            }
        }
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public static <T> T resolve(@Nullable BlockEntity be,
                                BlockCapability<T, Direction> cap,
                                @Nullable Direction side) {
        if (!(be instanceof AbstractScriptedBlockEntity scripted)) {
            return null;
        }
        for (String key : CapabilityRegistry.keysFor(cap)) {
            Object handler = adaptLegacyHandler(cap, ScriptedCapabilityResolver.resolve(scripted, key, side));
            if (handler != null) {
                return (T) handler;
            }
        }
        String key = ForgeCapabilityQuery.getKey(cap);
        if (key == null) {
            return null;
        }
        Object handler = adaptLegacyHandler(cap, ScriptedCapabilityResolver.resolve(scripted, key, side));
        if (handler == null) {
            return null;
        }
        return (T) handler;
    }

    /**
     * Wrap legacy Forge storage interfaces onto 26.2 transfer handlers when the
     * queried token is a built-in Energy/Fluid/Item BLOCK capability.
     */
    @Nullable
    private static Object adaptLegacyHandler(BlockCapability<?, Direction> cap, @Nullable Object handler) {
        if (handler == null) {
            return null;
        }
        if (handler instanceof ResourceHandler || handler instanceof EnergyHandler) {
            return handler;
        }
        if (cap == Capabilities.Item.BLOCK && handler instanceof IItemHandler itemHandler) {
            return ItemHandlerAsResourceHandler.wrap(itemHandler);
        }
        if (cap == Capabilities.Fluid.BLOCK && handler instanceof IFluidHandler fluidHandler) {
            return FluidHandlerAsResourceHandler.wrap(fluidHandler);
        }
        if (cap == Capabilities.Energy.BLOCK && handler instanceof IEnergyStorage energyStorage) {
            return EnergyStorageAsHandler.wrap(energyStorage);
        }
        return handler;
    }

    /**
     * Notify NeoForge capability caches that providers at {@code pos} may have changed.
     */
    public static void invalidateAt(@Nullable Level level, BlockPos pos) {
        if (level != null && pos != null) {
            level.invalidateCapabilities(pos);
        }
    }
}
