package cn.li.mc262.block;

import net.minecraft.world.level.block.state.BlockBehaviour;

public final class SharedBootstrapBlockHelper {
    private SharedBootstrapBlockHelper() {}
    public static BlockBehaviour.Properties defaultProperties() {
        return BlockBehaviour.Properties.of();
    }
}
