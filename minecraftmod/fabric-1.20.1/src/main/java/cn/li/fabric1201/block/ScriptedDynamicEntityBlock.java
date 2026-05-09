package cn.li.fabric1201.block;

import cn.li.fabric1201.block.entity.ScriptedBlockEntity;
import cn.li.mc1201.block.DynamicBlockPropertyRegistry;
import cn.li.mc1201.block.ScriptedCarrierBlockBase;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.List;

public class ScriptedDynamicEntityBlock extends ScriptedCarrierBlockBase {
    public static ScriptedDynamicEntityBlock create(String blockId,
                                                    String tileId,
                                                    List<Property<?>> properties,
                                                    BlockBehaviour.Properties behaviourProperties) {
        return DynamicBlockPropertyRegistry.withInitContext(
            ScriptedDynamicEntityBlock.class,
            blockId,
            properties,
            () -> new ScriptedDynamicEntityBlock(blockId, tileId, behaviourProperties)
        );
    }

    public static ScriptedDynamicEntityBlock create(String blockId,
                                                    List<Property<?>> properties,
                                                    BlockBehaviour.Properties behaviourProperties) {
        return create(blockId, blockId, properties, behaviourProperties);
    }

    public ScriptedDynamicEntityBlock(String blockId, String tileId, Properties props) {
        super(blockId, tileId, props);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        List<Property<?>> properties = DynamicBlockPropertyRegistry.resolveForDefinition(ScriptedDynamicEntityBlock.class, blockId);
        if (properties != null && !properties.isEmpty()) {
            builder.add(properties.toArray(new Property<?>[0]));
        }
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
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
}
