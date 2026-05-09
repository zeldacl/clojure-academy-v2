package cn.li.forge1201.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.core.Direction;
import cn.li.mc1201.block.AbstractDynamicStateBlock;

import javax.annotation.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * Forge 1.20.1 Block with dynamic BlockState properties, no BlockEntity.
 * Extends shared AbstractDynamicStateBlock for property registration logic.
 */
public class DynamicStateBlock extends AbstractDynamicStateBlock {

    public static DynamicStateBlock create(String blockId,
                                          List<Property<?>> properties,
                                          BlockBehaviour.Properties behaviourProperties) {
        INIT_CONTEXT.set(new InitContext(blockId, properties != null ? properties : Collections.emptyList()));
        try {
            return new DynamicStateBlock(behaviourProperties);
        } finally {
            INIT_CONTEXT.remove();
        }
    }

    public DynamicStateBlock(BlockBehaviour.Properties properties) {
        super(properties);
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
