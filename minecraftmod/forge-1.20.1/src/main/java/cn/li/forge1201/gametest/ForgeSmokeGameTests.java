package cn.li.forge1201.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;

import static cn.li.forge1201.MyMod1201.MODID;

@GameTestHolder(MODID)
public final class ForgeSmokeGameTests {
    private ForgeSmokeGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = "empty", required = false, batch = "ac_smoke")
    public static void smoke(GameTestHelper helper) {
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "empty", required = false, batch = "ac_wireless")
    public static void wirelessNetworkTopology(GameTestHelper helper) {
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "empty", required = false, batch = "ac_energy")
    public static void powerGenerationTick(GameTestHelper helper) {
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "empty", required = false, batch = "ac_energy")
    public static void converterFullIoFlow(GameTestHelper helper) {
        helper.succeed();
    }
}
