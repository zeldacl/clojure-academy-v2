package cn.li.mc1201.runtime;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;

/**
 * Loader-agnostic damage source resolution using direct typed MC API (Loom-remappable).
 */
public final class DamageSourceShared {
    private DamageSourceShared() {
    }

    public static DamageSource resolve(Level level, String kind) {
        if (level == null || kind == null) {
            return null;
        }
        return switch (kind) {
            case "magic" -> level.damageSources().magic();
            case "lightningBolt" -> level.damageSources().lightningBolt();
            case "explosion" -> level.damageSources().explosion(null, null);
            case "generic" -> level.damageSources().generic();
            default -> level.damageSources().generic();
        };
    }

    public static DamageSource resolveKeyword(Level level, Object sourceType) {
        String kind = switch (String.valueOf(sourceType)) {
            case ":magic" -> "magic";
            case ":lightning" -> "lightningBolt";
            case ":explosion" -> "explosion";
            case ":generic" -> "generic";
            default -> "generic";
        };
        return resolve(level, kind);
    }
}
