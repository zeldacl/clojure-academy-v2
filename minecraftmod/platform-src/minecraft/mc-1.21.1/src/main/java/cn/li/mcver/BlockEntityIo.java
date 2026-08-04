package cn.li.mcver;

import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Version seam for block-entity persistence.
 * {@link Registries} wraps {@link HolderLookup.Provider} for 1.21.1
 * {@code loadAdditional}/{@code saveAdditional}/{@code getUpdateTag} signatures.
 * saveAdditional remains protected — subclasses call {@link AdditionalWriter}
 * from their override.
 */
public final class BlockEntityIo {
    private BlockEntityIo() {
    }

    /**
     * Registries handle wrapping {@link HolderLookup.Provider}.
     */
    @FunctionalInterface
    public interface Registries {
        HolderLookup.Provider asLookup();
    }

    /**
     * Wrap a real {@link HolderLookup.Provider} for seam callers.
     */
    public static Registries of(HolderLookup.Provider provider) {
        Objects.requireNonNull(provider, "provider");
        return () -> provider;
    }

    /**
     * Sentinel kept for cross-version call sites. On 1.21.1 a real provider
     * is required — use {@link #of(HolderLookup.Provider)}.
     */
    public static final Registries NO_REGISTRIES = () -> {
        throw new IllegalStateException(
            "BlockEntityIo on 1.21.1 requires HolderLookup.Provider; use BlockEntityIo.of(provider)");
    };

    public static void load(BlockEntity be, CompoundTag tag, Registries registries) {
        be.loadWithComponents(tag, registries.asLookup());
    }

    public static CompoundTag getUpdateTag(BlockEntity be, Registries registries) {
        return be.getUpdateTag(registries.asLookup());
    }

    /**
     * Callback used by versioned block-entity subclasses when writing NBT.
     */
    @FunctionalInterface
    public interface AdditionalWriter {
        void write(CompoundTag tag, Registries registries);
    }
}
