package cn.li.mc1201.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import java.util.List;

/**
 * Loader-agnostic scripted carrier block with injected platform behavior.
 */
public class SharedScriptedBlock extends ScriptedCarrierBlockBase {

    private final ScriptedBlockEntityFactory blockEntityFactory;
    private final ScriptedServerTickDispatcher serverTickDispatcher;
    private final ScriptedRenderShapeResolver renderShapeResolver;

    public static SharedScriptedBlock create(String blockId,
                                             String tileId,
                                             List<Property<?>> properties,
                                             BlockBehaviour.Properties behaviourProperties,
                                             ScriptedBlockEntityFactory blockEntityFactory,
                                             ScriptedServerTickDispatcher serverTickDispatcher,
                                             ScriptedRenderShapeResolver renderShapeResolver) {
        return DynamicBlockPropertyRegistry.withInitContext(
            SharedScriptedBlock.class,
            blockId,
            properties,
            () -> new SharedScriptedBlock(
                blockId,
                tileId,
                behaviourProperties,
                blockEntityFactory,
                serverTickDispatcher,
                renderShapeResolver
            )
        );
    }

    public static SharedScriptedBlock create(String blockId,
                                             List<Property<?>> properties,
                                             BlockBehaviour.Properties behaviourProperties,
                                             ScriptedBlockEntityFactory blockEntityFactory,
                                             ScriptedServerTickDispatcher serverTickDispatcher,
                                             ScriptedRenderShapeResolver renderShapeResolver) {
        return create(
            blockId,
            blockId,
            properties,
            behaviourProperties,
            blockEntityFactory,
            serverTickDispatcher,
            renderShapeResolver
        );
    }

    public SharedScriptedBlock(String blockId,
                               String tileId,
                               Properties props,
                               ScriptedBlockEntityFactory blockEntityFactory,
                               ScriptedServerTickDispatcher serverTickDispatcher,
                               ScriptedRenderShapeResolver renderShapeResolver) {
        super(blockId, tileId, props);
        this.blockEntityFactory = blockEntityFactory;
        this.serverTickDispatcher = serverTickDispatcher;
        this.renderShapeResolver = renderShapeResolver;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        List<Property<?>> properties = DynamicBlockPropertyRegistry.resolveForDefinition(SharedScriptedBlock.class, blockId);
        if (properties != null && !properties.isEmpty()) {
            builder.add(properties.toArray(new Property<?>[0]));
        }
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return BlockPlacementHelper.withHorizontalFacing(this, this.defaultBlockState(), context);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        if (renderShapeResolver == null) {
            return RenderShape.MODEL;
        }
        RenderShape resolved = renderShapeResolver.resolve(blockId, state);
        return resolved != null ? resolved : RenderShape.MODEL;
    }

    @Override
    protected BlockEntity createScriptedBlockEntity(BlockPos pos, BlockState state) {
        return blockEntityFactory.create(tileId, blockId, pos, state);
    }

    @Override
    protected void serverTickScripted(Level level, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        serverTickDispatcher.tick(level, pos, state, blockEntity);
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

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return super.propagatesSkylightDown(state, level, pos);
    }

    @Override
    public int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return super.getLightBlock(state, level, pos);
    }
}
