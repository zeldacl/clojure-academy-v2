package cn.li.mcver;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Cross-version accessors for player/level/server APIs that drift by mapping.
 * Contract matches the 26.2 surface; older versions implement via classic getters.
 */
public final class McAccess {
    private McAccess() {
    }

    public static ResourceLocation resourceKeyId(ResourceKey<?> key) {
        return key.location();
    }

    public static String resourceKeyString(ResourceKey<?> key) {
        return key.location().toString();
    }

    public static MinecraftServer serverOf(Player player) {
        return player == null ? null : player.getServer();
    }

    public static MinecraftServer serverOf(ServerPlayer player) {
        return serverOf((Player) player);
    }

    public static long dayTime(Level level) {
        return level.getDayTime();
    }

    /** In-game time ticks (pauses with the world). Prefer over wall-clock for logic. */
    public static long gameTime(Level level) {
        return level == null ? 0L : level.getGameTime();
    }

    public static String dimensionId(Level level) {
        return resourceKeyString(level.dimension());
    }

    public static int serverTickCount(MinecraftServer server) {
        return server == null ? 0 : server.getTickCount();
    }

    public static boolean isClientSide(Level level) {
        return level != null && level.isClientSide;
    }


    public static long windowHandle(Window window) {
        return window == null ? 0L : window.getWindow();
    }

    /** Client frame partial tick (Gui clock / render interpolation). */
    public static double clientPartialTick(Minecraft mc) {
        return mc == null ? 0.0d : mc.getFrameTime();
    }

    /** Close the current screen if any (Minecraft.screen vs Minecraft.gui.screen). */
    public static void closeScreen(Minecraft mc) {
        if (mc != null && mc.screen != null) {
            mc.setScreen(null);
        }
    }

    /** Open or replace the current screen. */
    public static void setScreen(Minecraft mc, net.minecraft.client.gui.screens.Screen screen) {
        if (mc != null) {
            mc.setScreen(screen);
        }
    }

    public static boolean hasCommandPermission(CommandSourceStack source, int level) {
        return source != null && source.hasPermission(level);
    }

    /**
     * Client-side live snapshot of a loaded entity (position + bounding box),
     * for skill aim markers that must follow a target entity every frame
     * (upstream EntityMarker.target follow). Returns null when the entity is
     * not loaded on this client.
     */
    public static java.util.Map<String, Object> clientEntitySnapshot(java.util.UUID uuid) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.multiplayer.ClientLevel level = mc.level;
        if (level == null || uuid == null) {
            return null;
        }
        // 1.20.1 has no Level.getEntities() entity lookup — iterate the
        // client entity store and match the UUID.
        net.minecraft.world.entity.Entity entity = null;
        for (net.minecraft.world.entity.Entity candidate : level.entitiesForRendering()) {
            if (uuid.equals(candidate.getUUID())) {
                entity = candidate;
                break;
            }
        }
        if (entity == null) {
            return null;
        }
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("x", entity.getX());
        result.put("y", entity.getY());
        result.put("z", entity.getZ());
        result.put("width", entity.getBbWidth());
        result.put("height", entity.getBbHeight());
        return result;
    }

    /**
     * Client-side motion override for a loaded entity. VecReflection's
     * c_reflectEntity re-runs the reflection on the client so a bounced arrow
     * turns the instant the message lands, instead of holding its old course
     * until the next velocity sync. The server sends the velocity it already
     * computed, so the two sides cannot disagree about the new direction.
     *
     * No-op when the entity is not loaded here.
     */
    public static boolean setClientEntityMotion(java.util.UUID uuid, double vx, double vy, double vz) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.multiplayer.ClientLevel level = mc.level;
        if (level == null || uuid == null) {
            return false;
        }
        for (net.minecraft.world.entity.Entity candidate : level.entitiesForRendering()) {
            if (uuid.equals(candidate.getUUID())) {
                candidate.setDeltaMovement(vx, vy, vz);
                candidate.hasImpulse = true;
                return true;
            }
        }
        return false;
    }
}
