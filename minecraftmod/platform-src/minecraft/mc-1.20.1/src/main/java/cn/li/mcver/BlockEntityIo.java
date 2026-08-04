package cn.li.mcver;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Version seam for block-entity persistence.
 * Contract includes a registries handle that maps to HolderLookup.Provider on 1.21.1;
 * 1.20.1 ignores it. saveAdditional remains protected on both versions — subclasses
 * call {@link #writeAdditional} from their override.
 */
public final class BlockEntityIo {
    private BlockEntityIo() {
    }

    /**
     * Opaque registries handle. On 1.20.1 this is unused; callers may pass {@link #NO_REGISTRIES}.
     */
    public interface Registries {
    }

    public static final Registries NO_REGISTRIES = new Registries() {
    };

    public static void load(BlockEntity be, CompoundTag tag, Registries registries) {
        be.load(tag);
    }

    public static CompoundTag getUpdateTag(BlockEntity be, Registries registries) {
        return be.getUpdateTag();
    }

    /**
     * Callback used by versioned block-entity subclasses when writing NBT.
     * On 1.21.1 the registries argument is a real HolderLookup.Provider.
     */
    @FunctionalInterface
    public interface AdditionalWriter {
        void write(CompoundTag tag, Registries registries);
    }
}
