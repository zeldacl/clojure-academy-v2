package cn.li.forge1201.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
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

        if (level == null) {
            return null;
        }

        Vec3 start = new Vec3(startX, startY, startZ);
        Vec3 end = new Vec3(startX + dirX * maxDistance, startY + dirY * maxDistance, startZ + dirZ * maxDistance);
        ClipContext clipContext = new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, null);
        BlockHitResult result = level.clip(clipContext);

        if (result == null || result.getType() != HitResult.Type.BLOCK) {
            return null;
        }

        BlockPos pos = result.getBlockPos();
        BlockState blockState = level.getBlockState(pos);
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("x", pos.getX());
        hit.put("y", pos.getY());
        hit.put("z", pos.getZ());
        hit.put("hit-x", result.getLocation().x);
        hit.put("hit-y", result.getLocation().y);
        hit.put("hit-z", result.getLocation().z);
        hit.put("block-id", blockState.getBlock().getDescriptionId());
        hit.put("face", result.getDirection().getSerializedName());
        hit.put("distance", start.distanceTo(result.getLocation()));
        return hit;
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

        if (level == null) {
            return null;
        }

        Vec3 start = new Vec3(startX, startY, startZ);
        Vec3 end = new Vec3(startX + dirX * maxDistance, startY + dirY * maxDistance, startZ + dirZ * maxDistance);
        AABB searchBox = createSearchBox(startX, startY, startZ, dirX, dirY, dirZ, maxDistance).inflate(2.0D);

        Map<String, Object> nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, searchBox);

        for (LivingEntity entity : entities) {
            Optional<Vec3> optionalHit = entity.getBoundingBox().clip(start, end);

            if (optionalHit.isEmpty()) {
                continue;
            }

            Vec3 hitVec = optionalHit.get();
            double distance = start.distanceTo(hitVec);

            if (distance >= nearestDistance) {
                continue;
            }

            nearestDistance = distance;
            nearest = new LinkedHashMap<>();
            nearest.put("uuid", entity.getUUID().toString());
            nearest.put("x", entity.position().x);
            nearest.put("y", entity.position().y);
            nearest.put("z", entity.position().z);
            nearest.put("hit-x", hitVec.x);
            nearest.put("hit-y", hitVec.y);
            nearest.put("hit-z", hitVec.z);
            nearest.put("eye-height", entity.getEyeHeight());
            nearest.put("type", entity.getType().getDescriptionId());
            nearest.put("distance", distance);
        }

        return nearest;
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
        Map<String, Object> blockHit = raycastBlocks(worldId, startX, startY, startZ, dirX, dirY, dirZ, maxDistance);
        Map<String, Object> entityHit = raycastEntities(worldId, startX, startY, startZ, dirX, dirY, dirZ, maxDistance);

        if (blockHit == null) {
            if (entityHit != null) {
                entityHit.put("hit-type", "entity");
            }

            return entityHit;
        }

        if (entityHit == null) {
            blockHit.put("hit-type", "block");
            return blockHit;
        }

        double blockDistance = ((Number) blockHit.get("distance")).doubleValue();
        double entityDistance = ((Number) entityHit.get("distance")).doubleValue();

        if (blockDistance <= entityDistance) {
            blockHit.put("hit-type", "block");
            return blockHit;
        }

        entityHit.put("hit-type", "entity");
        return entityHit;
    }

    public static Map<String, Object> getPlayerLookVector(String playerUuid) {
        ServerPlayer player = getPlayerByUuid(playerUuid);

        if (player == null) {
            return null;
        }

        Vec3 lookVec = player.getLookAngle();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("x", lookVec.x);
        result.put("y", lookVec.y);
        result.put("z", lookVec.z);
        return result;
    }

    public static Map<String, Object> raycastFromPlayer(String playerUuid, double maxDistance, boolean livingOnly) {
        ServerPlayer player = getPlayerByUuid(playerUuid);

        if (player == null) {
            return null;
        }

        Vec3 eyePos = player.getEyePosition();
        Vec3 lookVec = player.getLookAngle();
        Vec3 end = eyePos.add(lookVec.scale(maxDistance));
        AABB searchBox = createSearchBox(eyePos.x, eyePos.y, eyePos.z, lookVec.x, lookVec.y, lookVec.z, maxDistance).inflate(2.0D);

        Map<String, Object> nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        if (livingOnly) {
            for (LivingEntity entity : player.serverLevel().getEntitiesOfClass(LivingEntity.class, searchBox)) {
                nearestDistance = updateNearestPlayerHit(player, eyePos, end, entity, nearestDistance);
                if (nearestDistance != Double.MAX_VALUE) {
                    nearest = buildEntityHitMap(entity, eyePos, end, nearestDistance);
                }
            }
        } else {
            for (Entity entity : player.serverLevel().getEntitiesOfClass(Entity.class, searchBox)) {
                nearestDistance = updateNearestPlayerHit(player, eyePos, end, entity, nearestDistance);
                if (nearestDistance != Double.MAX_VALUE) {
                    nearest = buildEntityHitMap(entity, eyePos, end, nearestDistance);
                }
            }
        }

        return nearest;
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

    private static AABB createSearchBox(
            double startX,
            double startY,
            double startZ,
            double dirX,
            double dirY,
            double dirZ,
            double maxDistance) {
        double endX = startX + dirX * maxDistance;
        double endY = startY + dirY * maxDistance;
        double endZ = startZ + dirZ * maxDistance;
        return new AABB(
                Math.min(startX, endX),
                Math.min(startY, endY),
                Math.min(startZ, endZ),
                Math.max(startX, endX),
                Math.max(startY, endY),
                Math.max(startZ, endZ));
    }

    private static double updateNearestPlayerHit(ServerPlayer player, Vec3 start, Vec3 end, Entity entity, double currentNearest) {
        if (entity == player) {
            return currentNearest;
        }

        Optional<Vec3> optionalHit = entity.getBoundingBox().clip(start, end);

        if (optionalHit.isEmpty()) {
            return currentNearest;
        }

        double distance = start.distanceTo(optionalHit.get());
        return Math.min(currentNearest, distance);
    }

    private static Map<String, Object> buildEntityHitMap(Entity entity, Vec3 start, Vec3 end, double nearestDistance) {
        Optional<Vec3> optionalHit = entity.getBoundingBox().clip(start, end);

        if (optionalHit.isEmpty()) {
            return null;
        }

        double distance = start.distanceTo(optionalHit.get());

        if (distance != nearestDistance) {
            return null;
        }

        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("entity-id", entity.getUUID().toString());
        hit.put("x", entity.position().x);
        hit.put("y", entity.position().y);
        hit.put("z", entity.position().z);
        hit.put("distance", distance);
        return hit;
    }
}
