package cn.li.mc1201.block.entity;

import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared registry for scripted block entity types.
 *
 * <p>Forge and Fabric loaders register their platform-specific ScriptedBlockEntity
 * types via this shared utility to avoid duplication of registry logic.</p>
 */
public final class BlockEntityRegistry {

    private BlockEntityRegistry() {
        // Utility class; no instantiation
    }

    private static final Map<String, BlockEntityType<?>> TYPES = new HashMap<>();

    /**
     * Register a scripted block entity type by its ID.
     *
     * @param tileId the tile ID string (e.g., "core", "cable")
     * @param type   the BlockEntityType instance
     */
    public static void registerType(String tileId, BlockEntityType<?> type) {
        TYPES.put(tileId, type);
    }

    /**
     * Retrieve a previously registered scripted block entity type.
     *
     * @param tileId the tile ID string
     * @return the BlockEntityType, or null if not found
     */
    public static BlockEntityType<?> getType(String tileId) {
        return TYPES.get(tileId);
    }

    /**
     * Clear all registered types (primarily for testing).
     */
    public static void clear() {
        TYPES.clear();
    }
}
