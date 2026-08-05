package cn.li.mc262.bridge;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * 26.2-safe accessors for APIs that drifted from 1.21.1 reflection shapes.
 */
public final class McAccess {
    private McAccess() {
    }

    public static Identifier resourceKeyId(ResourceKey<?> key) {
        return key.identifier();
    }

    public static String resourceKeyString(ResourceKey<?> key) {
        return key.identifier().toString();
    }

    public static MinecraftServer serverOf(Player player) {
        if (player == null) {
            return null;
        }
        Level level = player.level();
        return level != null ? level.getServer() : null;
    }

    public static MinecraftServer serverOf(ServerPlayer player) {
        return serverOf((Player) player);
    }

    public static long dayTime(Level level) {
        return level.getOverworldClockTime();
    }

    public static String dimensionId(Level level) {
        return resourceKeyString(level.dimension());
    }

    public static int serverTickCount(MinecraftServer server) {
        return server == null ? 0 : server.getTickCount();
    }

    /** 26.2: int op-level checks go through Permission.HasCommandLevel. */
    public static boolean hasCommandPermission(CommandSourceStack source, int level) {
        if (source == null) {
            return false;
        }
        PermissionLevel required = PermissionLevel.byId(level);
        return source.permissions().hasPermission(new Permission.HasCommandLevel(required));
    }
}
