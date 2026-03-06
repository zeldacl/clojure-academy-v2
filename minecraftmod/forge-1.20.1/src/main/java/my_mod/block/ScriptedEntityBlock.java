package my_mod.block;

import my_mod.block.entity.ScriptedBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * Generic block that uses ScriptedBlockEntity for block-id–driven logic.
 * One class per mod; each block instance carries its block-id for BE creation and ticking.
 */
public class ScriptedEntityBlock extends BaseEntityBlock {

    private final String blockId;

    public ScriptedEntityBlock(String blockId, Properties props) {
        super(props);
        this.blockId = blockId;
    }

    public String getBlockId() {
        return blockId;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        BlockEntityType<ScriptedBlockEntity> type = ScriptedBlockEntity.getType(blockId);
        return type != null ? new ScriptedBlockEntity(type, pos, state, blockId) : null;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return (lvl, pos, st, be) -> {
            if (be instanceof ScriptedBlockEntity s) {
                ScriptedBlockEntity.serverTick(lvl, pos, st, s);
            }
        };
    }
}
