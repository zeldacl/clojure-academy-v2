package cn.li.forge1201.block;

import cn.li.forge1201.block.entity.ScriptedBlockEntity;
import cn.li.mc1201.block.ScriptedCarrierBlockBase;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Merged block: generic block with ScriptedBlockEntity and optional dynamic BlockState.
 * Replaces ScriptedEntityBlock and ScriptedDynamicEntityBlock.
 */
public class ScriptedBlock extends ScriptedCarrierBlockBase {

    private static final Map<String, List<Property<?>>> BLOCK_PROPERTIES = new HashMap<>();
    /**
     * Blocks that should be rendered by BER only (no static block model pass).
     * Matches original AcademyCraft PhaseGen behavior (invisible block model + TESR/BER).
     */
    private static final Set<String> BER_ONLY_BLOCK_IDS = Set.of(
        // Single-block BER-only blocks
        "phase-gen",
        "cat-engine",
        "solar-gen",

        // Wind generator multiblock (controller + parts + pillar)
        "wind-gen-base",
        "wind-gen-base-part",
        "wind-gen-main",
        "wind-gen-main-part",
        "wind-gen-pillar",

        // Matrix multiblock
        "wireless-matrix",
        "wireless-matrix-part",

        // Developer station multiblock
        "developer-normal",
        "developer-normal-part",
        "developer-advanced",
        "developer-advanced-part"
    );
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

    private final boolean hasDynamicProps;

    public ScriptedBlock(String blockId, Properties props) {
        this(blockId, blockId, props);
    }

    public ScriptedBlock(String blockId, String tileId, Properties props) {
        super(blockId, tileId, props);
        InitContext ctx = INIT_CONTEXT.get();
        if (ctx != null) {
            this.hasDynamicProps = ctx.properties != null && !ctx.properties.isEmpty();
        } else {
            List<Property<?>> blockProps = BLOCK_PROPERTIES.getOrDefault(blockId, Collections.emptyList());
            this.hasDynamicProps = blockProps != null && !blockProps.isEmpty();
        }
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
        String normalizedId = blockId == null
            ? ""
            : blockId.toLowerCase(Locale.ROOT).replace('_', '-');
        if (BER_ONLY_BLOCK_IDS.contains(normalizedId)) {
            return RenderShape.ENTITYBLOCK_ANIMATED;
        }
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

    @Override
    protected BlockEntity createScriptedBlockEntity(BlockPos pos, BlockState state) {
        BlockEntityType<ScriptedBlockEntity> type = ScriptedBlockEntity.getType(tileId);
        return type != null ? new ScriptedBlockEntity(type, pos, state, tileId, blockId) : null;
    }

    @Override
    protected void serverTickScripted(Level level, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        if (blockEntity instanceof ScriptedBlockEntity scripted) {
            ScriptedBlockEntity.serverTick(level, pos, state, scripted);
        }
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
