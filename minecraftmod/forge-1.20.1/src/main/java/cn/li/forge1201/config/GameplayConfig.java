package cn.li.forge1201.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

import java.util.Arrays;
import java.util.List;

/**
 * Gameplay configuration for AcademyCraft.
 * Manages ability parameters, CP/Overload data, and metal block lists.
 */
public class GameplayConfig {

    public static final ForgeConfigSpec SPEC;

    // Generic settings
    public static final ForgeConfigSpec.BooleanValue ANALYSIS_ENABLED;
    public static final ForgeConfigSpec.BooleanValue ATTACK_PLAYER;
    public static final ForgeConfigSpec.BooleanValue DESTROY_BLOCKS;
    public static final ForgeConfigSpec.BooleanValue GEN_ORES;
    public static final ForgeConfigSpec.BooleanValue GEN_PHASE_LIQUID;
    public static final ForgeConfigSpec.BooleanValue HEADS_OR_TAILS;

    // Ability settings
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> NORMAL_METAL_BLOCKS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> WEAK_METAL_BLOCKS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> METAL_ENTITIES;

    // CP/Overload data
    public static final ForgeConfigSpec.IntValue CP_RECOVER_COOLDOWN;
    public static final ForgeConfigSpec.DoubleValue CP_RECOVER_SPEED;
    public static final ForgeConfigSpec.IntValue OVERLOAD_RECOVER_COOLDOWN;
    public static final ForgeConfigSpec.DoubleValue OVERLOAD_RECOVER_SPEED;

    public static final ForgeConfigSpec.ConfigValue<List<? extends Integer>> INIT_CP;
    public static final ForgeConfigSpec.ConfigValue<List<? extends Integer>> ADD_CP;
    public static final ForgeConfigSpec.ConfigValue<List<? extends Integer>> INIT_OVERLOAD;
    public static final ForgeConfigSpec.ConfigValue<List<? extends Integer>> ADD_OVERLOAD;

    // Global calculation
    public static final ForgeConfigSpec.DoubleValue DAMAGE_SCALE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        // Generic settings
        builder.comment("Generic gameplay settings").push("generic");

        ANALYSIS_ENABLED = builder
                .comment("Enable analysis system (deprecated, kept for compatibility)")
                .define("analysis", false);

        ATTACK_PLAYER = builder
                .comment("Allow abilities to damage other players (PvP)")
                .define("attackPlayer", true);

        DESTROY_BLOCKS = builder
                .comment("Allow abilities to destroy blocks")
                .define("destroyBlocks", true);

        GEN_ORES = builder
                .comment("Generate AcademyCraft ores in world")
                .define("genOres", true);

        GEN_PHASE_LIQUID = builder
                .comment("Generate phase liquid pools in world")
                .define("genPhaseLiquid", true);

        HEADS_OR_TAILS = builder
                .comment("Show heads or tails display for coin flip")
                .define("headsOrTails", true);

        builder.pop();

        // Ability settings
        builder.comment("Ability-specific settings").push("ability");

        NORMAL_METAL_BLOCKS = builder
                .comment("List of normal metal blocks for Mag Manip")
                .defineList("normalMetalBlocks",
                        Arrays.asList(
                                "minecraft:rail", "minecraft:iron_bars", "minecraft:iron_block",
                                "minecraft:iron_door", "minecraft:iron_trapdoor", "minecraft:anvil",
                                "minecraft:chipped_anvil", "minecraft:damaged_anvil", "minecraft:cauldron",
                                "minecraft:chain", "minecraft:lantern", "minecraft:soul_lantern",
                                "minecraft:heavy_weighted_pressure_plate", "minecraft:light_weighted_pressure_plate"
                        ),
                        obj -> obj instanceof String);

        WEAK_METAL_BLOCKS = builder
                .comment("List of weak metal blocks for Mag Manip")
                .defineList("weakMetalBlocks",
                        Arrays.asList(
                                "minecraft:dispenser", "minecraft:hopper", "minecraft:iron_ore",
                                "minecraft:deepslate_iron_ore", "minecraft:raw_iron_block",
                                "minecraft:gold_ore", "minecraft:deepslate_gold_ore", "minecraft:raw_gold_block",
                                "minecraft:copper_ore", "minecraft:deepslate_copper_ore", "minecraft:raw_copper_block"
                        ),
                        obj -> obj instanceof String);

        METAL_ENTITIES = builder
                .comment("List of metal entities for Mag Manip")
                .defineList("metalEntities",
                        Arrays.asList(
                                "minecraft:minecart", "my_mod:entity_mag_hook",
                                "minecraft:chest_minecart", "minecraft:furnace_minecart",
                                "minecraft:hopper_minecart", "minecraft:tnt_minecart"
                        ),
                        obj -> obj instanceof String);

        builder.pop();

        // CP/Overload data
        builder.comment("CP and Overload recovery settings").push("cpOverload");

        CP_RECOVER_COOLDOWN = builder
                .comment("Cooldown ticks before CP starts recovering")
                .defineInRange("cpRecoverCooldown", 15, 0, 1000);

        CP_RECOVER_SPEED = builder
                .comment("CP recovery speed per tick")
                .defineInRange("cpRecoverSpeed", 1.0, 0.0, 100.0);

        OVERLOAD_RECOVER_COOLDOWN = builder
                .comment("Cooldown ticks before overload starts recovering")
                .defineInRange("overloadRecoverCooldown", 32, 0, 1000);

        OVERLOAD_RECOVER_SPEED = builder
                .comment("Overload recovery speed per tick")
                .defineInRange("overloadRecoverSpeed", 1.0, 0.0, 100.0);

        INIT_CP = builder
                .comment("Initial CP values for each level (0-5)")
                .defineList("initCp",
                        Arrays.asList(1800, 1800, 2800, 4000, 5800, 8000),
                        obj -> obj instanceof Integer);

        ADD_CP = builder
                .comment("Additional CP gained per level (0-5)")
                .defineList("addCp",
                        Arrays.asList(0, 900, 1000, 1500, 1700, 12000),
                        obj -> obj instanceof Integer);

        INIT_OVERLOAD = builder
                .comment("Initial overload values for each level (0-5)")
                .defineList("initOverload",
                        Arrays.asList(100, 100, 150, 240, 350, 500),
                        obj -> obj instanceof Integer);

        ADD_OVERLOAD = builder
                .comment("Additional overload gained per level (0-5)")
                .defineList("addOverload",
                        Arrays.asList(0, 40, 70, 80, 100, 500),
                        obj -> obj instanceof Integer);

        builder.pop();

        // Global calculation
        builder.comment("Global calculation settings").push("global");

        DAMAGE_SCALE = builder
                .comment("Global damage scale multiplier for all abilities")
                .defineInRange("damageScale", 1.0, 0.0, 10.0);

        builder.pop();

        SPEC = builder.build();
    }

    /**
     * Register the config with Forge.
     */
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC, "academycraft-gameplay.toml");
    }
}
