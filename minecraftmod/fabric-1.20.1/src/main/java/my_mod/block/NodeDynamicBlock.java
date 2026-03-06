package my_mod.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Universal Dynamic Block with configurable BlockState properties.
 * 
 * IMPORTANT: This is now a generic implementation. Properties are injected
 * by Clojure during initialization, not hardcoded here.
 * 
 * Design:
 * - Properties are defined in Clojure: my-mod.block.wireless-node/block-state-properties
 * - Property objects are created by: my-mod.block.blockstate-properties module
 * - This class applies them before Minecraft initializes BlockState
 * 
 * Adding new block properties requires ONLY Clojure changes:
 * 1. Add definition in block DSL (:block-state-properties)
 * 2. blockstate-properties module auto-generates Property objects
 * 3. Platform passes them to setProperties()
 * 4. No Java code changes needed
 */
public class NodeDynamicBlock extends Block {
    private static final Map<String, List<Property<?>>> blockProperties = new HashMap<>();

    /**
     * Thread-local initialization context so that properties are available
     * when {@link #createBlockStateDefinition(StateDefinition.Builder)} is
     * first invoked from the {@link Block} constructor.
     */
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

    /**
     * Factory used by platform code to ensure that dynamic properties are
     * visible while the Block's state definition is being constructed.
     *
     * This MUST be used instead of calling the constructor directly when
     * creating blocks that have dynamic BlockState properties.
     */
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

        // Capture initialization context (for debugging / fallback access)
        InitContext ctx = initContext.get();
        if (ctx != null) {
            this.blockId = ctx.blockId;
            blockProperties.put(ctx.blockId, ctx.properties);
        }
    }

    /**
     * Set the block ID and inject properties.
     * Called during registration by platform initialization code.
     */
    public void setBlockIdAndProperties(String blockId, List<Property<?>> properties) {
        // Legacy API for older call sites.
        // We still record the mapping so that any code querying it later works,
        // but BlockState properties are now injected at construction time via
        // the create() factory and initContext thread-local.
        this.blockId = blockId;
        blockProperties.put(blockId, properties);
        this.registerDefaultState(this.stateDefinition.any());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        // During initial Block construction, use the thread-local context so
        // that properties are available before blockId is set.
        InitContext ctx = initContext.get();
        List<Property<?>> properties;

        if (ctx != null) {
            properties = ctx.properties;
            // Also cache for potential later lookups
            if (ctx.blockId != null) {
                this.blockId = ctx.blockId;
                blockProperties.put(ctx.blockId, ctx.properties);
            }
        } else {
            // Fallback path if create() was not used
            properties = blockProperties.getOrDefault(this.blockId, Collections.emptyList());
        }

        if (properties != null && !properties.isEmpty()) {
            builder.add(properties.toArray(new Property<?>[0]));
        }
    }
}
