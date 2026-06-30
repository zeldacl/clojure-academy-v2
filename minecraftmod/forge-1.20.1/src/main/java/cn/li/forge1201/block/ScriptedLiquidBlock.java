package cn.li.forge1201.block;

import cn.li.forge1201.block.entity.ScriptedBlockEntity;
import cn.li.mc1201.block.IScriptedBlock;
import cn.li.mc1201.block.logic.TileLogicBundle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;

import javax.annotation.Nullable;

import java.util.function.Supplier;

/**
 * A LiquidBlock that implements EntityBlock so the scripted TESR system
 * can attach a custom renderer. Used for fluid blocks that need animated
 * overlay rendering (e.g. ImagPhase Liquid).
 *
 * <p>Extends {@link LiquidBlock} to preserve all fluid behavior (collision,
 * level change, bucket pickup). Delegates block entity creation to the
 * existing {@link ScriptedBlockEntity} infrastructure.</p>
 */
public class ScriptedLiquidBlock extends LiquidBlock implements EntityBlock, IScriptedBlock {

    private final String blockId;
    private final String tileId;
    private volatile TileLogicBundle tileLogic = TileLogicBundle.EMPTY;

    public ScriptedLiquidBlock(Supplier<? extends FlowingFluid> fluidSupplier,
                               String blockId,
                               String tileId,
                               Properties props) {
        super(fluidSupplier, props);
        this.blockId = blockId;
        this.tileId = tileId;
    }

    @Override
    public String getBlockId() {
        return blockId;
    }

    @Override
    public String getTileId() {
        return tileId;
    }

    @Override
    public TileLogicBundle getTileLogic() {
        return tileLogic;
    }

    @Override
    public void installTileLogic(TileLogicBundle bundle) {
        this.tileLogic = bundle != null ? bundle : TileLogicBundle.EMPTY;
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        BlockEntityType<ScriptedBlockEntity> type = ScriptedBlockEntity.getType(tileId);
        if (type == null) {
            return null;
        }
        return new ScriptedBlockEntity(type, pos, state, tileId, blockId);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level,
            BlockState state,
            BlockEntityType<T> actualType) {
        if (level.isClientSide) {
            return null;
        }
        BlockEntityType<ScriptedBlockEntity> expected = ScriptedBlockEntity.getType(tileId);
        if (expected != null && actualType == expected) {
            return (lvl, pos, st, be) -> {
                if (be instanceof ScriptedBlockEntity scripted) {
                    ScriptedBlockEntity.serverTick(lvl, pos, st, scripted);
                }
            };
        }
        return null;
    }
}
