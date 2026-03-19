package cn.li.forge1201.block.entity;

import clojure.lang.RT;
import clojure.lang.Var;
import cn.li.forge1201.capability.CapabilitySlots;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.nbt.Tag;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic block entity driven by Clojure tile-logic (tick + NBT + capabilities).
 *
 * <p>State model (post Design-3):
 * <ul>
 *   <li>{@code customState} – the Clojure persistent map that is the single source of truth.
 *       read-nbt-fn returns it; write-nbt-fn receives it; tick receives/returns it.</li>
 *   <li>Legacy Java fields ({@code energy}, {@code status}, …) are kept for backward compat
 *       but are no longer the primary store.</li>
 * </ul>
 *
 * <p>Capability dispatch:
 * <ul>
 *   <li>{@link CapabilitySlots} maps logical string keys → anonymous Capability tokens.</li>
 *   <li>{@code tile-logic/get-capability} is called to obtain the handler for each key.</li>
 * </ul>
 *
 * <p>Container: every ScriptedBlockEntity implements {@link WorldlyContainer}. When
 * no container is registered in tile-logic the default (0 slots / always valid) is used.
 */
public class ScriptedBlockEntity extends BlockEntity implements WorldlyContainer {

    // -------------------------------------------------------------------------
    // Static registry
    // -------------------------------------------------------------------------

    private static final Map<String, BlockEntityType<ScriptedBlockEntity>> TYPES = new HashMap<>();

    public static void registerType(String tileId, BlockEntityType<ScriptedBlockEntity> type) {
        TYPES.put(tileId, type);
    }

    @Nullable
    public static BlockEntityType<ScriptedBlockEntity> getType(String tileId) {
        return TYPES.get(tileId);
    }

    // -------------------------------------------------------------------------
    // Instance fields
    // -------------------------------------------------------------------------

    private final String tileId;
    private final String blockId;

    /** Primary state: Clojure persistent map. Null until first NBT load or tick. */
    private Object customState = null;

    // Legacy fields kept for backward compatibility
    private double energy = 0.0;
    private double maxEnergy = 1000.0;
    private String status = "STOPPED";
    private ItemStack battery = ItemStack.EMPTY;
    private final Map<String, Object> scriptedState = new HashMap<>();

    /** Cache of active LazyOptionals so invalidateCaps() can properly invalidate them. */
    private final Map<String, LazyOptional<Object>> capCache = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public ScriptedBlockEntity(BlockEntityType<ScriptedBlockEntity> type,
                               BlockPos pos, BlockState state,
                               String tileId, String blockId) {
        super(type, pos, state);
        this.tileId = tileId;
        this.blockId = blockId;
    }

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    public String getTileId()  { return tileId;  }
    public String getBlockId() { return blockId; }

    // -------------------------------------------------------------------------
    // customState (Design-3 primary store)
    // -------------------------------------------------------------------------

    @Nullable
    public Object getCustomState() { return customState; }

    public void setCustomState(Object state) {
        this.customState = state;
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState blockState = getBlockState();
            level.sendBlockUpdated(worldPosition, blockState, blockState, 3);
        }
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        load(tag);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt) {
        CompoundTag tag = pkt.getTag();
        if (tag != null) {
            handleUpdateTag(tag);
        }
    }

    // -------------------------------------------------------------------------
    // Legacy field accessors
    // -------------------------------------------------------------------------

    public double getEnergy()                { return energy; }
    public void   setEnergy(double v)        { this.energy = v; setChanged(); }

    public double getMaxEnergy()             { return maxEnergy; }
    public void   setMaxEnergy(double v)     { this.maxEnergy = v; setChanged(); }

    public String getStatusName()            { return status; }
    public void   setStatus(String s)        { this.status = s != null ? s : "STOPPED"; setChanged(); }

    public ItemStack getBatteryStack()       { return battery; }
    public void setBatteryStack(ItemStack s) {
        this.battery = (s == null || s.isEmpty()) ? ItemStack.EMPTY : s;
        setChanged();
    }

    public void setScriptData(String key, Object value) {
        if (key == null || key.isBlank()) return;
        if (value == null) scriptedState.remove(key);
        else               scriptedState.put(key, value);
        setChanged();
    }

    @Nullable
    public Object getScriptData(String key) { return scriptedState.get(key); }

    public Map<String, Object> getScriptDataSnapshot() { return new HashMap<>(scriptedState); }

    // -------------------------------------------------------------------------
    // NBT persistence
    // -------------------------------------------------------------------------

    private void writeScriptData(CompoundTag tag) {
        if (scriptedState.isEmpty()) return;
        CompoundTag d = new CompoundTag();
        for (Map.Entry<String, Object> e : scriptedState.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Number n)   d.putDouble(e.getKey(), n.doubleValue());
            else if (v instanceof Boolean b) d.putBoolean(e.getKey(), b);
            else if (v instanceof String s)  d.putString(e.getKey(), s);
        }
        if (!d.isEmpty()) tag.put("ScriptedData", d);
    }

    private void readScriptData(CompoundTag tag) {
        scriptedState.clear();
        if (!tag.contains("ScriptedData")) return;
        CompoundTag d = tag.getCompound("ScriptedData");
        for (String key : d.getAllKeys()) {
            if      (d.contains(key, Tag.TAG_STRING)) scriptedState.put(key, d.getString(key));
            else if (d.contains(key, Tag.TAG_BYTE))   scriptedState.put(key, d.getBoolean(key));
            else if (d.contains(key, Tag.TAG_DOUBLE) || d.contains(key, Tag.TAG_FLOAT)
                  || d.contains(key, Tag.TAG_INT)    || d.contains(key, Tag.TAG_LONG)
                  || d.contains(key, Tag.TAG_SHORT))  scriptedState.put(key, d.getDouble(key));
        }
    }

    /** Apply flat data map returned from legacy read-nbt-fn to Java fields. */
    public void setFromData(Object data) {
        if (data == null) return;
        if (data instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String key = String.valueOf(e.getKey());
                Object v   = e.getValue();
                switch (key) {
                    case "energy"      -> { if (v instanceof Number n) this.energy = n.doubleValue(); }
                    case "battery"     -> this.battery = (v instanceof ItemStack s) ? s : ItemStack.EMPTY;
                    case "max-energy"  -> { if (v instanceof Number n) this.maxEnergy = n.doubleValue(); }
                    case "status"      -> { if (v != null) this.status = String.valueOf(v); }
                    default            -> setScriptData(key, v);
                }
            }
            return;
        }
        // Clojure persistent map path
        Object e = RT.get(data, "energy");     if (e instanceof Number n) this.energy = n.doubleValue();
        Object b = RT.get(data, "battery");    this.battery = (b instanceof ItemStack s) ? s : ItemStack.EMPTY;
        Object m = RT.get(data, "max-energy"); if (m instanceof Number n) this.maxEnergy = n.doubleValue();
        Object s = RT.get(data, "status");     if (s != null) this.status = String.valueOf(s);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        try {
            Var readNbt = RT.var("my-mod.block.tile-logic", "read-nbt");
            Object data = readNbt.invoke(tileId, tag);
            if (data != null) {
                customState = data;
                // Also sync legacy Java fields so older code paths still work
                setFromData(data);
            }
            readScriptData(tag);
        } catch (Exception ex) {
            // Graceful fallback for partial class-loading during startup
            if (tag.contains("Energy"))  this.energy  = tag.getDouble("Energy");
            if (tag.contains("Battery")) this.battery = ItemStack.of(tag.getCompound("Battery"));
            readScriptData(tag);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        try {
            Var writeNbt = RT.var("my-mod.block.tile-logic", "write-nbt");
            writeNbt.invoke(tileId, this, tag);
            writeScriptData(tag);
        } catch (Exception ex) {
            // Graceful fallback
            tag.putDouble("Energy", energy);
            if (battery != null && !battery.isEmpty()) tag.put("Battery", battery.save(new CompoundTag()));
            writeScriptData(tag);
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ScriptedBlockEntity be) {
        if (level == null || level.isClientSide) return;
        try {
            Var invokeTick = RT.var("my-mod.block.tile-logic", "invoke-tick");
            invokeTick.invoke(be.tileId, level, pos, state, be);
        } catch (Exception ex) {
            // Silent; log via Clojure if needed
        }
    }

    // -------------------------------------------------------------------------
    // Forge Capability
    // -------------------------------------------------------------------------

    @Nonnull
    @Override
    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction side) {
        String key = CapabilitySlots.getKey(cap);
        if (key != null) {
            LazyOptional<Object> cached = capCache.get(key);
            if (cached != null && cached.isPresent()) {
                return cached.cast();
            }
            try {
                Var getCapFn = RT.var("my-mod.block.tile-logic", "get-capability");
                Object handler = getCapFn.invoke(tileId, key, this, side);
                if (handler != null) {
                    LazyOptional<Object> lo = LazyOptional.of(() -> handler);
                    capCache.put(key, lo);
                    return lo.cast();
                }
            } catch (Exception ex) {
                // fallthrough to super
            }
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        capCache.values().forEach(LazyOptional::invalidate);
        capCache.clear();
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        // Handlers are re-created lazily on next getCapability call; nothing to do here.
    }

    // -------------------------------------------------------------------------
    // Container / WorldlyContainer implementation
    // Delegates to tile-logic/container-* functions when a container is registered.
    // -------------------------------------------------------------------------

    private static final int[] EMPTY_INT_ARRAY = new int[0];

    private @Nullable Object containerOp(String fn, Object... extraArgs) {
        try {
            Var v = RT.var("my-mod.block.tile-logic", fn);
            Object[] args = new Object[1 + extraArgs.length];
            args[0] = tileId;
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
            if (!getItem(i).isEmpty()) return false;
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

    // WorldlyContainer
    @Nonnull
    @Override
    public int[] getSlotsForFace(@Nonnull Direction side) {
        Object r = containerOp("container-slots-for-face", this, side);
        if (r instanceof int[] arr) return arr;
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
