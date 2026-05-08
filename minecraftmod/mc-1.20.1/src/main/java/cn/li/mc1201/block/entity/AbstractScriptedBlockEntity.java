package cn.li.mc1201.block.entity;

import clojure.lang.RT;
import clojure.lang.Var;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Shared scripted block-entity core for 1.20.1 loaders.
 *
 * <p>Contains the common Clojure tile-logic integration (NBT + tick + state)
 * while loader-specific features (Forge capabilities/container wiring, etc.)
 * remain in platform subclasses.</p>
 */
public abstract class AbstractScriptedBlockEntity extends BlockEntity {

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

    protected AbstractScriptedBlockEntity(BlockEntityType<?> type,
                                          BlockPos pos,
                                          BlockState state,
                                          String tileId,
                                          String blockId) {
        super(type, pos, state);
        this.tileId = tileId;
        this.blockId = blockId;
    }

    protected String tileLogicNamespace() {
        return "cn.li.mcmod.block.tile-logic";
    }

    public String getTileId() {
        return tileId;
    }

    public String getBlockId() {
        return blockId;
    }

    public Object getCustomState() {
        return customState;
    }

    public void setCustomState(Object state) {
        if (Objects.equals(this.customState, state)) {
            return;
        }
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

    public void handleUpdateTag(CompoundTag tag) {
        load(tag);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt) {
        CompoundTag tag = pkt.getTag();
        if (tag != null) {
            handleUpdateTag(tag);
        }
    }

    public double getEnergy() {
        return energy;
    }

    public void setEnergy(double value) {
        if (this.energy == value) {
            return;
        }
        this.energy = value;
        setChanged();
    }

    public double getMaxEnergy() {
        return maxEnergy;
    }

    public void setMaxEnergy(double value) {
        if (this.maxEnergy == value) {
            return;
        }
        this.maxEnergy = value;
        setChanged();
    }

    public String getStatusName() {
        return status;
    }

    public void setStatus(String value) {
        String newStatus = value != null ? value : "STOPPED";
        if (newStatus.equals(this.status)) {
            return;
        }
        this.status = newStatus;
        setChanged();
    }

    public ItemStack getBatteryStack() {
        return battery;
    }

    public void setBatteryStack(ItemStack stack) {
        ItemStack newBattery = (stack == null || stack.isEmpty()) ? ItemStack.EMPTY : stack;
        if (ItemStack.matches(this.battery, newBattery)) {
            return;
        }
        this.battery = newBattery;
        setChanged();
    }

    public void setScriptData(String key, Object value) {
        if (key == null || key.isBlank()) {
            return;
        }
        if (value == null) {
            scriptedState.remove(key);
        } else {
            scriptedState.put(key, value);
        }
        setChanged();
    }

    public Object getScriptData(String key) {
        return scriptedState.get(key);
    }

    public Map<String, Object> getScriptDataSnapshot() {
        return new HashMap<>(scriptedState);
    }

    private void writeScriptData(CompoundTag tag) {
        if (scriptedState.isEmpty()) {
            return;
        }
        CompoundTag dataTag = new CompoundTag();
        for (Map.Entry<String, Object> entry : scriptedState.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Number number) {
                dataTag.putDouble(key, number.doubleValue());
            } else if (value instanceof Boolean bool) {
                dataTag.putBoolean(key, bool);
            } else if (value instanceof String str) {
                dataTag.putString(key, str);
            }
        }
        if (!dataTag.isEmpty()) {
            tag.put("ScriptedData", dataTag);
        }
    }

    private void readScriptData(CompoundTag tag) {
        scriptedState.clear();
        if (!tag.contains("ScriptedData")) {
            return;
        }
        CompoundTag dataTag = tag.getCompound("ScriptedData");
        for (String key : dataTag.getAllKeys()) {
            if (dataTag.contains(key, Tag.TAG_STRING)) {
                scriptedState.put(key, dataTag.getString(key));
            } else if (dataTag.contains(key, Tag.TAG_BYTE)) {
                scriptedState.put(key, dataTag.getBoolean(key));
            } else if (dataTag.contains(key, Tag.TAG_DOUBLE)
                    || dataTag.contains(key, Tag.TAG_FLOAT)
                    || dataTag.contains(key, Tag.TAG_INT)
                    || dataTag.contains(key, Tag.TAG_LONG)
                    || dataTag.contains(key, Tag.TAG_SHORT)) {
                scriptedState.put(key, dataTag.getDouble(key));
            }
        }
    }

    /** Apply flat data map returned from legacy read-nbt-fn to Java fields. */
    public void setFromData(Object data) {
        if (data == null) {
            return;
        }
        if (data instanceof Map<?, ?> dataMap) {
            for (Map.Entry<?, ?> entry : dataMap.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                switch (key) {
                    case "energy" -> {
                        if (value instanceof Number number) {
                            this.energy = number.doubleValue();
                        }
                    }
                    case "battery" -> this.battery = (value instanceof ItemStack stack) ? stack : ItemStack.EMPTY;
                    case "max-energy" -> {
                        if (value instanceof Number number) {
                            this.maxEnergy = number.doubleValue();
                        }
                    }
                    case "status" -> {
                        if (value != null) {
                            this.status = String.valueOf(value);
                        }
                    }
                    default -> setScriptData(key, value);
                }
            }
            return;
        }
        Object energyValue = RT.get(data, "energy");
        if (energyValue instanceof Number number) {
            this.energy = number.doubleValue();
        }
        Object batteryValue = RT.get(data, "battery");
        this.battery = (batteryValue instanceof ItemStack stack) ? stack : ItemStack.EMPTY;
        Object maxEnergyValue = RT.get(data, "max-energy");
        if (maxEnergyValue instanceof Number number) {
            this.maxEnergy = number.doubleValue();
        }
        Object statusValue = RT.get(data, "status");
        if (statusValue != null) {
            this.status = String.valueOf(statusValue);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        try {
            Var readNbt = RT.var(tileLogicNamespace(), "read-nbt");
            Object data = readNbt.invoke(tileId, tag);
            if (data != null) {
                customState = data;
                setFromData(data);
            }
            readScriptData(tag);
        } catch (Exception ex) {
            if (tag.contains("Energy")) {
                this.energy = tag.getDouble("Energy");
            }
            if (tag.contains("Battery")) {
                this.battery = ItemStack.of(tag.getCompound("Battery"));
            }
            readScriptData(tag);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        try {
            Var writeNbt = RT.var(tileLogicNamespace(), "write-nbt");
            writeNbt.invoke(tileId, this, tag);
            writeScriptData(tag);
        } catch (Exception ex) {
            tag.putDouble("Energy", energy);
            if (battery != null && !battery.isEmpty()) {
                tag.put("Battery", battery.save(new CompoundTag()));
            }
            writeScriptData(tag);
        }
    }

    protected static void invokeServerTick(Level level, BlockPos pos, BlockState state, AbstractScriptedBlockEntity be) {
        if (level == null || level.isClientSide || be == null) {
            return;
        }
        try {
            Var invokeTick = RT.var(be.tileLogicNamespace(), "invoke-tick");
            invokeTick.invoke(be.tileId, level, pos, state, be);
        } catch (Exception ex) {
            // Silent; log via Clojure if needed
        }
    }
}
