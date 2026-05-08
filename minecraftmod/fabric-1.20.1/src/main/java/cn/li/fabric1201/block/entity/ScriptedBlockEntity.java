package cn.li.fabric1201.block.entity;

import cn.li.mc1201.block.entity.AbstractScriptedBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

public class ScriptedBlockEntity extends AbstractScriptedBlockEntity {

    private static final Map<String, BlockEntityType<ScriptedBlockEntity>> TYPES = new HashMap<>();

    public static void registerType(String tileId, BlockEntityType<ScriptedBlockEntity> type) {
        TYPES.put(tileId, type);
    }

    public static BlockEntityType<ScriptedBlockEntity> getType(String tileId) {
        return TYPES.get(tileId);
    }

    public ScriptedBlockEntity(BlockEntityType<ScriptedBlockEntity> type,
                               BlockPos pos,
                               BlockState state,
                               String tileId,
                               String blockId) {
        super(type, pos, state, tileId, blockId);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ScriptedBlockEntity blockEntity) {
        invokeServerTick(level, pos, state, blockEntity);
    }
}
