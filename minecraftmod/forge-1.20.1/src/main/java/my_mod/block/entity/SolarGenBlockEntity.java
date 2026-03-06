package my_mod.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Solar Generator block entity (Forge 1.20.1 / Mojmaps).
 *
 * Notes:
 * - BlockEntityType is injected by platform registration via {@link #TYPE}.
 * - Core Clojure code talks to this entity via reflection:
 *   getEnergy(), getMaxEnergy(), getStatusName().
 */
public class SolarGenBlockEntity extends BlockEntity {
    public static BlockEntityType<SolarGenBlockEntity> TYPE;

    private static final double MAX_ENERGY = 1000.0;

    private double energy = 0.0;
    private ItemStack battery = ItemStack.EMPTY;

    public SolarGenBlockEntity(BlockPos pos, BlockState state) {
        super(TYPE, pos, state);
    }

    public double getEnergy() {
        return energy;
    }

    public double getMaxEnergy() {
        return MAX_ENERGY;
    }

    public ItemStack getBatteryStack() {
        return battery;
    }

    public void setBatteryStack(ItemStack stack) {
        this.battery = (stack == null) ? ItemStack.EMPTY : stack;
        setChanged();
    }

    public String getStatusName() {
        Level level = getLevel();
        if (level == null) return "STOPPED";
        if (!canGenerate(level, worldPosition)) return "STOPPED";
        return level.isRaining() ? "WEAK" : "STRONG";
    }

    private static boolean canGenerate(Level level, BlockPos pos) {
        long time = level.getDayTime() % 24000L;
        boolean isDay = time >= 0L && time <= 12500L;
        return isDay && level.canSeeSky(pos.above());
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SolarGenBlockEntity be) {
        if (level == null || level.isClientSide) return;

        double bright = canGenerate(level, pos) ? 1.0 : 0.0;
        if (bright > 0.0 && level.isRaining()) {
            bright *= 0.2;
        }

        double gen = bright * 3.0;
        if (gen > 0.0) {
            double newEnergy = Math.min(MAX_ENERGY, be.energy + gen);
            if (newEnergy != be.energy) {
                be.energy = newEnergy;
                be.setChanged();
            }
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("Energy")) {
            this.energy = tag.getDouble("Energy");
        }
        if (tag.contains("Battery")) {
            this.battery = ItemStack.of(tag.getCompound("Battery"));
        } else {
            this.battery = ItemStack.EMPTY;
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putDouble("Energy", this.energy);
        if (this.battery != null && !this.battery.isEmpty()) {
            tag.put("Battery", this.battery.save(new CompoundTag()));
        }
    }
}

