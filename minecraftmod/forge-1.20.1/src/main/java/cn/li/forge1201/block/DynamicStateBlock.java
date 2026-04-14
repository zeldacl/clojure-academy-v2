package cn.li.forge1201.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.core.Direction;

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Block with dynamic BlockState properties, no BlockEntity.
 * Replaces NodeDynamicBlock (renamed).
 */
public class DynamicStateBlock extends Block {

    private static final Map<String, List<Property<?>>> blockProperties = new HashMap<>();
    private static final ThreadLocal<InitContext> initContext = new ThreadLocal<>();

    private static final class InitContext {
        final String blockId;
        final List<Property<?>> properties;

        InitContext(String blockId, List<Property<?>> properties) {
            this.blockId = blockId;
            this.properties = properties;
        }
    }

    public static DynamicStateBlock create(String blockId,
                                          List<Property<?>> properties,
                                          BlockBehaviour.Properties behaviourProperties) {
        initContext.set(new InitContext(blockId, properties != null ? properties : Collections.emptyList()));
        try {
            return new DynamicStateBlock(behaviourProperties);
        } finally {
            initContext.remove();
        }
    }

    private String blockId;

    public DynamicStateBlock(BlockBehaviour.Properties properties) {
        super(properties);
        InitContext ctx = initContext.get();
        if (ctx != null) {
            this.blockId = ctx.blockId;
            blockProperties.put(ctx.blockId, ctx.properties);
        }
    }

    public String getBlockId() {
        return blockId;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
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
}
