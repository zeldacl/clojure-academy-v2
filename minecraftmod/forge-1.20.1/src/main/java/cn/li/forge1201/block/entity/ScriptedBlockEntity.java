package cn.li.forge1201.block.entity;

import clojure.lang.RT;
import clojure.lang.Var;
import cn.li.forge1201.capability.ForgeCapabilityHandler;
import cn.li.mc1201.block.entity.AbstractScriptedBlockEntity;
import cn.li.mc1201.block.entity.BlockEntityRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Forge scripted block entity with capability and container support.
 *
 * <p>Core scripted state/NBT/tick behavior is implemented in
 * {@link AbstractScriptedBlockEntity}.</p>
 */
public class ScriptedBlockEntity extends AbstractScriptedBlockEntity implements WorldlyContainer {

    /**
     * Register this entity type via the shared registry.
     */
    public static void registerType(String tileId, BlockEntityType<ScriptedBlockEntity> type) {
        BlockEntityRegistry.registerType(tileId, type);
    }

    /**
     * Retrieve a registered entity type via the shared registry.
     */
    @Nullable
    public static BlockEntityType<ScriptedBlockEntity> getType(String tileId) {
        return (BlockEntityType<ScriptedBlockEntity>) BlockEntityRegistry.getType(tileId);
    }

    private final ForgeCapabilityHandler capabilityHandler = new ForgeCapabilityHandler();

    public ScriptedBlockEntity(BlockEntityType<ScriptedBlockEntity> type,
                               BlockPos pos,
                               BlockState state,
                               String tileId,
                               String blockId) {
        super(type, pos, state, tileId, blockId);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ScriptedBlockEntity blockEntity) {
        invokeServerTick(level, pos, state, blockEntity);
    }

    // -------------------------------------------------------------------------
    // Forge Capability
    // -------------------------------------------------------------------------

    @Nonnull
    @Override
    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction side) {
        LazyOptional<T> resolved = capabilityHandler.getCapability(cap, side, getTileId(), this);
        if (resolved.isPresent()) {
            return resolved;
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        capabilityHandler.invalidate();
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        capabilityHandler.revive();
    }

    // -------------------------------------------------------------------------
    // Container / WorldlyContainer implementation
    // Delegates to tile-logic/container-* functions when a container is registered.
    // -------------------------------------------------------------------------

    private static final int[] EMPTY_INT_ARRAY = new int[0];

    private @Nullable Object containerOp(String fn, Object... extraArgs) {
        try {
            Var v = RT.var("cn.li.mcmod.block.tile-logic", fn);
            Object[] args = new Object[1 + extraArgs.length];
            args[0] = getTileId();
            System.arraycopy(extraArgs, 0, args, 1, extraArgs.length);
            return v.applyTo(clojure.lang.RT.seq(args));
        } catch (Exception ex) {
            return null;
        }
    }

    @Override
    public int getContainerSize() {
        Object r = containerOp("container-size", this);
        return (r instanceof Number n) ? n.intValue() : 0;
    }

    @Override
    public boolean isEmpty() {
        int size = getContainerSize();
        for (int i = 0; i < size; i++) {
            if (!getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Nonnull
    @Override
    public ItemStack getItem(int slot) {
        Object r = containerOp("container-get-item", this, slot);
        return (r instanceof ItemStack s) ? s : ItemStack.EMPTY;
    }

    @Nonnull
    @Override
    public ItemStack removeItem(int slot, int amount) {
        Object r = containerOp("container-remove-item", this, slot, amount);
        return (r instanceof ItemStack s) ? s : ItemStack.EMPTY;
    }

    @Nonnull
    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        Object r = containerOp("container-remove-item-no-update", this, slot);
        return (r instanceof ItemStack s) ? s : ItemStack.EMPTY;
    }

    @Override
    public void setItem(int slot, @Nonnull ItemStack stack) {
        containerOp("container-set-item", this, slot, stack);
        setChanged();
    }

    @Override
    public boolean stillValid(@Nonnull Player player) {
        Object r = containerOp("container-still-valid", this, player);
        return !(Boolean.FALSE.equals(r));
    }

    @Override
    public void clearContent() {
        containerOp("container-clear", this);
        setChanged();
    }

    @Nonnull
    @Override
    public int[] getSlotsForFace(@Nonnull Direction side) {
        Object r = containerOp("container-slots-for-face", this, side);
        if (r instanceof int[] arr) {
            return arr;
        }
        return EMPTY_INT_ARRAY;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, @Nonnull ItemStack item, @Nullable Direction dir) {
        Object r = containerOp("container-can-place-through-face", this, slot, item, dir);
        return Boolean.TRUE.equals(r);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, @Nonnull ItemStack item, @Nonnull Direction dir) {
        Object r = containerOp("container-can-take-through-face", this, slot, item, dir);
        return Boolean.TRUE.equals(r);
    }
}
