package cn.li.fabric1201.block;

import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.Property;
import cn.li.mc1201.block.AbstractDynamicStateBlock;

import java.util.Collections;
import java.util.List;

/**
 * Fabric 1.20.1 Block with dynamic BlockState properties, no BlockEntity.
 * Extends shared AbstractDynamicStateBlock for property registration logic.
 */
public class NodeDynamicBlock extends AbstractDynamicStateBlock {

    public static NodeDynamicBlock create(String blockId,
                                          List<Property<?>> properties,
                                          BlockBehaviour.Properties behaviourProperties) {
        INIT_CONTEXT.set(new InitContext(blockId, properties));
        try {
            return new NodeDynamicBlock(behaviourProperties);
        } finally {
            INIT_CONTEXT.remove();
        }
    }

    public NodeDynamicBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    public void setBlockIdAndProperties(String blockId, List<Property<?>> properties) {
        this.blockId = blockId;
        BLOCK_PROPERTIES.put(blockId, properties);
        this.registerDefaultState(this.stateDefinition.any());
    }
}
