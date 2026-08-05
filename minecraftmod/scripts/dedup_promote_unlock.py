#!/usr/bin/env python3
"""Unlock tile/mob logic cluster via mcbase markers and promote identical closed files."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MC_VERSIONS = [("mc-1.20.1", "mc1201"), ("mc-1.21.1", "mc1211"), ("mc-26.2", "mc262")]

MC_PROMOTE = [
    "block/IScriptedBlock.java",
    "block/logic/ITileTickLogic.java",
    "block/logic/ITileNbtLogic.java",
    "block/logic/ITileContainerLogic.java",
    "block/logic/ITileCapabilityLogic.java",
    "block/logic/TileLogicBundle.java",
    "block/capability/ScriptedCapabilityResolver.java",
    "shim/FnTileTickLogic.java",
    "shim/FnTileNbtLogic.java",
    "entity/logic/IMobTickLogic.java",
    "entity/logic/IMobHurtLogic.java",
    "entity/logic/IMobDeathLogic.java",
    "entity/logic/IMobLootLogic.java",
    "entity/logic/MobLogicBundle.java",
    "entity/ScriptedEntityLogicRegistry.java",
    "shim/FnMobTickLogic.java",
    "shim/FnMobHurtLogic.java",
    "shim/FnMobDeathLogic.java",
    "shim/FnMobLootLogic.java",
    "entity/ScriptedEntitySpecAccess.java",
]


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")
    print("write", path.relative_to(ROOT))


def main() -> None:
    write(
        ROOT
        / "platform-src/minecraft/base/src/main/java/cn/li/mcbase/block/entity/IScriptedBlockEntity.java",
        """package cn.li.mcbase.block.entity;

/**
 * Version-neutral marker implemented by each version's AbstractScriptedBlockEntity.
 */
public interface IScriptedBlockEntity {
}
""",
    )
    write(
        ROOT / "platform-src/minecraft/base/src/main/java/cn/li/mcbase/entity/IScriptedMob.java",
        """package cn.li.mcbase.entity;

/**
 * Version-neutral marker implemented by each version's ScriptedMobEntity.
 */
public interface IScriptedMob {
}
""",
    )

    for folder, ns in MC_VERSIONS:
        be = ROOT / f"platform-src/minecraft/{folder}/src/main/java/cn/li/{ns}/block/entity/AbstractScriptedBlockEntity.java"
        text = be.read_text(encoding="utf-8")
        if "IScriptedBlockEntity" not in text:
            text = text.replace(
                f"package cn.li.{ns}.block.entity;\n",
                f"package cn.li.{ns}.block.entity;\n\nimport cn.li.mcbase.block.entity.IScriptedBlockEntity;\n",
            )
            text = text.replace(
                "public abstract class AbstractScriptedBlockEntity extends BlockEntity {",
                "public abstract class AbstractScriptedBlockEntity extends BlockEntity implements IScriptedBlockEntity {",
            )
            be.write_text(text, encoding="utf-8")
            print("implements BE", be)

        mob = ROOT / f"platform-src/minecraft/{folder}/src/main/java/cn/li/{ns}/entity/ScriptedMobEntity.java"
        text = mob.read_text(encoding="utf-8")
        if "IScriptedMob" not in text:
            text = text.replace(
                f"package cn.li.{ns}.entity;\n",
                f"package cn.li.{ns}.entity;\n\nimport cn.li.mcbase.entity.IScriptedMob;\n",
            )
            text = text.replace(
                "public class ScriptedMobEntity extends PathfinderMob {",
                "public class ScriptedMobEntity extends PathfinderMob implements IScriptedMob {",
            )
            mob.write_text(text, encoding="utf-8")
            print("implements Mob", mob)

    # Retarget tile/mob logic types onto markers before promotion.
    tile_rels = [
        "block/logic/ITileTickLogic.java",
        "block/logic/ITileNbtLogic.java",
        "block/logic/ITileContainerLogic.java",
        "block/logic/ITileCapabilityLogic.java",
        "shim/FnTileTickLogic.java",
        "shim/FnTileNbtLogic.java",
        "block/capability/ScriptedCapabilityResolver.java",
    ]
    for rel in tile_rels:
        for folder, ns in MC_VERSIONS:
            path = ROOT / f"platform-src/minecraft/{folder}/src/main/java/cn/li/{ns}/{rel}"
            if not path.exists():
                continue
            text = path.read_text(encoding="utf-8")
            text = text.replace(
                f"import cn.li.{ns}.block.entity.AbstractScriptedBlockEntity;\n",
                "import cn.li.mcbase.block.entity.IScriptedBlockEntity;\n",
            )
            text = text.replace("AbstractScriptedBlockEntity", "IScriptedBlockEntity")
            if rel.endswith("ScriptedCapabilityResolver.java"):
                text = text.replace("import cn.li.mcbase.block.entity.IScriptedBlockEntity;\n", "")
                if "import net.minecraft.world.level.block.entity.BlockEntity;" not in text:
                    text = text.replace(
                        "import net.minecraft.world.level.block.Block;\n",
                        "import net.minecraft.world.level.block.Block;\nimport net.minecraft.world.level.block.entity.BlockEntity;\n",
                    )
                text = re.sub(
                    r"public static Object resolve\([^,]+ be, String key",
                    "public static Object resolve(BlockEntity be, String key",
                    text,
                )
            path.write_text(text, encoding="utf-8")
            print("retarget tile", path.relative_to(ROOT))

    mob_rels = [
        "entity/logic/IMobTickLogic.java",
        "entity/logic/IMobHurtLogic.java",
        "entity/logic/IMobDeathLogic.java",
        "entity/logic/IMobLootLogic.java",
        "shim/FnMobTickLogic.java",
        "shim/FnMobHurtLogic.java",
        "shim/FnMobDeathLogic.java",
        "shim/FnMobLootLogic.java",
    ]
    for rel in mob_rels:
        for folder, ns in MC_VERSIONS:
            path = ROOT / f"platform-src/minecraft/{folder}/src/main/java/cn/li/{ns}/{rel}"
            if not path.exists():
                continue
            text = path.read_text(encoding="utf-8")
            text = text.replace(
                f"import cn.li.{ns}.entity.ScriptedMobEntity;\n",
                "import cn.li.mcbase.entity.IScriptedMob;\n",
            )
            text = text.replace("ScriptedMobEntity", "IScriptedMob")
            path.write_text(text, encoding="utf-8")
            print("retarget mob", path.relative_to(ROOT))

    # Promote from 1.20.1 copies (now marker-based).
    for rel in MC_PROMOTE:
        src = ROOT / f"platform-src/minecraft/mc-1.20.1/src/main/java/cn/li/mc1201/{rel}"
        if not src.exists():
            raise SystemExit(f"missing source {src}")
        text = src.read_text(encoding="utf-8").replace("cn.li.mc1201", "cn.li.mcbase")
        dest = ROOT / f"platform-src/minecraft/base/src/main/java/cn/li/mcbase/{rel}"
        write(dest, text)
        for folder, ns in MC_VERSIONS:
            p = ROOT / f"platform-src/minecraft/{folder}/src/main/java/cn/li/{ns}/{rel}"
            if p.exists():
                p.unlink()
                print("delete", p.relative_to(ROOT))

    # Rewrite remaining references in version/loader trees.
    replacements = []
    for ns in ("mc1201", "mc1211", "mc262"):
        replacements.extend(
            [
                (f"cn.li.{ns}.block.IScriptedBlock", "cn.li.mcbase.block.IScriptedBlock"),
                (f"cn.li.{ns}.block.logic.", "cn.li.mcbase.block.logic."),
                (
                    f"cn.li.{ns}.block.capability.ScriptedCapabilityResolver",
                    "cn.li.mcbase.block.capability.ScriptedCapabilityResolver",
                ),
                (f"cn.li.{ns}.entity.logic.", "cn.li.mcbase.entity.logic."),
                (
                    f"cn.li.{ns}.entity.ScriptedEntityLogicRegistry",
                    "cn.li.mcbase.entity.ScriptedEntityLogicRegistry",
                ),
                (
                    f"cn.li.{ns}.entity.ScriptedEntitySpecAccess",
                    "cn.li.mcbase.entity.ScriptedEntitySpecAccess",
                ),
                (f"cn.li.{ns}.shim.FnTile", "cn.li.mcbase.shim.FnTile"),
                (f"cn.li.{ns}.shim.FnMob", "cn.li.mcbase.shim.FnMob"),
            ]
        )

    for root_name in ("platform-src/minecraft", "platform-src/loader"):
        root = ROOT / root_name
        for path in root.rglob("*"):
            if path.suffix not in {".java", ".clj"}:
                continue
            if "minecraft/base/" in path.as_posix().replace("\\", "/"):
                continue
            text = path.read_text(encoding="utf-8")
            orig = text
            for a, b in replacements:
                text = text.replace(a, b)
            if text != orig:
                path.write_text(text, encoding="utf-8")
                print("rewrite", path.relative_to(ROOT))

    # NeoForge: promote server_context (false-positive blocked earlier).
    rel = "adapter/server_context.clj"
    src = ROOT / "platform-src/loader/neoforge-1.21.1/src/main/clojure/cn/li/neoforge1211" / rel
    text = src.read_text(encoding="utf-8").replace("cn.li.neoforge1211", "cn.li.neoforgebase")
    dest = ROOT / "platform-src/loader/neoforge-shared/src/main/clojure/cn/li/neoforgebase" / rel
    write(dest, text)
    for folder, ns in (("neoforge-1.21.1", "neoforge1211"), ("neoforge-26.2", "neoforge262")):
        p = ROOT / f"platform-src/loader/{folder}/src/main/clojure/cn/li/{ns}/{rel}"
        if p.exists():
            p.unlink()
            print("delete", p.relative_to(ROOT))
    for path in (ROOT / "platform-src/loader").rglob("*.clj"):
        text = path.read_text(encoding="utf-8")
        n = text.replace(
            "cn.li.neoforge1211.adapter.server-context",
            "cn.li.neoforgebase.adapter.server-context",
        ).replace(
            "cn.li.neoforge262.adapter.server-context",
            "cn.li.neoforgebase.adapter.server-context",
        )
        if n != text:
            path.write_text(n, encoding="utf-8")
            print("rewrite", path.relative_to(ROOT))

    print("DONE")


if __name__ == "__main__":
    main()
