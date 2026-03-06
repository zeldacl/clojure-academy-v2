package my_mod.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import java.util.*;

/**
 * Universal Dynamic Block with configurable BlockState properties.
 * 
 * IMPORTANT: This is now a generic implementation. Properties are injected
 * by Clojure during initialization, not hardcoded here.
 * 
 * Design:
 * - Properties are defined in Clojure: my-mod.block.wireless-node/block-state-properties
 * - Property objects are created by: my-mod.block.blockstate-properties module
 * - This class applies them via setProperties() before Minecraft initializes
 * 
 * Adding new block properties requires ONLY Clojure changes:
 * 1. Add definition in block DSL (:block-state-properties)
 * 2. blockstate-properties module auto-generates Property objects
 * 3. Platform passes them to setProperties()
 * 4. No Java code changes needed
 */
public class NodeDynamicBlock extends Block {
    private static final Map<String, List<Property<?>>> blockProperties = new HashMap<>();
    private String blockId;

    public NodeDynamicBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    /**
     * Set the block ID and inject properties.
     * Called during registration by platform initialization code.
     */
    public void setBlockIdAndProperties(String blockId, List<Property<?>> properties) {
        this.blockId = blockId;
        blockProperties.put(blockId, properties);
        // Trigger state initialization after properties are set
        this.registerDefaultState(this.stateDefinition.any());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        // Get injected properties for this block
        List<Property<?>> properties = blockProperties.getOrDefault(this.blockId, Collections.emptyList());
        if (!properties.isEmpty()) {
            builder.add(properties.toArray(new Property<?>[0]));
        }
    }
}
