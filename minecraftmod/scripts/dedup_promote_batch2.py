#!/usr/bin/env python3
"""Promote remaining closed identical clusters unlocked by markers / parameterization."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MC_VERSIONS = [("mc-1.20.1", "mc1201"), ("mc-1.21.1", "mc1211"), ("mc-26.2", "mc262")]
NEO_VERSIONS = [("neoforge-1.21.1", "neoforge1211"), ("neoforge-26.2", "neoforge262")]


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")
    print("write", path.relative_to(ROOT))


def delete_versioned(rel: str, kind: str = "java") -> None:
    for folder, ns in MC_VERSIONS:
        if kind == "java":
            p = ROOT / f"platform-src/minecraft/{folder}/src/main/java/cn/li/{ns}/{rel}"
        else:
            # rel uses underscores for clj filename
            p = ROOT / f"platform-src/minecraft/{folder}/src/main/clojure/cn/li/{ns}/{rel}"
        if p.exists():
            p.unlink()
            print("delete", p.relative_to(ROOT))


def rewrite_tree(replacements: list[tuple[str, str]], roots: list[str]) -> None:
    for root_name in roots:
        root = ROOT / root_name
        for path in root.rglob("*"):
            if path.suffix not in {".java", ".clj"}:
                continue
            posix = path.as_posix().replace("\\", "/")
            if "/minecraft/base/" in posix or "/neoforge-shared/" in posix:
                continue
            text = path.read_text(encoding="utf-8", errors="surrogateescape")
            orig = text
            for a, b in replacements:
                text = text.replace(a, b)
            if text != orig:
                path.write_text(text, encoding="utf-8", errors="surrogateescape", newline="\n")
                print("rewrite", path.relative_to(ROOT))


def expand_block_entity_marker() -> None:
    write(
        ROOT
        / "platform-src/minecraft/base/src/main/java/cn/li/mcbase/block/entity/IScriptedBlockEntity.java",
        """package cn.li.mcbase.block.entity;

/**
 * Version-neutral scripted block-entity surface implemented by each version's
 * AbstractScriptedBlockEntity.
 */
public interface IScriptedBlockEntity {
    Object getCustomState();

    void setCustomState(Object state);
}
""",
    )


def unlock_logic_compile() -> None:
    for folder, ns in MC_VERSIONS:
        path = (
            ROOT
            / f"platform-src/minecraft/{folder}/src/main/clojure/cn/li/{ns}/block/logic_compile.clj"
        )
        text = path.read_text(encoding="utf-8")
        text = text.replace(
            f"[cn.li.{ns}.block.entity AbstractScriptedBlockEntity]\n",
            "",
        )
        if "IScriptedBlockEntity" not in text:
            text = text.replace(
                "[cn.li.mcbase.block.logic\n",
                "[cn.li.mcbase.block.entity IScriptedBlockEntity]\n"
                "           [cn.li.mcbase.block.logic\n",
            )
        text = text.replace("AbstractScriptedBlockEntity", "IScriptedBlockEntity")
        path.write_text(text, encoding="utf-8", newline="\n")
        print("unlock", path.relative_to(ROOT))


def unlock_mob_pipeline() -> None:
    for folder, ns in MC_VERSIONS:
        path = (
            ROOT
            / f"platform-src/minecraft/{folder}/src/main/clojure/cn/li/{ns}/entity/mob_logic_pipeline.clj"
        )
        text = path.read_text(encoding="utf-8")
        text = text.replace(
            f"[cn.li.{ns}.entity ScriptedEntityLogicRegistry]",
            "[cn.li.mcbase.entity ScriptedEntityLogicRegistry]",
        )
        path.write_text(text, encoding="utf-8", newline="\n")
        print("unlock", path.relative_to(ROOT))


def promote_mc_java(rel: str) -> None:
    src = ROOT / f"platform-src/minecraft/mc-1.20.1/src/main/java/cn/li/mc1201/{rel}"
    text = src.read_text(encoding="utf-8").replace("cn.li.mc1201", "cn.li.mcbase")
    # refresh javadoc that still says mc1201 module
    text = text.replace("shared mc1201 module", "shared mcbase module")
    write(ROOT / f"platform-src/minecraft/base/src/main/java/cn/li/mcbase/{rel}", text)
    delete_versioned(rel, "java")


def promote_mc_clj(rel_underscored: str, ns_suffix: str) -> None:
    """rel_underscored e.g. block/logic_compile.clj; ns_suffix e.g. block.logic-compile"""
    src = (
        ROOT
        / f"platform-src/minecraft/mc-1.20.1/src/main/clojure/cn/li/mc1201/{rel_underscored}"
    )
    text = src.read_text(encoding="utf-8")
    text = text.replace("cn.li.mc1201", "cn.li.mcbase")
    write(
        ROOT / f"platform-src/minecraft/base/src/main/clojure/cn/li/mcbase/{rel_underscored}",
        text,
    )
    delete_versioned(rel_underscored, "clj")
    rewrite_tree(
        [
            (f"cn.li.mc1201.{ns_suffix}", f"cn.li.mcbase.{ns_suffix}"),
            (f"cn.li.mc1211.{ns_suffix}", f"cn.li.mcbase.{ns_suffix}"),
            (f"cn.li.mc262.{ns_suffix}", f"cn.li.mcbase.{ns_suffix}"),
        ],
        ["platform-src/minecraft", "platform-src/loader"],
    )


def promote_command_registrar() -> None:
    dest = (
        ROOT
        / "platform-src/loader/neoforge-shared/src/main/java/cn/li/neoforgebase/event/ForgeCommandRegistrar.java"
    )
    write(
        dest,
        """package cn.li.neoforgebase.event;

import clojure.java.api.Clojure;
import clojure.lang.Var;
import cn.li.mcbase.clj.ClojureInterop;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

/**
 * Shared NeoForge command registration bridge.
 * Callers pass the versioned commands namespace (e.g. cn.li.neoforge1211.commands).
 */
public final class ForgeCommandRegistrar {
    private ForgeCommandRegistrar() {
    }

    public static void registerAll(RegisterCommandsEvent event, Logger logger, String commandsNs) {
        try {
            ClojureInterop.requireNamespace(commandsNs);

            Var handler = (Var) Clojure.var(commandsNs, "register-all-commands");
            if (!handler.isBound()) {
                throw new IllegalStateException("register-all-commands is unbound after require");
            }

            handler.invoke(event.getDispatcher(), event.getBuildContext());
        } catch (Throwable t) {
            logger.error("[ForgeEventBusManager] Failed to register commands", t);
        }
    }
}
""",
    )
    for folder, ns in NEO_VERSIONS:
        p = (
            ROOT
            / f"platform-src/loader/{folder}/src/main/java/cn/li/{ns}/event/ForgeCommandRegistrar.java"
        )
        if p.exists():
            p.unlink()
            print("delete", p.relative_to(ROOT))
        mgr = (
            ROOT
            / f"platform-src/loader/{folder}/src/main/java/cn/li/{ns}/event/ForgeEventBusManager.java"
        )
        text = mgr.read_text(encoding="utf-8")
        if "cn.li.neoforgebase.event.ForgeCommandRegistrar" not in text:
            text = text.replace(
                f"package cn.li.{ns}.event;\n",
                f"package cn.li.{ns}.event;\n\nimport cn.li.neoforgebase.event.ForgeCommandRegistrar;\n",
            )
        text = re.sub(
            r"ForgeCommandRegistrar\.registerAll\(event,\s*LOGGER\);",
            f'ForgeCommandRegistrar.registerAll(event, LOGGER, "cn.li.{ns}.commands");',
            text,
        )
        mgr.write_text(text, encoding="utf-8", newline="\n")
        print("update", mgr.relative_to(ROOT))


def main() -> None:
    expand_block_entity_marker()
    unlock_logic_compile()
    unlock_mob_pipeline()

    promote_mc_java("client/MinecraftClientAccess.java")
    rewrite_tree(
        [
            ("cn.li.mc1201.client.MinecraftClientAccess", "cn.li.mcbase.client.MinecraftClientAccess"),
            ("cn.li.mc1211.client.MinecraftClientAccess", "cn.li.mcbase.client.MinecraftClientAccess"),
            ("cn.li.mc262.client.MinecraftClientAccess", "cn.li.mcbase.client.MinecraftClientAccess"),
        ],
        ["platform-src/minecraft", "platform-src/loader"],
    )

    # Order: compile before pipeline
    promote_mc_clj("block/logic_compile.clj", "block.logic-compile")
    promote_mc_clj("block/logic_pipeline.clj", "block.logic-pipeline")
    promote_mc_clj("entity/mob_logic_compile.clj", "entity.mob-logic-compile")
    promote_mc_clj("entity/mob_logic_pipeline.clj", "entity.mob-logic-pipeline")

    promote_command_registrar()
    print("DONE")


if __name__ == "__main__":
    main()
