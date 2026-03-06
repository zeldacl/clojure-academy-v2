package my_mod.block.entity;

import clojure.lang.RT;
import clojure.lang.Var;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Generic block entity driven by Clojure tile-logic (tick + NBT).
 * Platform registers BlockEntityType per block-id and stores types here for lookup.
 */
public class ScriptedBlockEntity extends BlockEntity {

    private static final Map<String, BlockEntityType<ScriptedBlockEntity>> TYPES = new HashMap<>();

    public static void registerType(String blockId, BlockEntityType<ScriptedBlockEntity> type) {
        TYPES.put(blockId, type);
    }

    @Nullable
    public static BlockEntityType<ScriptedBlockEntity> getType(String blockId) {
        return TYPES.get(blockId);
    }

    private final String blockId;
    private double energy = 0.0;
    private double maxEnergy = 1000.0;
    private String status = "STOPPED";
    private ItemStack battery = ItemStack.EMPTY;

    public ScriptedBlockEntity(BlockEntityType<ScriptedBlockEntity> type, BlockPos pos, BlockState state, String blockId) {
        super(type, pos, state);
        this.blockId = blockId;
    }

    public String getBlockId() {
        return blockId;
    }

    public double getEnergy() {
        return energy;
    }

    public void setEnergy(double energy) {
        this.energy = energy;
        setChanged();
    }

    public double getMaxEnergy() {
        return maxEnergy;
    }

    public void setMaxEnergy(double maxEnergy) {
        this.maxEnergy = maxEnergy;
        setChanged();
    }

    public String getStatusName() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status != null ? status : "STOPPED";
        setChanged();
    }

    public ItemStack getBatteryStack() {
        return battery;
    }

    public void setBatteryStack(ItemStack stack) {
        this.battery = stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack;
        setChanged();
    }

    /** Apply data map returned from Clojure read-nbt. */
    @SuppressWarnings("unchecked")
    public void setFromData(Object data) {
        if (data == null) return;
        Object e = RT.get(data, "energy");
        if (e instanceof Number) this.energy = ((Number) e).doubleValue();
        Object b = RT.get(data, "battery");
        this.battery = (b instanceof ItemStack) ? (ItemStack) b : ItemStack.EMPTY;
        Object m = RT.get(data, "max-energy");
        if (m instanceof Number) this.maxEnergy = ((Number) m).doubleValue();
        Object s = RT.get(data, "status");
        if (s != null) this.status = String.valueOf(s);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        try {
            Var readNbt = RT.var("my-mod.block.tile-logic", "read-nbt");
            Object data = readNbt.invoke(blockId, tag);
            setFromData(data);
        } catch (Exception e) {
            if (tag.contains("Energy")) this.energy = tag.getDouble("Energy");
            if (tag.contains("Battery")) this.battery = ItemStack.of(tag.getCompound("Battery"));
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        try {
            Var writeNbt = RT.var("my-mod.block.tile-logic", "write-nbt");
            writeNbt.invoke(blockId, this, tag);
        } catch (Exception e) {
            tag.putDouble("Energy", energy);
            if (battery != null && !battery.isEmpty()) {
                tag.put("Battery", battery.save(new CompoundTag()));
            }
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ScriptedBlockEntity be) {
        if (level == null || level.isClientSide) return;
        try {
            Var invokeTick = RT.var("my-mod.block.tile-logic", "invoke-tick");
            invokeTick.invoke(be.blockId, level, pos, state, be);
        } catch (Exception e) {
            // Log if needed
        }
    }
}
