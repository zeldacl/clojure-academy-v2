#!/usr/bin/env python3
"""Batch3: aggressive unlock + promote remaining identical clusters (no legacy compat)."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MC = [("mc-1.20.1", "mc1201"), ("mc-1.21.1", "mc1211"), ("mc-26.2", "mc262")]
NEO = [("neoforge-1.21.1", "neoforge1211"), ("neoforge-26.2", "neoforge262")]


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")
    print("write", path.relative_to(ROOT))


def delete_mc(rel: str) -> None:
    for folder, ns in MC:
        for kind in ("java", "clojure"):
            base = "java" if kind == "java" else "clojure"
            p = ROOT / f"platform-src/minecraft/{folder}/src/main/{base}/cn/li/{ns}/{rel}"
            if p.exists():
                p.unlink()
                print("delete", p.relative_to(ROOT))


def rewrite(replacements: list[tuple[str, str]], roots: list[str] | None = None) -> None:
    roots = roots or ["platform-src/minecraft", "platform-src/loader"]
    for root_name in roots:
        for path in (ROOT / root_name).rglob("*"):
            if path.suffix not in {".java", ".clj"}:
                continue
            posix = path.as_posix().replace("\\", "/")
            if "/minecraft/base/" in posix or "/neoforge-shared/" in posix:
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                text = path.read_text(encoding="utf-8", errors="surrogateescape")
            orig = text
            for a, b in replacements:
                text = text.replace(a, b)
            if text != orig:
                path.write_text(text, encoding="utf-8", errors="surrogateescape", newline="\n")
                print("rewrite", path.relative_to(ROOT))


def promote_hooks() -> None:
    write(
        ROOT / "platform-src/minecraft/base/src/main/java/cn/li/mcbase/entity/IScriptedOwnedEntity.java",
        """package cn.li.mcbase.entity;

import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;

/** Owned scripted entity surface (effect/marker) shared across MC versions. */
public interface IScriptedOwnedEntity {
    @Nullable
    Player getOwnerPlayer();
}
""",
    )

    # Marker/effect/ray hook interfaces + registries in mcbase (Entity-typed).
    write(
        ROOT / "platform-src/minecraft/base/src/main/java/cn/li/mcbase/entity/hook/effect/ScriptedEffectHook.java",
        """package cn.li.mcbase.entity.hook.effect;

import cn.li.mcbase.entity.hook.ClientEntityHook;
import net.minecraft.world.entity.Entity;

public interface ScriptedEffectHook extends ClientEntityHook<Entity> {
}
""",
    )
    write(
        ROOT / "platform-src/minecraft/base/src/main/java/cn/li/mcbase/entity/hook/effect/ScriptedEffectHooks.java",
        """package cn.li.mcbase.entity.hook.effect;

import cn.li.mcbase.entity.hook.AbstractHookRegistry;

public final class ScriptedEffectHooks {
    private static final Class<?> REGISTRY_CLASS = ScriptedEffectHooks.class;
    private static final Class<? extends ScriptedEffectHook> HOOK_INTERFACE = ScriptedEffectHook.class;

    public static void register(String hookId, ScriptedEffectHook hook) {
        AbstractHookRegistry.register(REGISTRY_CLASS, hookId, hook);
    }

    private static final ScriptedEffectHook NOOP = new ScriptedEffectHook() {
    };

    public static ScriptedEffectHook resolve(String hookId) {
        ScriptedEffectHook hook = AbstractHookRegistry.resolve(REGISTRY_CLASS, hookId);
        return hook != null ? hook : NOOP;
    }

    public static boolean registerByClassName(String hookId, String className) {
        return AbstractHookRegistry.registerByClassName(REGISTRY_CLASS, HOOK_INTERFACE, hookId, className);
    }
}
""",
    )
    write(
        ROOT / "platform-src/minecraft/base/src/main/java/cn/li/mcbase/entity/hook/marker/ScriptedMarkerHook.java",
        """package cn.li.mcbase.entity.hook.marker;

import cn.li.mcbase.entity.hook.ClientEntityHook;
import net.minecraft.world.entity.Entity;

public interface ScriptedMarkerHook extends ClientEntityHook<Entity> {
}
""",
    )
    write(
        ROOT / "platform-src/minecraft/base/src/main/java/cn/li/mcbase/entity/hook/marker/ScriptedMarkerHooks.java",
        """package cn.li.mcbase.entity.hook.marker;

import cn.li.mcbase.entity.hook.AbstractHookRegistry;

public final class ScriptedMarkerHooks {
    private static final Class<?> REGISTRY_CLASS = ScriptedMarkerHooks.class;
    private static final Class<? extends ScriptedMarkerHook> HOOK_INTERFACE = ScriptedMarkerHook.class;

    public static void register(String hookId, ScriptedMarkerHook hook) {
        AbstractHookRegistry.register(REGISTRY_CLASS, hookId, hook);
    }

    private static final ScriptedMarkerHook NOOP = new ScriptedMarkerHook() {
    };

    public static ScriptedMarkerHook resolve(String hookId) {
        ScriptedMarkerHook hook = AbstractHookRegistry.resolve(REGISTRY_CLASS, hookId);
        return hook != null ? hook : NOOP;
    }

    public static boolean registerByClassName(String hookId, String className) {
        return AbstractHookRegistry.registerByClassName(REGISTRY_CLASS, HOOK_INTERFACE, hookId, className);
    }
}
""",
    )
    write(
        ROOT / "platform-src/minecraft/base/src/main/java/cn/li/mcbase/entity/hook/ray/ScriptedRayHook.java",
        """package cn.li.mcbase.entity.hook.ray;

import cn.li.mcbase.entity.hook.ClientEntityHook;
import net.minecraft.world.entity.Entity;

public interface ScriptedRayHook extends ClientEntityHook<Entity> {
}
""",
    )
    write(
        ROOT / "platform-src/minecraft/base/src/main/java/cn/li/mcbase/entity/hook/ray/ScriptedRayHooks.java",
        """package cn.li.mcbase.entity.hook.ray;

import cn.li.mcbase.entity.hook.AbstractHookRegistry;

public final class ScriptedRayHooks {
    private static final Class<?> REGISTRY_CLASS = ScriptedRayHooks.class;
    private static final Class<? extends ScriptedRayHook> HOOK_INTERFACE = ScriptedRayHook.class;

    public static void register(String hookId, ScriptedRayHook hook) {
        AbstractHookRegistry.register(REGISTRY_CLASS, hookId, hook);
    }

    private static final ScriptedRayHook NOOP = new ScriptedRayHook() {
    };

    public static ScriptedRayHook resolve(String hookId) {
        ScriptedRayHook hook = AbstractHookRegistry.resolve(REGISTRY_CLASS, hookId);
        return hook != null ? hook : NOOP;
    }

    public static boolean registerByClassName(String hookId, String className) {
        return AbstractHookRegistry.registerByClassName(REGISTRY_CLASS, HOOK_INTERFACE, hookId, className);
    }
}
""",
    )
    write(
        ROOT / "platform-src/minecraft/base/src/main/java/cn/li/mcbase/entity/hook/marker/OwnerFollowMarkerHook.java",
        """package cn.li.mcbase.entity.hook.marker;

import cn.li.mcbase.entity.IScriptedOwnedEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

public final class OwnerFollowMarkerHook implements ScriptedMarkerHook {
    @Override
    public void onClientTick(Entity entity, ClientLevel level) {
        follow(entity);
    }

    @Override
    public void onServerTick(Entity entity, Level level) {
        follow(entity);
    }

    private static void follow(Entity entity) {
        if (!(entity instanceof IScriptedOwnedEntity owned)) {
            return;
        }
        Player owner = owned.getOwnerPlayer();
        if (owner == null) {
            return;
        }
        entity.setPos(owner.getX(), owner.getY() + 1.1D, owner.getZ());
        entity.setYRot(owner.getYRot());
        entity.setXRot(owner.getXRot());
    }
}
""",
    )

    for folder, ns in MC:
        # implement IScriptedOwnedEntity on effect + marker entities
        for ent, simple in (
            (f"entity/ScriptedEffectEntity.java", "ScriptedEffectEntity"),
            (f"entity/ScriptedMarkerEntity.java", "ScriptedMarkerEntity"),
        ):
            path = ROOT / f"platform-src/minecraft/{folder}/src/main/java/cn/li/{ns}/{ent}"
            text = path.read_text(encoding="utf-8")
            if "IScriptedOwnedEntity" not in text:
                text = text.replace(
                    f"package cn.li.{ns}.entity;\n",
                    f"package cn.li.{ns}.entity;\n\nimport cn.li.mcbase.entity.IScriptedOwnedEntity;\n",
                )
                text = re.sub(
                    rf"public class {simple} extends (\w+)",
                    rf"public class {simple} extends \1 implements IScriptedOwnedEntity",
                    text,
                    count=1,
                )
                # avoid double implements
                text = text.replace(
                    "implements IScriptedOwnedEntity implements",
                    "implements IScriptedOwnedEntity,",
                )
                path.write_text(text, encoding="utf-8", newline="\n")
                print("owned", path.relative_to(ROOT))

        # retarget effect hook implementations to Entity + cast
        effect_dir = ROOT / f"platform-src/minecraft/{folder}/src/main/java/cn/li/{ns}/entity/hook/effect"
        for path in effect_dir.glob("*EffectHook.java"):
            if path.name in {"ScriptedEffectHook.java"}:
                continue
            text = path.read_text(encoding="utf-8")
            text = text.replace(
                f"import cn.li.{ns}.entity.hook.effect.ScriptedEffectHook;\n",
                "import cn.li.mcbase.entity.hook.effect.ScriptedEffectHook;\n",
            )
            if "import cn.li.mcbase.entity.hook.effect.ScriptedEffectHook;" not in text:
                text = text.replace(
                    f"package cn.li.{ns}.entity.hook.effect;\n",
                    f"package cn.li.{ns}.entity.hook.effect;\n\nimport cn.li.mcbase.entity.hook.effect.ScriptedEffectHook;\n",
                )
            if "import net.minecraft.world.entity.Entity;" not in text:
                text = text.replace(
                    "import net.minecraft.world.level.Level;\n",
                    "import net.minecraft.world.entity.Entity;\nimport net.minecraft.world.level.Level;\n",
                )
            # widen override signatures
            text = re.sub(
                r"public void onServerTick\(ScriptedEffectEntity entity, Level level\)",
                "public void onServerTick(Entity raw, Level level)",
                text,
            )
            text = re.sub(
                r"public void onClientTick\(ScriptedEffectEntity entity, ClientLevel level\)",
                "public void onClientTick(Entity raw, ClientLevel level)",
                text,
            )
            # inject cast after method open brace for server/client tick that use entity.
            if "onServerTick(Entity raw" in text and "ScriptedEffectEntity entity = " not in text:
                text = text.replace(
                    "public void onServerTick(Entity raw, Level level) {\n",
                    "public void onServerTick(Entity raw, Level level) {\n"
                    "        if (!(raw instanceof ScriptedEffectEntity entity)) {\n"
                    "            return;\n"
                    "        }\n",
                )
            if "onClientTick(Entity raw" in text and "ScriptedEffectEntity entity = " not in text:
                text = text.replace(
                    "public void onClientTick(Entity raw, ClientLevel level) {\n",
                    "public void onClientTick(Entity raw, ClientLevel level) {\n"
                    "        if (!(raw instanceof ScriptedEffectEntity entity)) {\n"
                    "            return;\n"
                    "        }\n",
                )
            path.write_text(text, encoding="utf-8", newline="\n")
            print("retarget effect", path.relative_to(ROOT))

        # marker hooks other than OwnerFollow / interface / registry
        marker_dir = ROOT / f"platform-src/minecraft/{folder}/src/main/java/cn/li/{ns}/entity/hook/marker"
        for path in marker_dir.glob("*.java"):
            if path.name in {
                "ScriptedMarkerHook.java",
                "ScriptedMarkerHooks.java",
                "OwnerFollowMarkerHook.java",
            }:
                continue
            text = path.read_text(encoding="utf-8")
            text = text.replace(
                f"import cn.li.{ns}.entity.hook.marker.ScriptedMarkerHook;\n",
                "import cn.li.mcbase.entity.hook.marker.ScriptedMarkerHook;\n",
            )
            if "ScriptedMarkerHook" in text and "mcbase.entity.hook.marker.ScriptedMarkerHook" not in text:
                text = text.replace(
                    f"package cn.li.{ns}.entity.hook.marker;\n",
                    f"package cn.li.{ns}.entity.hook.marker;\n\nimport cn.li.mcbase.entity.hook.marker.ScriptedMarkerHook;\n",
                )
            text = re.sub(
                r"public void onClientTick\(ScriptedMarkerEntity entity, ClientLevel level\)",
                "public void onClientTick(Entity raw, ClientLevel level)",
                text,
            )
            text = re.sub(
                r"public void onServerTick\(ScriptedMarkerEntity entity, Level level\)",
                "public void onServerTick(Entity raw, Level level)",
                text,
            )
            if "onClientTick(Entity raw" in text and "ScriptedMarkerEntity entity =" not in text:
                if "import net.minecraft.world.entity.Entity;" not in text:
                    text = text.replace(
                        "import net.minecraft.client.multiplayer.ClientLevel;\n",
                        "import net.minecraft.client.multiplayer.ClientLevel;\nimport net.minecraft.world.entity.Entity;\n",
                    )
                text = text.replace(
                    "public void onClientTick(Entity raw, ClientLevel level) {\n",
                    "public void onClientTick(Entity raw, ClientLevel level) {\n"
                    "        if (!(raw instanceof ScriptedMarkerEntity entity)) {\n"
                    "            return;\n"
                    "        }\n",
                )
            path.write_text(text, encoding="utf-8", newline="\n")
            print("retarget marker", path.relative_to(ROOT))

        ray_dir = ROOT / f"platform-src/minecraft/{folder}/src/main/java/cn/li/{ns}/entity/hook/ray"
        for path in ray_dir.glob("*.java"):
            if path.name in {"ScriptedRayHook.java", "ScriptedRayHooks.java"}:
                continue
            text = path.read_text(encoding="utf-8")
            if "ScriptedRayHook" in text and "mcbase.entity.hook.ray.ScriptedRayHook" not in text:
                text = text.replace(
                    f"package cn.li.{ns}.entity.hook.ray;\n",
                    f"package cn.li.{ns}.entity.hook.ray;\n\nimport cn.li.mcbase.entity.hook.ray.ScriptedRayHook;\n",
                )
            text = re.sub(
                r"public void onClientTick\(ScriptedRayEntity entity, ClientLevel level\)",
                "public void onClientTick(Entity raw, ClientLevel level)",
                text,
            )
            text = re.sub(
                r"public void onServerTick\(ScriptedRayEntity entity, Level level\)",
                "public void onServerTick(Entity raw, Level level)",
                text,
            )
            if "onClientTick(Entity raw" in text and "ScriptedRayEntity entity =" not in text:
                if "import net.minecraft.world.entity.Entity;" not in text:
                    text = text.replace(
                        "import net.minecraft.client.multiplayer.ClientLevel;\n",
                        "import net.minecraft.client.multiplayer.ClientLevel;\nimport net.minecraft.world.entity.Entity;\n",
                    )
                text = text.replace(
                    "public void onClientTick(Entity raw, ClientLevel level) {\n",
                    "public void onClientTick(Entity raw, ClientLevel level) {\n"
                    "        if (!(raw instanceof ScriptedRayEntity entity)) {\n"
                    "            return;\n"
                    "        }\n",
                )
            path.write_text(text, encoding="utf-8", newline="\n")
            print("retarget ray", path.relative_to(ROOT))

        # entity tick call sites: resolve still works; Entity param OK
        for ent in (
            "entity/ScriptedEffectEntity.java",
            "entity/ScriptedMarkerEntity.java",
            "entity/ScriptedRayEntity.java",
        ):
            path = ROOT / f"platform-src/minecraft/{folder}/src/main/java/cn/li/{ns}/{ent}"
            text = path.read_text(encoding="utf-8")
            for old, new in (
                (
                    f"import cn.li.{ns}.entity.hook.effect.ScriptedEffectHook;",
                    "import cn.li.mcbase.entity.hook.effect.ScriptedEffectHook;",
                ),
                (
                    f"import cn.li.{ns}.entity.hook.effect.ScriptedEffectHooks;",
                    "import cn.li.mcbase.entity.hook.effect.ScriptedEffectHooks;",
                ),
                (
                    f"import cn.li.{ns}.entity.hook.marker.ScriptedMarkerHook;",
                    "import cn.li.mcbase.entity.hook.marker.ScriptedMarkerHook;",
                ),
                (
                    f"import cn.li.{ns}.entity.hook.marker.ScriptedMarkerHooks;",
                    "import cn.li.mcbase.entity.hook.marker.ScriptedMarkerHooks;",
                ),
                (
                    f"import cn.li.{ns}.entity.hook.ray.ScriptedRayHook;",
                    "import cn.li.mcbase.entity.hook.ray.ScriptedRayHook;",
                ),
                (
                    f"import cn.li.{ns}.entity.hook.ray.ScriptedRayHooks;",
                    "import cn.li.mcbase.entity.hook.ray.ScriptedRayHooks;",
                ),
            ):
                text = text.replace(old, new)
            path.write_text(text, encoding="utf-8", newline="\n")

    for rel in (
        "entity/hook/effect/ScriptedEffectHook.java",
        "entity/hook/effect/ScriptedEffectHooks.java",
        "entity/hook/marker/ScriptedMarkerHook.java",
        "entity/hook/marker/ScriptedMarkerHooks.java",
        "entity/hook/marker/OwnerFollowMarkerHook.java",
        "entity/hook/ray/ScriptedRayHook.java",
        "entity/hook/ray/ScriptedRayHooks.java",
    ):
        delete_mc(rel)

    rewrite(
        [
            (f"cn.li.{ns}.entity.hook.effect.ScriptedEffectHook", "cn.li.mcbase.entity.hook.effect.ScriptedEffectHook")
            for _, ns in MC
        ]
        + [
            (f"cn.li.{ns}.entity.hook.effect.ScriptedEffectHooks", "cn.li.mcbase.entity.hook.effect.ScriptedEffectHooks")
            for _, ns in MC
        ]
        + [
            (f"cn.li.{ns}.entity.hook.marker.ScriptedMarkerHook", "cn.li.mcbase.entity.hook.marker.ScriptedMarkerHook")
            for _, ns in MC
        ]
        + [
            (f"cn.li.{ns}.entity.hook.marker.ScriptedMarkerHooks", "cn.li.mcbase.entity.hook.marker.ScriptedMarkerHooks")
            for _, ns in MC
        ]
        + [
            (f"cn.li.{ns}.entity.hook.marker.OwnerFollowMarkerHook", "cn.li.mcbase.entity.hook.marker.OwnerFollowMarkerHook")
            for _, ns in MC
        ]
        + [
            (f"cn.li.{ns}.entity.hook.ray.ScriptedRayHook", "cn.li.mcbase.entity.hook.ray.ScriptedRayHook")
            for _, ns in MC
        ]
        + [
            (f"cn.li.{ns}.entity.hook.ray.ScriptedRayHooks", "cn.li.mcbase.entity.hook.ray.ScriptedRayHooks")
            for _, ns in MC
        ]
    )


def promote_item_player_ops() -> None:
    write(
        ROOT / "platform-src/minecraft/base/src/main/java/cn/li/mcbase/runtime/ItemPlayerOps.java",
        """package cn.li.mcbase.runtime;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Player item count/consume/give helpers. Item id resolution is installed per MC version.
 */
public final class ItemPlayerOps {
    @FunctionalInterface
    public interface ItemIdLookup {
        Item getItemById(String itemId);
    }

    private static volatile ItemIdLookup LOOKUP;

    private ItemPlayerOps() {
    }

    public static void installItemIdLookup(ItemIdLookup lookup) {
        LOOKUP = lookup;
    }

    private static ItemIdLookup lookup() {
        ItemIdLookup local = LOOKUP;
        if (local == null) {
            throw new IllegalStateException("ItemPlayerOps ItemIdLookup not installed");
        }
        return local;
    }

    public static int countPlayerItemById(Object playerObj, String itemId) {
        if (!(playerObj instanceof Player player) || itemId == null || itemId.isEmpty()) {
            return 0;
        }
        Item item = lookup().getItemById(itemId);
        if (item == null) {
            return 0;
        }
        return countPlayerItem(player, item);
    }

    public static boolean consumePlayerItemById(Object playerObj, String itemId, int amount) {
        if (!(playerObj instanceof Player player) || itemId == null || itemId.isEmpty() || amount <= 0) {
            return false;
        }
        Item item = lookup().getItemById(itemId);
        if (item == null) {
            return false;
        }
        return consumePlayerItem(player, item, amount);
    }

    public static boolean givePlayerItemStack(Object playerObj, Object stackObj) {
        if (!(playerObj instanceof Player player) || !(stackObj instanceof ItemStack stack) || stack.isEmpty()) {
            return false;
        }
        return givePlayerItemStack(player, stack);
    }

    private static int countPlayerItem(Player player, Item item) {
        int total = 0;
        Inventory inventory = player.getInventory();
        for (ItemStack stack : inventory.items) {
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        for (ItemStack stack : inventory.offhand) {
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static boolean consumePlayerItem(Player player, Item item, int amount) {
        int remaining = amount;
        Inventory inventory = player.getInventory();
        for (ItemStack stack : inventory.items) {
            if (remaining <= 0) {
                break;
            }
            if (!stack.isEmpty() && stack.getItem() == item) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
        }
        for (ItemStack stack : inventory.offhand) {
            if (remaining <= 0) {
                break;
            }
            if (!stack.isEmpty() && stack.getItem() == item) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
        }
        return remaining <= 0;
    }

    private static boolean givePlayerItemStack(Player player, ItemStack stack) {
        ItemStack copy = stack.copy();
        if (!player.getInventory().add(copy)) {
            player.drop(copy, false);
        }
        return true;
    }
}
""",
    )
    delete_mc("runtime/ItemPlayerOps.java")
    for folder, ns in MC:
        reg = ROOT / f"platform-src/minecraft/{folder}/src/main/java/cn/li/{ns}/runtime/ItemRegistry.java"
        text = reg.read_text(encoding="utf-8")
        if "ItemPlayerOps.installItemIdLookup" not in text:
            text = text.replace(
                f"package cn.li.{ns}.runtime;\n",
                f"package cn.li.{ns}.runtime;\n\nimport cn.li.mcbase.runtime.ItemPlayerOps;\n",
            )
            # static install block
            text = text.replace(
                "public final class ItemRegistry {\n",
                "public final class ItemRegistry {\n"
                "    static {\n"
                "        ItemPlayerOps.installItemIdLookup(ItemRegistry::getItemById);\n"
                "    }\n\n",
            )
            reg.write_text(text, encoding="utf-8", newline="\n")
            print("install lookup", reg.relative_to(ROOT))
    rewrite(
        [
            (f"cn.li.{ns}.runtime.ItemPlayerOps", "cn.li.mcbase.runtime.ItemPlayerOps")
            for _, ns in MC
        ]
    )
    # Fix runtime_ops imports cleanly
    for folder, ns in MC:
        path = ROOT / f"platform-src/minecraft/{folder}/src/main/clojure/cn/li/{ns}/platform/runtime_ops.clj"
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8")
        text = re.sub(
            rf"\[cn\.li\.{ns}\.runtime BlockRegistry ItemInventory ItemPlayerOps([^\]]*)\]",
            rf"[cn.li.mcbase.runtime ItemPlayerOps]\n           [cn.li.{ns}.runtime BlockRegistry ItemInventory\1]",
            text,
        )
        path.write_text(text, encoding="utf-8", newline="\n")
        print("runtime_ops", path.relative_to(ROOT))


def promote_placement_and_shared_block() -> None:
    # Unified BlockPlacementHelper (EnumProperty style works on all targets)
    write(
        ROOT / "platform-src/minecraft/base/src/main/java/cn/li/mcbase/block/BlockPlacementHelper.java",
        """package cn.li.mcbase.block;

import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Property;

public final class BlockPlacementHelper {
    private BlockPlacementHelper() {
    }

    @SuppressWarnings("unchecked")
    public static BlockState withHorizontalFacing(Block block, BlockState state, BlockPlaceContext context) {
        if (context == null) {
            return state;
        }
        Property<?> prop = block.getStateDefinition().getProperty("facing");
        if (prop instanceof EnumProperty<?> enumProperty
                && enumProperty.getValueClass() == Direction.class) {
            EnumProperty<Direction> directionProperty = (EnumProperty<Direction>) enumProperty;
            Direction placedFacing = context.getHorizontalDirection().getOpposite();
            if (directionProperty.getPossibleValues().contains(placedFacing)) {
                return state.setValue(directionProperty, placedFacing);
            }
        }
        return state;
    }
}
""",
    )
    # Abstract without codec (1201-compatible); 1211/262 thin Shared subclass adds codec
    src_abs = ROOT / "platform-src/minecraft/mc-1.20.1/src/main/java/cn/li/mc1201/block/AbstractDynamicStateBlock.java"
    abs_text = src_abs.read_text(encoding="utf-8").replace("cn.li.mc1201", "cn.li.mcbase")
    write(ROOT / "platform-src/minecraft/base/src/main/java/cn/li/mcbase/block/AbstractDynamicStateBlock.java", abs_text)

    write(
        ROOT / "platform-src/minecraft/base/src/main/java/cn/li/mcbase/block/SharedDynamicStateBlock.java",
        """package cn.li.mcbase.block;

import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import java.util.List;

/**
 * Loader-agnostic dynamic block (no BlockEntity).
 */
public class SharedDynamicStateBlock extends AbstractDynamicStateBlock {

    public static SharedDynamicStateBlock create(String blockId,
                                                 List<Property<?>> properties,
                                                 BlockBehaviour.Properties behaviourProperties) {
        INIT_CONTEXT.set(new InitContext(blockId, properties));
        try {
            return new SharedDynamicStateBlock(behaviourProperties);
        } finally {
            INIT_CONTEXT.remove();
        }
    }

    public SharedDynamicStateBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return BlockPlacementHelper.withHorizontalFacing(this, this.defaultBlockState(), context);
    }
}
""",
    )

    delete_mc("block/BlockPlacementHelper.java")
    delete_mc("block/AbstractDynamicStateBlock.java")
    delete_mc("block/SharedDynamicStateBlock.java")

    # 1211/262: thin codec-bearing subclass kept under version package name used by bootstrap
    for folder, ns in (("mc-1.21.1", "mc1211"), ("mc-26.2", "mc262")):
        write(
            ROOT / f"platform-src/minecraft/{folder}/src/main/java/cn/li/{ns}/block/SharedDynamicStateBlock.java",
            f"""package cn.li.{ns}.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.List;

/**
 * Version-local SharedDynamicStateBlock that supplies MapCodec required by MC {ns}.
 */
public class SharedDynamicStateBlock extends cn.li.mcbase.block.SharedDynamicStateBlock {{

    public static SharedDynamicStateBlock create(String blockId,
                                                 List<Property<?>> properties,
                                                 BlockBehaviour.Properties behaviourProperties) {{
        INIT_CONTEXT.set(new InitContext(blockId, properties));
        try {{
            return new SharedDynamicStateBlock(behaviourProperties);
        }} finally {{
            INIT_CONTEXT.remove();
        }}
    }}

    public SharedDynamicStateBlock(BlockBehaviour.Properties properties) {{
        super(properties);
    }}

    @Override
    protected MapCodec<? extends Block> codec() {{
        return MapCodec.unit(this);
    }}
}}
""",
        )

    # 1201 uses mcbase Shared directly
    rewrite(
        [
            ("cn.li.mc1201.block.SharedDynamicStateBlock", "cn.li.mcbase.block.SharedDynamicStateBlock"),
            ("cn.li.mc1201.block.BlockPlacementHelper", "cn.li.mcbase.block.BlockPlacementHelper"),
            ("cn.li.mc1201.block.AbstractDynamicStateBlock", "cn.li.mcbase.block.AbstractDynamicStateBlock"),
            ("cn.li.mc1211.block.BlockPlacementHelper", "cn.li.mcbase.block.BlockPlacementHelper"),
            ("cn.li.mc1211.block.AbstractDynamicStateBlock", "cn.li.mcbase.block.AbstractDynamicStateBlock"),
            ("cn.li.mc262.block.BlockPlacementHelper", "cn.li.mcbase.block.BlockPlacementHelper"),
            ("cn.li.mc262.block.AbstractDynamicStateBlock", "cn.li.mcbase.block.AbstractDynamicStateBlock"),
        ]
    )
    # SharedScriptedBlock still versioned - update placement helper import only (done).
    # Bootstrap helpers on 1211/262 keep cn.li.mc*/SharedDynamicStateBlock (thin subclass).


def promote_neo() -> None:
    write(
        ROOT / "platform-src/loader/neoforge-shared/src/main/java/cn/li/neoforgebase/capability/CapabilityKeyIndex.java",
        """package cn.li.neoforgebase.capability;

import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.ItemCapability;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared reverse-index for capability token → string key lookups.
 */
public final class CapabilityKeyIndex {
    private static final Map<BlockCapability<?, ?>, String> BLOCK_TO_KEY =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<ItemCapability<?, ?>, String> ITEM_TO_KEY =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private CapabilityKeyIndex() {
    }

    public static void putBlock(BlockCapability<?, ?> cap, String key) {
        BLOCK_TO_KEY.put(cap, key);
    }

    public static void putItem(ItemCapability<?, ?> cap, String key) {
        ITEM_TO_KEY.put(cap, key);
    }

    @Nullable
    public static String getKey(BlockCapability<?, ?> cap) {
        return BLOCK_TO_KEY.get(cap);
    }

    @Nullable
    public static String getKey(ItemCapability<?, ?> cap) {
        return ITEM_TO_KEY.get(cap);
    }

    public static List<String> keysFor(BlockCapability<?, ?> cap) {
        if (cap == null) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        synchronized (BLOCK_TO_KEY) {
            for (Map.Entry<BlockCapability<?, ?>, String> entry : BLOCK_TO_KEY.entrySet()) {
                if (entry.getKey() == cap) {
                    keys.add(entry.getValue());
                }
            }
        }
        return keys;
    }
}
""",
    )
    write(
        ROOT / "platform-src/loader/neoforge-shared/src/main/java/cn/li/neoforgebase/capability/ForgeCapabilityQuery.java",
        """package cn.li.neoforgebase.capability;

import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.ItemCapability;

import javax.annotation.Nullable;

/** Read-only capability registry queries for NeoForge capability handling. */
public final class ForgeCapabilityQuery {
    private ForgeCapabilityQuery() {
    }

    @Nullable
    public static String getKey(BlockCapability<?, ?> cap) {
        return CapabilityKeyIndex.getKey(cap);
    }

    @Nullable
    public static String getKey(ItemCapability<?, ?> cap) {
        return CapabilityKeyIndex.getKey(cap);
    }
}
""",
    )

    for folder, ns in NEO:
        path = ROOT / f"platform-src/loader/{folder}/src/main/java/cn/li/{ns}/capability/CapabilityRegistry.java"
        text = path.read_text(encoding="utf-8")
        if "CapabilityKeyIndex" not in text:
            text = text.replace(
                f"package cn.li.{ns}.capability;\n",
                f"package cn.li.{ns}.capability;\n\nimport cn.li.neoforgebase.capability.CapabilityKeyIndex;\n",
            )
            # remove local reverse maps usage for getKey - keep maps for forward lookup but sync index
            text = text.replace(
                "BLOCK_TO_KEY.put(cap, key);",
                "BLOCK_TO_KEY.put(cap, key);\n        CapabilityKeyIndex.putBlock(cap, key);",
            )
            text = text.replace(
                "ITEM_TO_KEY.put(cap, key);",
                "ITEM_TO_KEY.put(cap, key);\n        CapabilityKeyIndex.putItem(cap, key);",
            )
            text = re.sub(
                r"public static String getKey\(BlockCapability<\?, \?> cap\) \{\s*return BLOCK_TO_KEY\.get\(cap\);\s*\}",
                "public static String getKey(BlockCapability<?, ?> cap) {\n        return CapabilityKeyIndex.getKey(cap);\n    }",
                text,
            )
            text = re.sub(
                r"public static String getKey\(ItemCapability<\?, \?> cap\) \{\s*return ITEM_TO_KEY\.get\(cap\);\s*\}",
                "public static String getKey(ItemCapability<?, ?> cap) {\n        return CapabilityKeyIndex.getKey(cap);\n    }",
                text,
            )
            path.write_text(text, encoding="utf-8", newline="\n")
            print("cap registry", path.relative_to(ROOT))
        q = ROOT / f"platform-src/loader/{folder}/src/main/java/cn/li/{ns}/capability/ForgeCapabilityQuery.java"
        if q.exists():
            q.unlink()
            print("delete", q.relative_to(ROOT))

    rewrite(
        [
            (f"cn.li.{ns}.capability.ForgeCapabilityQuery", "cn.li.neoforgebase.capability.ForgeCapabilityQuery")
            for _, ns in NEO
        ]
    )

    # gui_open_port with installable open fn
    write(
        ROOT / "platform-src/loader/neoforge-shared/src/main/clojure/cn/li/neoforgebase/integration/events/gui_open_port.clj",
        """(ns cn.li.neoforgebase.integration.events.gui-open-port
  "Shared GUI opening port. Version loaders install the concrete open-fn."
  (:require [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.world.level Level]))

(defonce ^:private *open-gui-fn*
  (atom nil))

(defn install-open-gui!
  "Install (fn [player gui-id tile-entity] ...) used by open-gui-for-result."
  [f]
  (reset! *open-gui-fn* f)
  f)

(defn open-gui-for-result
  [gui-id player world _pos tile-entity]
  (when (and tile-entity (not (.isClientSide ^Level world)))
    (log/info "[RIGHT-CLICK] Opening GUI on server side...")
    (when-let [f @*open-gui-fn*]
      (f player gui-id tile-entity))))
""",
    )

    write(
        ROOT / "platform-src/loader/neoforge-shared/src/main/clojure/cn/li/neoforgebase/integration/events/entity_attributes.clj",
        """(ns cn.li.neoforgebase.integration.events.entity-attributes
  "Shared scripted-mob attribute registration. Version loaders install ModEntities bridge."
  (:require [cn.li.neoforgebase.registry.state :as registry-state]
            [cn.li.mcmod.entity.dsl :as edsl]
            [cn.li.mcmod.util.log :as log])
  (:import [net.neoforged.neoforge.registries DeferredHolder]))

(defonce ^:private *register-mob-attrs*
  (atom nil))

(defn install-register-mob-attrs!
  "Install (fn [event entity-type] ...) → ModEntities/registerMobDefaultAttributes."
  [f]
  (reset! *register-mob-attrs* f)
  f)

(defn handle-entity-attribute-creation
  "Register PathfinderMob default attributes for every :scripted-mob entity type."
  [^net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent event]
  (let [register! @*register-mob-attrs*]
    (when (nil? register!)
      (throw (IllegalStateException. "entity-attributes register-mob-attrs not installed")))
    (doseq [entity-id (edsl/list-entities)]
      (let [entity-spec (edsl/get-entity entity-id)]
        (when (= :scripted-mob (:entity-kind entity-spec))
          (if-let [^DeferredHolder ro (registry-state/get-registered-entity-ro entity-id)]
            (when (.isBound ro)
              (register! event (.get ro))
              (log/info "Registered attributes for"
                        (edsl/get-entity-registry-name entity-id)))
            (log/warn "No registered entity type for scripted-mob"
                      {:entity-id entity-id})))))))
""",
    )

    for folder, ns in NEO:
        # delete version copies
        for rel in (
            "integration/events/gui_open_port.clj",
            "integration/events/entity_attributes.clj",
        ):
            p = ROOT / f"platform-src/loader/{folder}/src/main/clojure/cn/li/{ns}/{rel}"
            if p.exists():
                p.unlink()
                print("delete", p.relative_to(ROOT))

        # install open-gui from gui_registry into a small installer ns or client/init / setup
        # Prefer patching adapter/gui_registry to install on load, and event_registration_manifest requires
        gui = ROOT / f"platform-src/loader/{folder}/src/main/clojure/cn/li/{ns}/adapter/gui_registry.clj"
        text = gui.read_text(encoding="utf-8", errors="surrogateescape")
        if "gui-open-port/install-open-gui!" not in text:
            text = text.replace(
                f"(ns cn.li.{ns}.adapter.gui-registry\n",
                f"(ns cn.li.{ns}.adapter.gui-registry\n",
            )
            # add require
            if "neoforgebase.integration.events.gui-open-port" not in text:
                text = text.replace(
                    "(:require ",
                    "(:require [cn.li.neoforgebase.integration.events.gui-open-port :as gui-open-port]\n            ",
                    1,
                )
            # install at end of file
            text = text.rstrip() + (
                "\n\n(gui-open-port/install-open-gui!\n"
                "  (fn [player gui-id tile-entity]\n"
                "    (open-gui-for-player player gui-id tile-entity)))\n"
            )
            gui.write_text(text, encoding="utf-8", newline="\n")
            print("install gui", gui.relative_to(ROOT))

        # install attrs near ModEntities usage - patch event_registration_manifest / a setup file
        # Create tiny installer in version setup
        install = ROOT / f"platform-src/loader/{folder}/src/main/clojure/cn/li/{ns}/setup/shared_event_install.clj"
        write(
            install,
            f"""(ns cn.li.{ns}.setup.shared-event-install
  "Install NeoForge-shared event ports that need versioned Mod* bridges."
  (:require [cn.li.neoforgebase.integration.events.entity-attributes :as entity-attr-events])
  (:import [cn.li.{ns}.entity ModEntities]))

(defn install!
  []
  (entity-attr-events/install-register-mob-attrs!
    (fn [event entity-type]
      (ModEntities/registerMobDefaultAttributes event entity-type))))
""",
        )

        # call install! from common.clj or event_registration
        common = ROOT / f"platform-src/loader/{folder}/src/main/clojure/cn/li/{ns}/setup/common.clj"
        if common.exists():
            text = common.read_text(encoding="utf-8", errors="surrogateescape")
            if "shared-event-install" not in text:
                text = text.replace(
                    "(:require ",
                    f"(:require [cn.li.{ns}.setup.shared-event-install :as shared-event-install]\n            ",
                    1,
                )
                # invoke early in init if there's a clear init fn - append install call at end as side effect
                text = text.rstrip() + "\n\n(shared-event-install/install!)\n"
                common.write_text(text, encoding="utf-8", newline="\n")
                print("common install", common.relative_to(ROOT))

        # rewrite requires for promoted ns
        rewrite(
            [
                (f"cn.li.{ns}.integration.events.gui-open-port", "cn.li.neoforgebase.integration.events.gui-open-port"),
                (f"cn.li.{ns}.integration.events.entity-attributes", "cn.li.neoforgebase.integration.events.entity-attributes"),
            ],
            [f"platform-src/loader/{folder}"],
        )


def main() -> None:
    promote_hooks()
    promote_item_player_ops()
    promote_placement_and_shared_block()
    promote_neo()
    print("BATCH3 DONE")


if __name__ == "__main__":
    main()
