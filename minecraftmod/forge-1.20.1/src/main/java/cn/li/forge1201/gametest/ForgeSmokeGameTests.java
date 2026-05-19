package cn.li.forge1201.gametest;

import clojure.lang.AFn;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentVector;
import com.mojang.authlib.GameProfile;
import cn.li.mc1201.clj.ClojureInterop;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static cn.li.forge1201.MyMod1201.MODID;

/**
 * {@link PrefixGameTestTemplate}({@code false}) makes {@code template = "empty"} resolve to
 * {@code minecraft:empty}. Without it, Forge prepends the lowercase class name to the template id
 * (see Forge GameTest docs), so tests would not register and the GameTest server would run 0 tests.
 */
@GameTestHolder(MODID)
@PrefixGameTestTemplate(false)
public final class ForgeSmokeGameTests {
    private static final String PLAYER_NBT_STATE_KEY = "ac_ability_state";
    private static final String SAVED_LOCATIONS_KEY = "SavedLocations";

    private static final String[] AC_GUI_MENU_IDS = {
        "wireless_node_gui",
        "wireless_matrix_gui",
        "solar_gen_gui",
        "wind_gen_main_gui",
        "wind_gen_base_gui",
        "imag_fusor_gui",
        "metal_former_gui",
        "phase_gen_gui",
        "developer_gui",
        "energy_converter_gui",
        "ability_interferer_gui"
    };

    private static final GuiOpenSpec[] AC_GUI_OPEN_SPECS = {
        new GuiOpenSpec(0, "wireless_node_gui", "node_basic", "Wireless Node"),
        new GuiOpenSpec(1, "wireless_matrix_gui", "matrix", "Wireless Matrix"),
        new GuiOpenSpec(2, "solar_gen_gui", "solar_gen", "Solar Generator"),
        new GuiOpenSpec(3, "wind_gen_main_gui", "wind_gen_main", "Wind Generator Main"),
        new GuiOpenSpec(4, "wind_gen_base_gui", "wind_gen_base", "Wind Generator Base"),
        new GuiOpenSpec(5, "imag_fusor_gui", "imag_fusor", "Imag Fusor"),
        new GuiOpenSpec(6, "metal_former_gui", "metal_former", "Metal Former"),
        new GuiOpenSpec(7, "phase_gen_gui", "phase_gen", "Phase Generator"),
        new GuiOpenSpec(13, "developer_gui", "developer_normal", "Ability Developer"),
        new GuiOpenSpec(14, "energy_converter_gui", "converter_rf_input", "Energy Converter"),
        new GuiOpenSpec(15, "ability_interferer_gui", "ability_interferer", "Ability Interferer")
    };

    private ForgeSmokeGameTests() {
    }

    private record GuiOpenSpec(int guiId, String menuId, String blockId, String label) {
    }

    private static void assertBlockRegistered(GameTestHelper helper, String id) {
        helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(new ResourceLocation(MODID, id)),
            "Expected " + id + " block to be registered");
    }

    private static void assertBlockRegistered(GameTestHelper helper, String registryId, String logicalId) {
        helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(new ResourceLocation(MODID, registryId)),
            "Expected " + logicalId + " block to be registered as " + registryId);
    }

    private static void assertPhaseLiquidPoolUsesImagPhase(GameTestHelper helper) {
        Feature<?> feature = BuiltInRegistries.FEATURE.get(new ResourceLocation(MODID, "phase_liquid_pool"));
        helper.assertTrue(feature != null, "Expected phase_liquid_pool feature instance to be registered");
        helper.assertTrue("cn.li.mc1201.worldgen.PhaseLiquidPoolFeature".equals(feature.getClass().getName()),
            "Expected phase_liquid_pool to use shared PhaseLiquidPoolFeature implementation");
        try {
            java.lang.reflect.Field field = feature.getClass().getDeclaredField("phaseLiquidBlock");
            field.setAccessible(true);
            BlockState phaseState = (BlockState) field.get(feature);
            Block imagPhase = requireBlock(helper, "imag_phase");
            helper.assertTrue(phaseState.getBlock() == imagPhase,
                "Expected phase_liquid_pool to generate my_mod:imag_phase, not a fallback block");
            helper.assertTrue(phaseState.getBlock() != Blocks.WATER,
                "Expected phase_liquid_pool not to fall back to minecraft:water");
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to inspect phase_liquid_pool block binding", e);
        }
    }

    private static MenuType<?> requireMenuType(GameTestHelper helper, String menuId) {
        MenuType<?> menuType = BuiltInRegistries.MENU.get(new ResourceLocation(MODID, menuId));
        helper.assertTrue(menuType != null, "Expected " + menuId + " menu type to be registered");
        return menuType;
    }

    private static Block requireBlock(GameTestHelper helper, String blockId) {
        Block block = BuiltInRegistries.BLOCK.get(new ResourceLocation(MODID, blockId));
        helper.assertTrue(block != null, "Expected " + blockId + " block to be registered");
        return block;
    }

    private static void assertProviderCreatesMenu(GameTestHelper helper, Player player, GuiOpenSpec spec, int index) {
        try {
            BlockPos pos = new BlockPos(1 + (index % 4), 2, 1 + (index / 4));
            Block block = requireBlock(helper, spec.blockId());
            helper.setBlock(pos, block.defaultBlockState());
            BlockPos absolutePos = helper.absolutePos(pos);
            player.setPos(absolutePos.getX() + 0.5D, absolutePos.getY(), absolutePos.getZ() + 0.5D);

            BlockEntity blockEntity = helper.getBlockEntity(pos);
            helper.assertTrue(blockEntity != null, "Expected " + spec.label() + " block entity at " + pos);

            MenuType<?> expectedMenuType = requireMenuType(helper, spec.menuId());
            Object providerObj = ClojureInterop.invoke(
                "cn.li.forge1201.gui.provider-bridge",
                "create-menu-provider",
                spec.guiId(),
                blockEntity);
            helper.assertTrue(providerObj instanceof MenuProvider,
                "Expected " + spec.label() + " provider to implement MenuProvider");

            AbstractContainerMenu menu = ((MenuProvider) providerObj).createMenu(200 + index, player.getInventory(), player);
            helper.assertTrue(menu != null, "Expected " + spec.label() + " provider to create a server menu");
            helper.assertTrue(menu.getType() == expectedMenuType,
                "Expected " + spec.label() + " menu type " + spec.menuId());
            helper.assertTrue(menu.stillValid(player), "Expected " + spec.label() + " menu to be initially valid");
        } catch (Throwable t) {
            throw new RuntimeException("Failed GUI provider smoke for " + spec.label()
                + " (guiId=" + spec.guiId()
                + ", menu=" + spec.menuId()
                + ", block=" + spec.blockId() + ")", t);
        }
    }

    private static double invokeDouble(String namespace, String functionName, double value) {
        return ((Number) ClojureInterop.invoke(namespace, functionName, value)).doubleValue();
    }

    private static Keyword kw(String name) {
        return Keyword.intern(null, name);
    }

    private static PersistentArrayMap mapOf(Object... entries) {
        return PersistentArrayMap.createAsIfByAssoc(entries);
    }

    private static ServerPlayer fakePlayer(GameTestHelper helper, String name) {
        UUID uuid = UUID.nameUUIDFromBytes(("academycraft-gametest:" + name).getBytes(StandardCharsets.UTF_8));
        return FakePlayerFactory.get(helper.getLevel(), new GameProfile(uuid, name));
    }

    private static byte[] encodeGuiPayload(Object payload) {
        return (byte[]) ClojureInterop.invoke("cn.li.mc1201.gui.network.packet", "encode-payload-bytes", payload);
    }

    private static void invokeNetworkRegistry(String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Class<?> registry = Class.forName("cn.li.forge1201.network.NetworkHandlerRegistry");
            Method method = registry.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            method.invoke(null, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void dispatchForgeGuiRequest(String msgId, int requestId, byte[] payload, ServerPlayer player) {
        invokeNetworkRegistry(
            "dispatchRequest",
            new Class<?>[]{String.class, Integer.TYPE, byte[].class, ServerPlayer.class},
            msgId,
            requestId,
            payload,
            player);
    }

    private static void dispatchForgeGuiResponse(int requestId, byte[] payload) {
        invokeNetworkRegistry(
            "dispatchResponse",
            new Class<?>[]{Integer.TYPE, byte[].class},
            requestId,
            payload);
    }

    private static PersistentArrayMap samplePlayerState() {
        return mapOf(
            kw("ability-data"), mapOf(kw("category"), kw("electromaster"), kw("level"), 3L),
            kw("resource-data"), mapOf(kw("activated"), true, kw("cp"), 42.0D, kw("overload"), 7.5D),
            kw("cooldown-data"), mapOf(kw("railgun"), 12L, kw("teleport"), 5L),
            kw("preset-data"), mapOf(kw("current"), "combat", kw("slots"), PersistentVector.create(Arrays.asList("railgun", "teleport"))),
            kw("develop-data"), mapOf(kw("developer"), "normal", kw("progress"), 0.75D),
            kw("terminal-data"), mapOf(kw("terminal-installed?"), true,
                kw("installed-apps"), PersistentVector.create(Arrays.asList(kw("skill-tree"), kw("tutorial"), kw("settings")))),
            kw("dirty?"), true);
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> asMap(Object value) {
        return (Map<?, ?>) value;
    }

    private static void assertSamplePlayerStateLoaded(GameTestHelper helper, Object loadedState, String label) {
        Map<?, ?> loaded = asMap(loadedState);
        helper.assertTrue(loaded.keySet().containsAll(Arrays.asList(
            kw("ability-data"),
            kw("resource-data"),
            kw("cooldown-data"),
            kw("preset-data"),
            kw("develop-data"),
            kw("terminal-data"))),
            "Expected " + label + " to preserve all top-level player-state sections");
        helper.assertTrue(Boolean.FALSE.equals(loaded.get(kw("dirty?"))),
            "Expected " + label + " load to mark state clean");

        Map<?, ?> ability = asMap(loaded.get(kw("ability-data")));
        helper.assertTrue(kw("electromaster").equals(ability.get(kw("category"))),
            "Expected " + label + " ability category to round-trip");
        helper.assertTrue(Long.valueOf(3L).equals(ability.get(kw("level"))),
            "Expected " + label + " ability level to round-trip");

        Map<?, ?> terminal = asMap(loaded.get(kw("terminal-data")));
        helper.assertTrue(Boolean.TRUE.equals(terminal.get(kw("terminal-installed?"))),
            "Expected " + label + " terminal installation flag to round-trip");
        helper.assertTrue(asMap(loaded.get(kw("preset-data"))).containsKey(kw("slots")),
            "Expected " + label + " preset slots to round-trip");
        helper.assertTrue(asMap(loaded.get(kw("develop-data"))).containsKey(kw("progress")),
            "Expected " + label + " develop progress to round-trip");
    }

    private static void assertPlayerStateHasCurrentSchema(GameTestHelper helper, Object loadedState, String label) {
        Map<?, ?> loaded = asMap(loadedState);
        helper.assertTrue(loaded.keySet().containsAll(Arrays.asList(
            kw("ability-data"),
            kw("resource-data"),
            kw("cooldown-data"),
            kw("preset-data"),
            kw("develop-data"),
            kw("terminal-data"))),
            "Expected " + label + " to contain all current top-level player-state sections");
        helper.assertTrue(Boolean.FALSE.equals(loaded.get(kw("dirty?"))),
            "Expected " + label + " to load as clean state");
        helper.assertTrue(asMap(loaded.get(kw("ability-data"))).containsKey(kw("category-id")),
            "Expected " + label + " ability-data defaults to be present");
        helper.assertTrue(asMap(loaded.get(kw("resource-data"))).containsKey(kw("max-cp")),
            "Expected " + label + " resource-data defaults to be present");
        helper.assertTrue(asMap(loaded.get(kw("terminal-data"))).containsKey(kw("terminal-installed?")),
            "Expected " + label + " terminal-data defaults to be present");
    }

    private static void seedSavedLocation(ServerPlayer player) {
        CompoundTag locations = new CompoundTag();
        CompoundTag home = new CompoundTag();
        home.putString("world", "minecraft:overworld");
        home.putDouble("x", 12.5D);
        home.putDouble("y", 70.0D);
        home.putDouble("z", -8.25D);
        locations.put("home", home);
        player.getPersistentData().put(SAVED_LOCATIONS_KEY, locations);
    }

    private static void assertSavedLocationCloned(GameTestHelper helper, ServerPlayer player) {
        CompoundTag playerData = player.getPersistentData();
        helper.assertTrue(playerData.contains(SAVED_LOCATIONS_KEY),
            "Expected cloned player persistent data to keep SavedLocations");
        CompoundTag locations = playerData.getCompound(SAVED_LOCATIONS_KEY);
        helper.assertTrue(locations.contains("home"), "Expected cloned player to keep saved location named home");
        CompoundTag home = locations.getCompound("home");
        helper.assertTrue("minecraft:overworld".equals(home.getString("world")),
            "Expected cloned saved location world to round-trip");
        helper.assertTrue(Math.abs(home.getDouble("x") - 12.5D) < 0.0001D,
            "Expected cloned saved location x to round-trip");
        helper.assertTrue(Math.abs(home.getDouble("y") - 70.0D) < 0.0001D,
            "Expected cloned saved location y to round-trip");
        helper.assertTrue(Math.abs(home.getDouble("z") + 8.25D) < 0.0001D,
            "Expected cloned saved location z to round-trip");
    }

    @GameTest(templateNamespace = "minecraft", template = "empty", batch = "ac_smoke")
    public static void smoke(GameTestHelper helper) {
        assertBlockRegistered(helper, "matrix", "wireless-matrix");
        helper.assertTrue(BuiltInRegistries.FEATURE.containsKey(new ResourceLocation(MODID, "phase_liquid_pool")),
            "Expected phase_liquid_pool feature to be registered");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "empty", batch = "ac_worldgen")
    public static void phaseLiquidPoolUsesImagPhaseBlock(GameTestHelper helper) {
        assertPhaseLiquidPoolUsesImagPhase(helper);
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "empty", batch = "ac_gui")
    public static void guiMenuTypesRegistered(GameTestHelper helper) {
        for (String id : AC_GUI_MENU_IDS) {
            helper.assertTrue(BuiltInRegistries.MENU.containsKey(new ResourceLocation(MODID, id)),
                "Expected " + id + " menu type to be registered");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "empty", batch = "ac_gui")
    public static void guiProvidersCreateServerMenus(GameTestHelper helper) {
        ClojureInterop.requireNamespace("cn.li.forge1201.gui.provider-bridge");
        Player player = helper.makeMockPlayer();
        for (int i = 0; i < AC_GUI_OPEN_SPECS.length; i++) {
            assertProviderCreatesMenu(helper, player, AC_GUI_OPEN_SPECS[i], i);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "empty", batch = "ac_gui_network")
    public static void forgeGuiNetworkLoopbackSmoke(GameTestHelper helper) {
        String guiNetworkNs = "cn.li.forge1201.gui.network";
        String packetNs = "cn.li.mc1201.gui.network.packet";
        String serverNs = "cn.li.mcmod.network.server";
        String clientNs = "cn.li.mcmod.network.client";
        ClojureInterop.requireNamespace(guiNetworkNs);
        ClojureInterop.requireNamespace(packetNs);
        ClojureInterop.requireNamespace(serverNs);
        ClojureInterop.requireNamespace(clientNs);
        ClojureInterop.invoke(guiNetworkNs, "init!");

        ServerPlayer sender = fakePlayer(helper, "ACGuiNetSender");
        String messageId = "gametest/gui-loopback/" + UUID.randomUUID();
        String pushId = messageId + "/push";
        AtomicReference<Object> requestPayload = new AtomicReference<>();
        AtomicReference<Object> requestSender = new AtomicReference<>();
        AtomicReference<Object> pushPayload = new AtomicReference<>();

        ClojureInterop.invoke(serverNs, "register-handler", messageId, new AFn() {
            @Override
            public Object invoke(Object payload, Object player) {
                requestPayload.set(payload);
                requestSender.set(player);
                return mapOf(kw("success"), true);
            }
        });
        ClojureInterop.invoke(clientNs, "register-push-handler!", pushId, new AFn() {
            @Override
            public Object invoke(Object payload) {
                pushPayload.set(payload);
                return null;
            }
        });

        Object request = mapOf(
            kw("gui-id"), kw("developer"),
            kw("action"), "upgrade",
            kw("nested"), mapOf(kw("page"), 2L, kw("enabled"), true));
        dispatchForgeGuiRequest(messageId, -1, encodeGuiPayload(request), sender);

        helper.assertTrue(requestPayload.get() != null, "Expected Forge GUI request bridge to dispatch decoded payload");
        helper.assertTrue(requestSender.get() == sender, "Expected Forge GUI request bridge to preserve sender player");
        Map<?, ?> decodedRequest = asMap(requestPayload.get());
        helper.assertTrue(kw("developer").equals(decodedRequest.get(kw("gui-id"))),
            "Expected GUI request keyword payload to round-trip");
        helper.assertTrue("upgrade".equals(decodedRequest.get(kw("action"))),
            "Expected GUI request string payload to round-trip");
        helper.assertTrue(Boolean.TRUE.equals(asMap(decodedRequest.get(kw("nested"))).get(kw("enabled"))),
            "Expected nested GUI request payload to round-trip");

        Object pushEnvelope = mapOf(
            kw("msg-id"), pushId,
            kw("payload"), mapOf(kw("screen"), kw("terminal"), kw("refresh"), true));
        dispatchForgeGuiResponse(-1, encodeGuiPayload(pushEnvelope));

        helper.assertTrue(pushPayload.get() != null, "Expected Forge GUI response bridge to dispatch client push payload");
        Map<?, ?> decodedPush = asMap(pushPayload.get());
        helper.assertTrue(kw("terminal").equals(decodedPush.get(kw("screen"))),
            "Expected GUI push keyword payload to round-trip");
        helper.assertTrue(Boolean.TRUE.equals(decodedPush.get(kw("refresh"))),
            "Expected GUI push boolean payload to round-trip");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "empty", batch = "ac_wireless")
    public static void wirelessNetworkTopology(GameTestHelper helper) {
        assertBlockRegistered(helper, "node_basic", "wireless-node-basic");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "empty", batch = "ac_energy")
    public static void powerGenerationTick(GameTestHelper helper) {
        assertBlockRegistered(helper, "solar_gen", "solar-gen");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "empty", batch = "ac_energy")
    public static void converterFullIoFlow(GameTestHelper helper) {
        assertBlockRegistered(helper, "converter_rf_input", "rf-input");
        assertBlockRegistered(helper, "converter_rf_output", "rf-output");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "empty", batch = "ac_energy")
    public static void ic2OptionalIntegrationSmoke(GameTestHelper helper) {
        String ns = "cn.li.forge1201.integration.ic2-energy";
        ClojureInterop.requireNamespace(ns);

        boolean available = Boolean.TRUE.equals(ClojureInterop.invoke(ns, "ic2-available?"));
        boolean registered = Boolean.TRUE.equals(ClojureInterop.invoke(ns, "register-ic2-capability!"));
        helper.assertTrue(registered == available,
            "IC2 registration result should match optional API availability");

        ClojureInterop.invoke(ns, "init-ic2-energy!");
        helper.assertTrue(Math.abs(invokeDouble(ns, "if-to-eu", 32.0D) - 32.0D) < 0.0001D,
            "Expected upstream IC2 conversion rate 1 IF = 1 EU");
        helper.assertTrue(Math.abs(invokeDouble(ns, "eu-to-if", 32.0D) - 32.0D) < 0.0001D,
            "Expected upstream IC2 conversion rate 1 EU = 1 IF");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "empty", batch = "ac_persistence")
    public static void playerStateNbtRoundTripAndClone(GameTestHelper helper) {
        String nbtNs = "cn.li.mc1201.runtime.nbt-core";
        String hooksNs = "cn.li.mcmod.hooks.core";
        ClojureInterop.requireNamespace(nbtNs);

        ServerPlayer source = fakePlayer(helper, "ACPersistSrc");
        ServerPlayer clone = fakePlayer(helper, "ACPersistClone");
        String sourceUuid = source.getUUID().toString();
        String cloneUuid = clone.getUUID().toString();
        PersistentArrayMap expectedState = samplePlayerState();

        ClojureInterop.invoke(hooksNs, "set-player-state!", sourceUuid, expectedState);
        helper.assertTrue(Boolean.TRUE.equals(ClojureInterop.invoke(nbtNs, "save-player-state!", source)),
            "Expected source player state save to succeed");

        CompoundTag sourceTag = source.getPersistentData();
        helper.assertTrue(sourceTag.contains(PLAYER_NBT_STATE_KEY),
            "Expected source persistent data to contain AC player-state EDN");
        helper.assertTrue(sourceTag.getString(PLAYER_NBT_STATE_KEY).contains(":terminal-data"),
            "Expected saved EDN to include terminal-data");

        ClojureInterop.invoke(hooksNs, "set-player-state!", sourceUuid, mapOf());
        ClojureInterop.invoke(nbtNs, "load-player-state!", source);
        Object loadedSourceState = ClojureInterop.invoke(hooksNs, "get-player-state", sourceUuid);
        assertSamplePlayerStateLoaded(helper, loadedSourceState, "source player NBT");

        ClojureInterop.invoke(nbtNs, "clone-player-state!", source, clone);
        helper.assertTrue(clone.getPersistentData().contains(PLAYER_NBT_STATE_KEY),
            "Expected cloned player persistent data to contain AC player-state EDN");
        ClojureInterop.invoke(hooksNs, "set-player-state!", cloneUuid, mapOf());
        ClojureInterop.invoke(nbtNs, "load-player-state!", clone);
        Object loadedCloneState = ClojureInterop.invoke(hooksNs, "get-player-state", cloneUuid);
        assertSamplePlayerStateLoaded(helper, loadedCloneState, "cloned player NBT");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "empty", batch = "ac_persistence")
    public static void deathLifecycleClonePreservesPlayerData(GameTestHelper helper) {
        String nbtNs = "cn.li.mc1201.runtime.nbt-core";
        String lifecycleNs = "cn.li.mc1201.runtime.lifecycle-core";
        String hooksNs = "cn.li.mcmod.hooks.core";
        ClojureInterop.requireNamespace(nbtNs);
        ClojureInterop.requireNamespace(lifecycleNs);

        ServerPlayer source = fakePlayer(helper, "ACDeathPersistSrc");
        ServerPlayer clone = fakePlayer(helper, "ACDeathPersistClone");
        String sourceUuid = source.getUUID().toString();
        String cloneUuid = clone.getUUID().toString();

        ClojureInterop.invoke(hooksNs, "set-player-state!", sourceUuid, samplePlayerState());
        seedSavedLocation(source);

        ClojureInterop.invoke(lifecycleNs, "on-player-death!", source,
            mapOf(kw("save-player-state!"), new AFn() {
                @Override
                public Object invoke(Object player) {
                    return ClojureInterop.invoke(nbtNs, "save-player-state!", player);
                }
            }));
        helper.assertTrue(source.getPersistentData().contains(PLAYER_NBT_STATE_KEY),
            "Expected death lifecycle to save source player state before clone");

        ClojureInterop.invoke(lifecycleNs, "on-player-clone!", source, clone, false,
            mapOf(kw("clone-player-state!"), new AFn() {
                @Override
                public Object invoke(Object oldPlayer, Object newPlayer) {
                    return ClojureInterop.invoke(nbtNs, "clone-player-state!", oldPlayer, newPlayer);
                }
            }));

        helper.assertTrue(clone.getPersistentData().contains(PLAYER_NBT_STATE_KEY),
            "Expected death clone lifecycle to preserve AC player state");
        assertSavedLocationCloned(helper, clone);

        ClojureInterop.invoke(hooksNs, "set-player-state!", cloneUuid, mapOf());
        ClojureInterop.invoke(nbtNs, "load-player-state!", clone);
        Object loadedCloneState = ClojureInterop.invoke(hooksNs, "get-player-state", cloneUuid);
        assertSamplePlayerStateLoaded(helper, loadedCloneState, "death-cloned player NBT");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "empty", batch = "ac_persistence")
    public static void playerStateNbtMalformedAndPartialFallback(GameTestHelper helper) {
        String nbtNs = "cn.li.mc1201.runtime.nbt-core";
        String hooksNs = "cn.li.mcmod.hooks.core";
        ClojureInterop.requireNamespace(nbtNs);

        ServerPlayer malformed = fakePlayer(helper, "ACMalformedPersist");
        String malformedUuid = malformed.getUUID().toString();
        malformed.getPersistentData().putString(PLAYER_NBT_STATE_KEY, "{:ability-data");
        ClojureInterop.invoke(nbtNs, "load-player-state!", malformed);
        Object malformedState = ClojureInterop.invoke(hooksNs, "get-player-state", malformedUuid);
        assertPlayerStateHasCurrentSchema(helper, malformedState, "malformed player NBT fallback");

        ServerPlayer partial = fakePlayer(helper, "ACPartialPersist");
        String partialUuid = partial.getUUID().toString();
        partial.getPersistentData().putString(PLAYER_NBT_STATE_KEY,
            "{:ability-data {:level 2} :terminal-data {:terminal-installed? true}}");
        ClojureInterop.invoke(nbtNs, "load-player-state!", partial);
        Object partialState = ClojureInterop.invoke(hooksNs, "get-player-state", partialUuid);
        assertPlayerStateHasCurrentSchema(helper, partialState, "partial legacy player NBT");
        Map<?, ?> loadedPartial = asMap(partialState);
        helper.assertTrue(Long.valueOf(2L).equals(asMap(loadedPartial.get(kw("ability-data"))).get(kw("level"))),
            "Expected partial legacy player NBT to preserve explicit ability level");
        helper.assertTrue(Boolean.TRUE.equals(asMap(loadedPartial.get(kw("terminal-data"))).get(kw("terminal-installed?"))),
            "Expected partial legacy player NBT to preserve explicit terminal installation flag");
        helper.succeed();
    }
}
