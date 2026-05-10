package cn.li.mc1201.block;

import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Shared placement helpers used by platform block adapters.
 */
public final class BlockPlacementHelper {

    private BlockPlacementHelper() {
    }

    public static BlockState withHorizontalFacing(Block block, BlockState state, BlockPlaceContext context) {
        if (context == null) {
            return state;
        }
        Property<?> prop = block.getStateDefinition().getProperty("facing");
        if (prop instanceof DirectionProperty directionProperty) {
            Direction placedFacing = context.getHorizontalDirection().getOpposite();
            if (directionProperty.getPossibleValues().contains(placedFacing)) {
                return state.setValue(directionProperty, placedFacing);
            }
        }
        return state;
    }
}
