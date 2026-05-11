package cn.li.forge1201.runtime;

import java.util.Map;
import java.util.UUID;

import cn.li.mc1201.runtime.RaycastShared;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.server.ServerLifecycleHooks;

public final class RaycastBridge {
    private RaycastBridge() {
    }

    public static MinecraftServer getServer() {
        return ServerLifecycleHooks.getCurrentServer();
    }

    public static ServerPlayer getPlayerByUuid(String uuidStr) {
        MinecraftServer server = getServer();

        if (server == null || uuidStr == null) {
            return null;
        }

        try {
            return server.getPlayerList().getPlayer(UUID.fromString(uuidStr));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static Map<String, Object> raycastBlocks(
            String worldId,
            double startX,
            double startY,
            double startZ,
            double dirX,
            double dirY,
            double dirZ,
            double maxDistance) {
        ServerLevel level = resolveLevel(worldId);
        return RaycastShared.raycastBlocks(level, startX, startY, startZ, dirX, dirY, dirZ, maxDistance);
    }

    public static Map<String, Object> raycastEntities(
            String worldId,
            double startX,
            double startY,
            double startZ,
            double dirX,
            double dirY,
            double dirZ,
            double maxDistance) {
        ServerLevel level = resolveLevel(worldId);
        return RaycastShared.raycastEntities(level, startX, startY, startZ, dirX, dirY, dirZ, maxDistance);
    }

    public static Map<String, Object> raycastCombined(
            String worldId,
            double startX,
            double startY,
            double startZ,
            double dirX,
            double dirY,
            double dirZ,
            double maxDistance) {
        ServerLevel level = resolveLevel(worldId);
        return RaycastShared.raycastCombined(level, startX, startY, startZ, dirX, dirY, dirZ, maxDistance);
    }

    public static Map<String, Object> getPlayerLookVector(String playerUuid) {
        ServerPlayer player = getPlayerByUuid(playerUuid);
        return RaycastShared.getPlayerLookVector(player);
    }

    public static Map<String, Object> raycastFromPlayer(String playerUuid, double maxDistance, boolean livingOnly) {
        ServerPlayer player = getPlayerByUuid(playerUuid);
        return RaycastShared.raycastFromPlayer(player, maxDistance, livingOnly);
    }

    private static ServerLevel resolveLevel(String worldId) {
        MinecraftServer server = getServer();

        if (server == null) {
            return null;
        }

        if (worldId == null || worldId.isEmpty()) {
            return server.overworld();
        }

        try {
            ResourceLocation id = new ResourceLocation(worldId);
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
            ServerLevel level = server.getLevel(key);
            return level != null ? level : server.overworld();
        } catch (IllegalArgumentException ignored) {
            return server.overworld();
        }
    }

}
