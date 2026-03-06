package my_mod.block;

import my_mod.block.entity.SolarGenBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Solar Generator block (Fabric 1.20.1 / official Mojang mappings).
 *
 * RenderShape is INVISIBLE; actual model is rendered via BlockEntityRenderer.
 */
public class SolarGenBlock extends BaseEntityBlock {
    public SolarGenBlock(Properties props) {
        super(props);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SolarGenBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return (lvl, p, st, be) -> {
            if (be instanceof SolarGenBlockEntity s) {
                SolarGenBlockEntity.serverTick(lvl, p, st, s);
            }
        };
    }
}

