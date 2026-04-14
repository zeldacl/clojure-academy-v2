package cn.li.forge1201.block;

import cn.li.forge1201.block.entity.ScriptedBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Merged block: generic block with ScriptedBlockEntity and optional dynamic BlockState.
 * Replaces ScriptedEntityBlock and ScriptedDynamicEntityBlock.
 */
public class ScriptedBlock extends BaseEntityBlock {

    private static final Map<String, List<Property<?>>> BLOCK_PROPERTIES = new HashMap<>();
    private static final ThreadLocal<InitContext> INIT_CONTEXT = new ThreadLocal<>();

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

    public static ScriptedBlock create(String blockId, String tileId,
                                      List<Property<?>> properties,
                                      BlockBehaviour.Properties behaviourProperties) {
        INIT_CONTEXT.set(new InitContext(blockId, tileId, properties != null ? properties : Collections.emptyList()));
        try {
            return new ScriptedBlock(blockId, tileId, behaviourProperties);
        } finally {
            INIT_CONTEXT.remove();
        }
    }

    public static ScriptedBlock create(String blockId, List<Property<?>> properties,
                                      BlockBehaviour.Properties behaviourProperties) {
        return create(blockId, blockId, properties, behaviourProperties);
    }

    private final String blockId;
    private final String tileId;
    private final boolean hasDynamicProps;

    public ScriptedBlock(String blockId, Properties props) {
        this(blockId, blockId, props);
    }

    public ScriptedBlock(String blockId, String tileId, Properties props) {
        super(props);
        this.blockId = blockId;
        this.tileId = tileId;
        InitContext ctx = INIT_CONTEXT.get();
        if (ctx != null) {
            this.hasDynamicProps = ctx.properties != null && !ctx.properties.isEmpty();
        } else {
            List<Property<?>> blockProps = BLOCK_PROPERTIES.getOrDefault(blockId, Collections.emptyList());
            this.hasDynamicProps = blockProps != null && !blockProps.isEmpty();
        }
    }

    public String getBlockId() {
        return blockId;
    }

    public String getTileId() {
        return tileId;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        InitContext ctx = INIT_CONTEXT.get();
        List<Property<?>> properties = Collections.emptyList();
        if (ctx != null) {
            properties = ctx.properties != null ? ctx.properties : Collections.emptyList();
            BLOCK_PROPERTIES.put(ctx.blockId, properties);
        } else if (blockId != null) {
            properties = BLOCK_PROPERTIES.getOrDefault(blockId, Collections.emptyList());
        }
        if (properties != null && !properties.isEmpty()) {
            builder.add(properties.toArray(new Property<?>[0]));
        }
    }

    private BlockState withHorizontalFacing(BlockState state, @Nullable BlockPlaceContext context) {
        if (context == null) {
            return state;
        }
        Property<?> prop = this.getStateDefinition().getProperty("facing");
        if (prop instanceof DirectionProperty directionProperty) {
            Direction placedFacing = context.getHorizontalDirection().getOpposite();
            if (directionProperty.getPossibleValues().contains(placedFacing)) {
                return state.setValue(directionProperty, placedFacing);
            }
        }
        return state;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return withHorizontalFacing(this.defaultBlockState(), context);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return super.propagatesSkylightDown(state, level, pos);
    }

    @Override
    public int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return super.getLightBlock(state, level, pos);
    }

    @Override
    public float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return super.getShadeBrightness(state, level, pos);
    }

    @Override
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return super.getOcclusionShape(state, level, pos);
    }

    @Override
    public VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return super.getVisualShape(state, level, pos, context);
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return super.useShapeForLightOcclusion(state);
    }

    @Override
    public boolean skipRendering(BlockState state, BlockState adjacentBlockState, Direction side) {
        return super.skipRendering(state, adjacentBlockState, side);
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

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!level.isClientSide && blockEntity instanceof Container container) {
                Containers.dropContents(level, pos, container);
                level.updateNeighbourForOutputSignal(pos, this);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
