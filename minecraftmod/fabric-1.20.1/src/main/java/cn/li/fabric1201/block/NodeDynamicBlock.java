package cn.li.fabric1201.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NodeDynamicBlock extends Block {
    private static final Map<String, List<Property<?>>> blockProperties = new HashMap<>();

    private static final ThreadLocal<InitContext> initContext = new ThreadLocal<>();

    private String blockId;

    private static final class InitContext {
        final String blockId;
        final List<Property<?>> properties;

        InitContext(String blockId, List<Property<?>> properties) {
            this.blockId = blockId;
            this.properties = properties;
        }
    }

    public static NodeDynamicBlock create(String blockId,
                                          List<Property<?>> properties,
                                          BlockBehaviour.Properties behaviourProperties) {
        initContext.set(new InitContext(blockId, properties));
        try {
            return new NodeDynamicBlock(behaviourProperties);
        } finally {
            initContext.remove();
        }
    }

    public NodeDynamicBlock(BlockBehaviour.Properties properties) {
        super(properties);

        InitContext ctx = initContext.get();
        if (ctx != null) {
            this.blockId = ctx.blockId;
            blockProperties.put(ctx.blockId, ctx.properties);
        }
    }

    public void setBlockIdAndProperties(String blockId, List<Property<?>> properties) {
        this.blockId = blockId;
        blockProperties.put(blockId, properties);
        this.registerDefaultState(this.stateDefinition.any());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        InitContext ctx = initContext.get();
        List<Property<?>> properties;

        if (ctx != null) {
            properties = ctx.properties;
            if (ctx.blockId != null) {
                this.blockId = ctx.blockId;
                blockProperties.put(ctx.blockId, ctx.properties);
            }
        } else {
            properties = blockProperties.getOrDefault(this.blockId, Collections.emptyList());
        }

        if (properties != null && !properties.isEmpty()) {
            builder.add(properties.toArray(new Property<?>[0]));
        }
    }
}
