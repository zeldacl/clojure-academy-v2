package cn.li.neoforge262.gametest;

import com.mojang.serialization.MapCodec;
import cn.li.mcver.ResourceLocations;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** Registers smoke tests through the data-driven 26.2 GameTest registry. */
public final class ForgeGameTestRegistration {
    private static final String MODID = "academy";

    private ForgeGameTestRegistration() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(ForgeGameTestRegistration::onRegisterGameTests);
    }

    private static void onRegisterGameTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment =
                event.registerEnvironment(ResourceLocations.of(MODID, "smoke"));

        register(event, environment, "neutral_feature_registered",
                ForgeSmokeGameTests::neutralFeatureRegistered);
        register(event, environment, "content_smoke_manifests_registered",
                ForgeSmokeGameTests::contentSmokeManifestsRegistered);
        register(event, environment, "electron_bomb_defaults_aligned",
                ForgeSmokeGameTests::electronBombDefaultsAligned);
        register(event, environment, "scatter_bomb_defaults_aligned",
                ForgeSmokeGameTests::scatterBombDefaultsAligned);
    }

    private static void register(RegisterGameTestsEvent event,
                                 Holder<TestEnvironmentDefinition<?>> environment,
                                 String name,
                                 Consumer<GameTestHelper> body) {
        Identifier id = ResourceLocations.of(MODID, name);
        TestData<Holder<TestEnvironmentDefinition<?>>> data =
                new TestData<>(environment, ResourceLocations.of("minecraft", "empty"), 100, 0, true);
        event.registerTest(id, new SmokeTestInstance(data, name, body));
    }

    private static final class SmokeTestInstance extends GameTestInstance {
        private final String name;
        private final Consumer<GameTestHelper> body;

        private SmokeTestInstance(TestData<Holder<TestEnvironmentDefinition<?>>> data,
                                  String name,
                                  Consumer<GameTestHelper> body) {
            super(data);
            this.name = name;
            this.body = body;
        }

        @Override
        public void run(GameTestHelper helper) {
            body.accept(helper);
        }

        @Override
        public MapCodec<? extends GameTestInstance> codec() {
            return FunctionGameTestInstance.CODEC;
        }

        @Override
        protected MutableComponent typeDescription() {
            return Component.literal("Academy smoke: " + name);
        }
    }
}
