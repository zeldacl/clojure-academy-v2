package cn.li.forge1201.gametest;

import clojure.lang.Keyword;
import cn.li.mc1201.clj.ClojureInterop;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Map;

import static cn.li.forge1201.MyMod1201.MODID;

/**
 * Neutral Forge smoke tests for platform bootstrap and descriptor registration.
 *
 * {@link PrefixGameTestTemplate}({@code false}) makes {@code template = "empty"}
 * resolve to {@code minecraft:empty}. Without it, Forge prepends the lowercase
 * class name to the template id.
 */
@GameTestHolder(MODID)
@PrefixGameTestTemplate(false)
public final class ForgeSmokeGameTests {
    private ForgeSmokeGameTests() {
    }

    private static Keyword kw(String name) {
        return Keyword.intern(null, name);
    }

    @GameTest(templateNamespace = "minecraft", template = "empty", batch = "platform_smoke")
    public static void neutralFeatureRegistered(GameTestHelper helper) {
        helper.assertTrue(BuiltInRegistries.FEATURE.containsKey(new ResourceLocation(MODID, "configurable_pool")),
            "Expected neutral configurable_pool feature to be registered");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "empty", batch = "content_smoke")
    public static void contentSmokeManifestsRegistered(GameTestHelper helper) {
        String registryNs = "cn.li.mcmod.content.registry";
        ClojureInterop.requireNamespace(registryNs);

        Object manifestsObj = ClojureInterop.invoke(registryNs, "list-smoke-manifests");
        helper.assertTrue(manifestsObj instanceof Iterable,
            "Expected content smoke manifest registry to be iterable");

        boolean foundManifestWithChecks = false;
        for (Object manifestObj : (Iterable<?>) manifestsObj) {
            if (manifestObj instanceof Map<?, ?> manifestMap && manifestMap.containsKey(kw("checks"))) {
                foundManifestWithChecks = true;
                break;
            }
        }

        helper.assertTrue(foundManifestWithChecks,
            "Expected at least one content-owned smoke manifest with checks");
        helper.succeed();
    }
}
