package my_mod.block;

import my_mod.block.entity.ScriptedBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic scripted BE block that also supports dynamic BlockState properties.
 */
public class ScriptedDynamicEntityBlock extends BaseEntityBlock {
    private static final Map<String, List<Property<?>>> BLOCK_PROPERTIES = new HashMap<>();
    private static final ThreadLocal<InitContext> INIT_CONTEXT = new ThreadLocal<>();

    private final String blockId;
    private final String tileId;

    private static final class InitContext {
        final String blockId;
        final String tileId;
        final List<Property<?>> properties;

        InitContext(String blockId, String tileId, List<Property<?>> properties) {
            this.blockId = blockId;
            this.tileId = tileId;
            this.properties = properties;
        }
    }

    public static ScriptedDynamicEntityBlock create(String blockId,
                                                    String tileId,
                                                    List<Property<?>> properties,
                                                    BlockBehaviour.Properties behaviourProperties) {
        INIT_CONTEXT.set(new InitContext(blockId, tileId, properties));
        try {
            return new ScriptedDynamicEntityBlock(blockId, tileId, behaviourProperties);
        } finally {
            INIT_CONTEXT.remove();
        }
    }

    public static ScriptedDynamicEntityBlock create(String blockId,
                                                    List<Property<?>> properties,
                                                    BlockBehaviour.Properties behaviourProperties) {
        return create(blockId, blockId, properties, behaviourProperties);
    }

    public ScriptedDynamicEntityBlock(String blockId, String tileId, Properties props) {
        super(props);
        this.blockId = blockId;
        this.tileId = tileId;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        InitContext ctx = INIT_CONTEXT.get();
        List<Property<?>> properties = Collections.emptyList();
        if (ctx != null) {
            properties = ctx.properties;
            BLOCK_PROPERTIES.put(ctx.blockId, ctx.properties);
        } else if (blockId != null) {
            properties = BLOCK_PROPERTIES.getOrDefault(blockId, Collections.emptyList());
        }
        if (properties != null && !properties.isEmpty()) {
            builder.add(properties.toArray(new Property<?>[0]));
        }
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
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
            if (be instanceof ScriptedBlockEntity scripted) {
                ScriptedBlockEntity.serverTick(lvl, pos, st, scripted);
            }
        };
    }
}
