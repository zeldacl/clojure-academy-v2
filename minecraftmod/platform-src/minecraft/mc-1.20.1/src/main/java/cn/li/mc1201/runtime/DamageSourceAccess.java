package cn.li.mc1201.runtime;

/**
 * @deprecated Use {@link cn.li.mcbase.runtime.DamageSourceAccess}.
 */
@Deprecated
public final class DamageSourceAccess {
    private DamageSourceAccess() {
    }

    public static net.minecraft.world.damagesource.DamageSource resolve(
            net.minecraft.world.level.Level level, String kind) {
        return cn.li.mcbase.runtime.DamageSourceAccess.resolve(level, kind);
    }

    public static net.minecraft.world.damagesource.DamageSource resolveKeyword(
            net.minecraft.world.level.Level level, Object sourceType) {
        return cn.li.mcbase.runtime.DamageSourceAccess.resolveKeyword(level, sourceType);
    }

    public static net.minecraft.world.damagesource.DamageSource resolveKeyword(
            net.minecraft.world.level.Level level, Object sourceType, Object attacker) {
        return cn.li.mcbase.runtime.DamageSourceAccess.resolveKeyword(level, sourceType, attacker);
    }
}
