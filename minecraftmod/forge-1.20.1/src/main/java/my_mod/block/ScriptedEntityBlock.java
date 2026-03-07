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
 * Generic block that uses ScriptedBlockEntity for tile-id–driven logic.
 * Each block instance carries a block-id (for per-block behavior) and a tile-id
 * (for shared BlockEntityType registration across multiple blocks).
 */
public class ScriptedEntityBlock extends BaseEntityBlock {

    private final String blockId;
    private final String tileId;

    public ScriptedEntityBlock(String blockId, Properties props) {
        this(blockId, blockId, props);
    }

    public ScriptedEntityBlock(String blockId, String tileId, Properties props) {
        super(props);
        this.blockId = blockId;
        this.tileId = tileId;
    }

    public String getBlockId() {
        return blockId;
    }

    public String getTileId() {
        return tileId;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        BlockEntityType<ScriptedBlockEntity> type = ScriptedBlockEntity.getType(tileId);
        return type != null ? new ScriptedBlockEntity(type, pos, state, tileId, blockId) : null;
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
