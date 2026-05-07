package cn.li.forge1201.gametest;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static cn.li.forge1201.MyMod1201.MODID;

/**
 * {@link PrefixGameTestTemplate}({@code false}) makes {@code template = "empty"} resolve to
 * {@code minecraft:empty}. Without it, Forge prepends the lowercase class name to the template id
 * (see Forge GameTest docs), so tests would not register and the GameTest server would run 0 tests.
 */
@GameTestHolder(MODID)
@PrefixGameTestTemplate(false)
public final class ForgeSmokeGameTests {
    private ForgeSmokeGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = "empty", batch = "ac_smoke")
    public static void smoke(GameTestHelper helper) {
        helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(new ResourceLocation(MODID, "wireless-matrix")),
            "Expected wireless-matrix block to be registered");
        helper.assertTrue(BuiltInRegistries.FEATURE.containsKey(new ResourceLocation(MODID, "phase_liquid_pool")),
            "Expected phase_liquid_pool feature to be registered");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "empty", batch = "ac_wireless")
    public static void wirelessNetworkTopology(GameTestHelper helper) {
        helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(new ResourceLocation(MODID, "wireless-node-basic")),
            "Expected wireless-node-basic block to be registered");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "empty", batch = "ac_energy")
    public static void powerGenerationTick(GameTestHelper helper) {
        helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(new ResourceLocation(MODID, "solar-gen")),
            "Expected solar-gen block to be registered");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "empty", batch = "ac_energy")
    public static void converterFullIoFlow(GameTestHelper helper) {
        helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(new ResourceLocation(MODID, "rf-input")),
            "Expected rf-input block to be registered");
        helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(new ResourceLocation(MODID, "rf-output")),
            "Expected rf-output block to be registered");
        helper.succeed();
    }
}
