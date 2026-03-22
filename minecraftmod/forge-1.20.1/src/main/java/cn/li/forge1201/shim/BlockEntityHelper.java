package cn.li.forge1201.shim;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class BlockEntityHelper {
    private BlockEntityHelper() {
    }

    public static BlockPos getPosition(Object value) {
        if (value instanceof BlockEntity blockEntity) {
            return blockEntity.getBlockPos();
        }
        return null;
    }
}