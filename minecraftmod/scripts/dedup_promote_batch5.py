#!/usr/bin/env python3
"""Batch5: SPI-unlock screen/command/runtime adapters + multipart + NeoForge registry-binding."""
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


def read_mc(rel: str, folder: str, ns: str) -> Path:
    for kind in ("clojure", "java"):
        p = ROOT / f"platform-src/minecraft/{folder}/src/main/{kind}/cn/li/{ns}/{rel}"
        if p.exists():
            return p
    raise FileNotFoundError(rel)


def delete_mc(rel: str) -> None:
    for folder, ns in MC:
        for kind in ("clojure", "java"):
            p = ROOT / f"platform-src/minecraft/{folder}/src/main/{kind}/cn/li/{ns}/{rel}"
            if p.exists():
                p.unlink()
                print("delete", p.relative_to(ROOT))


def rewrite_exact(reps: list[tuple[str, str]], roots: list[Path] | None = None) -> None:
    reps = sorted(reps, key=lambda x: -len(x[0]))
    search_roots = roots or [ROOT / "platform-src"]
    for root in search_roots:
        if not root.exists():
            continue
        for path in root.rglob("*"):
            if path.suffix not in {".clj", ".java"}:
                continue
            posix = path.as_posix().replace("\\", "/")
            if "/minecraft/base/" in posix or "/neoforge-shared/" in posix:
                continue
            text = path.read_text(encoding="utf-8", errors="surrogateescape")
            orig = text
            for a, b in reps:
                text = text.replace(a, b)
            if text != orig:
                path.write_text(text, encoding="utf-8", errors="surrogateescape", newline="\n")
                print("rewrite", path.relative_to(ROOT))


def promote_clj(rel: str, ns_suffix: str, source_ns: str = "mc1201") -> None:
    folder = {"mc1201": "mc-1.20.1", "mc1211": "mc-1.21.1", "mc262": "mc-26.2"}[source_ns]
    src = read_mc(rel, folder, source_ns)
    text = src.read_text(encoding="utf-8", errors="replace").replace(f"cn.li.{source_ns}", "cn.li.mcbase")
    write(ROOT / f"platform-src/minecraft/base/src/main/clojure/cn/li/mcbase/{rel}", text)
    delete_mc(rel)
    rewrite_exact(
        [
            (f"cn.li.mc1201.{ns_suffix}", f"cn.li.mcbase.{ns_suffix}"),
            (f"cn.li.mc1211.{ns_suffix}", f"cn.li.mcbase.{ns_suffix}"),
            (f"cn.li.mc262.{ns_suffix}", f"cn.li.mcbase.{ns_suffix}"),
        ]
    )


def unify_from_1201(rel: str) -> None:
    src = read_mc(rel, "mc-1.20.1", "mc1201")
    base = src.read_text(encoding="utf-8", errors="replace")
    for folder, ns in (("mc-1.21.1", "mc1211"), ("mc-26.2", "mc262")):
        dest = read_mc(rel, folder, ns)
        text = base.replace("cn.li.mc1201", f"cn.li.{ns}")
        dest.write_text(text, encoding="utf-8", newline="\n")
        print("unify", dest.relative_to(ROOT))


def unlock_screen() -> None:
    """Inject create-tech-ui-container-screen into screen/impl."""
    for folder, ns in MC:
        path = read_mc("gui/screen/impl.clj", folder, ns)
        text = path.read_text(encoding="utf-8", errors="replace")
        # drop host-container require
        text = re.sub(
            rf"\[cn\.li\.(mc1201|mc1211|mc262)\.gui\.reactive\.host-container :as reactive-host\]\s*",
            "",
            text,
        )
        if "create-tech-ui-container-screen-atom" not in text:
            text = text.replace(
                "[cn.li.mcmod.runtime.owner :as runtime-owner])\n",
                """[cn.li.mcmod.runtime.owner :as runtime-owner]))

(defonce ^:private create-tech-ui-container-screen-atom
  (atom nil))

(defn install-create-tech-ui-container-screen!
  "Install versioned host-container create-tech-ui-container-screen."
  [f]
  (reset! create-tech-ui-container-screen-atom f)
  f)

(defn- create-tech-ui-container-screen
  [screen-data]
  (let [f @create-tech-ui-container-screen-atom]
    (when (nil? f)
      (throw (IllegalStateException. "create-tech-ui-container-screen not installed")))
    (f screen-data)))
""",
            )
            # also handle already-fixed closing paren form
            text = text.replace(
                "[cn.li.mcmod.runtime.owner :as runtime-owner]))\n",
                """[cn.li.mcmod.runtime.owner :as runtime-owner]))

(defonce ^:private create-tech-ui-container-screen-atom
  (atom nil))

(defn install-create-tech-ui-container-screen!
  "Install versioned host-container create-tech-ui-container-screen."
  [f]
  (reset! create-tech-ui-container-screen-atom f)
  f)

(defn- create-tech-ui-container-screen
  [screen-data]
  (let [f @create-tech-ui-container-screen-atom]
    (when (nil? f)
      (throw (IllegalStateException. \"create-tech-ui-container-screen not installed\")))
    (f screen-data)))
""",
            )
        text = text.replace(
            "(reactive-host/create-tech-ui-container-screen",
            "(create-tech-ui-container-screen",
        )
        # strip leftover empty require line artifacts
        text = text.replace("            \n            [cn.li.mcmod.gui", "            [cn.li.mcmod.gui")
        path.write_text(text, encoding="utf-8", newline="\n")
        print("screen unlock", path.relative_to(ROOT))

        # install from host_container at end of ns
        host = ROOT / f"platform-src/minecraft/{folder}/src/main/clojure/cn/li/{ns}/gui/reactive/host_container.clj"
        htext = host.read_text(encoding="utf-8", errors="surrogateescape")
        if "install-create-tech-ui-container-screen!" not in htext:
            # require screen.impl for install - careful circular: impl requires install from host
            # host should require screen.impl only for install at bottom
            if f"cn.li.{ns}.gui.screen.impl" not in htext and "cn.li.mcbase.gui.screen.impl" not in htext:
                htext = htext.replace(
                    "(:require ",
                    f"(:require [cn.li.{ns}.gui.screen.impl :as screen-impl]\n            ",
                    1,
                )
            htext = htext.rstrip() + "\n\n(screen-impl/install-create-tech-ui-container-screen! create-tech-ui-container-screen)\n"
            host.write_text(htext, encoding="utf-8", errors="surrogateescape", newline="\n")
            print("host wire", host.relative_to(ROOT))


def unlock_and_promote_multipart() -> None:
    src = read_mc("runtime/multipart_entity.clj", "mc-1.20.1", "mc1201")
    text = src.read_text(encoding="utf-8")
    # replace EnderDragonPart import with reflective resolve
    text = """(ns cn.li.mcbase.runtime.multipart-entity
  \"Shared multipart entity normalization (EnderDragonPart package forks via reflection).\"
  (:import [cn.li.acapi.entity MultipartEntityApi MultipartEntityApi$ParentValidator MultipartEntityPart]
           [net.minecraft.world.entity Entity]))

(def ^:private ender-dragon-part-class
  (delay
    (or (try (Class/forName \"net.minecraft.world.entity.boss.EnderDragonPart\")
             (catch ClassNotFoundException _))
        (try (Class/forName \"net.minecraft.world.entity.boss.enderdragon.EnderDragonPart\")
             (catch ClassNotFoundException _))
        (throw (IllegalStateException. \"EnderDragonPart class not found\")))))

(defn- ender-dragon-part?
  [entity]
  (boolean (and entity (.isInstance ^Class @ender-dragon-part-class entity))))

(defn- ender-dragon-parent-mob
  [entity]
  (try
    (let [f (.getDeclaredField (.getClass entity) \"parentMob\")]
      (.setAccessible f true)
      (.get f entity))
    (catch Exception _
      nil)))

(def ^:private ^MultipartEntityApi$ParentValidator entity-parent-validator
  (reify MultipartEntityApi$ParentValidator
    (isValid [_ candidate]
      (instance? Entity candidate))))

(defn- valid-parent
  [entity candidate]
  (when (and (instance? Entity candidate)
             (not (identical? entity candidate)))
    candidate))

(defn- api-contract-parent
  [entity]
  (when (instance? MultipartEntityPart entity)
    (try
      (valid-parent entity
                    (.getMultipartParent ^MultipartEntityPart entity))
      (catch Exception _
        nil)
      (catch LinkageError _
        nil))))

(defn- registered-parent
  [entity]
  (valid-parent entity
                (MultipartEntityApi/resolveParent
                  entity
                  ^MultipartEntityApi$ParentValidator entity-parent-validator)))

(defn parent
  \"Resolve an immediate multipart parent through vanilla, cross-loader API,
   and registered compatibility contracts, in that order.\"
  [entity]
  (when entity
    (or (when (ender-dragon-part? entity)
          (valid-parent entity (ender-dragon-parent-mob entity)))
        (api-contract-parent entity)
        (registered-parent entity))))

(defn multipart?
  [entity]
  (boolean (and entity (parent entity))))

(defn combat-root
  \"Resolve nested multipart graphs to a stable root.\"
  [entity]
  (loop [^Entity current entity
         depth 0
         seen []]
    (if (or (nil? current)
            (>= depth 8)
            (some #(identical? current %) seen))
      current
      (if-let [^Entity parent-entity (parent current)]
        (recur parent-entity (inc depth) (conj seen current))
        current))))
"""
    write(ROOT / "platform-src/minecraft/base/src/main/clojure/cn/li/mcbase/runtime/multipart_entity.clj", text)
    delete_mc("runtime/multipart_entity.clj")
    rewrite_exact(
        [
            ("cn.li.mc1201.runtime.multipart-entity", "cn.li.mcbase.runtime.multipart-entity"),
            ("cn.li.mc1211.runtime.multipart-entity", "cn.li.mcbase.runtime.multipart-entity"),
            ("cn.li.mc262.runtime.multipart-entity", "cn.li.mcbase.runtime.multipart-entity"),
        ]
    )


def unlock_runtime_adapters() -> None:
    """Install versioned cores into shared adapters."""
    # block-manipulation
    for folder, ns in MC:
        path = read_mc("runtime/adapter/block_manipulation.clj", folder, ns)
        text = path.read_text(encoding="utf-8", errors="replace")
        text = re.sub(
            rf"\[cn\.li\.(mc1201|mc1211|mc262)\.runtime\.block-manipulation-core :as core\]\s*",
            "",
            text,
        )
        if "block-core-atom" not in text:
            text = text.replace(
                "[cn.li.mcmod.framework.platform :as platform]))\n",
                """[cn.li.mcmod.framework.platform :as platform]))

(defonce ^:private block-core-atom (atom nil))

(defn install-block-core!
  \"Install map of core fns: break-block! set-block! get-block get-block-hardness
   block-collidable? can-break-block? requires-high-tier-tool? find-blocks-in-line
   liquid-block? farmland-block?.\"
  [m]
  (reset! block-core-atom m)
  m)

(defn- core []
  (let [m @block-core-atom]
    (when (nil? m)
      (throw (IllegalStateException. \"block-manipulation-core not installed\")))
    m))
""",
            )
        # replace core/foo with ((:foo (core)) ...)
        for fn_name in (
            "break-block!",
            "set-block!",
            "get-block",
            "get-block-hardness",
            "block-collidable?",
            "can-break-block?",
            "requires-high-tier-tool?",
            "find-blocks-in-line",
            "liquid-block?",
            "farmland-block?",
        ):
            text = text.replace(f"(core/{fn_name}", f"((:{fn_name} (core))")
        path.write_text(text, encoding="utf-8", newline="\n")
        print("block adapter unlock", path.relative_to(ROOT))

        # wire install at end of core ns
        corep = read_mc("runtime/block_manipulation_core.clj", folder, ns)
        ctext = corep.read_text(encoding="utf-8", errors="surrogateescape")
        if "install-block-core!" not in ctext:
            ctext = ctext.replace(
                "(:require ",
                f"(:require [cn.li.{ns}.runtime.adapter.block-manipulation :as block-adapter]\n            ",
                1,
            )
            # append install map - use vars from this ns
            ctext = (
                ctext.rstrip()
                + """

(block-adapter/install-block-core!
  {:break-block! break-block!
   :set-block! set-block!
   :get-block get-block
   :get-block-hardness get-block-hardness
   :block-collidable? block-collidable?
   :can-break-block? can-break-block?
   :requires-high-tier-tool? requires-high-tier-tool?
   :find-blocks-in-line find-blocks-in-line
   :liquid-block? liquid-block?
   :farmland-block? farmland-block?})
"""
            )
            corep.write_text(ctext, encoding="utf-8", errors="surrogateescape", newline="\n")
            print("block core wire", corep.relative_to(ROOT))

    # world-effects adapter — only swap multipart (already mcbase) + install core
    for folder, ns in MC:
        path = read_mc("runtime/adapter/world_effects.clj", folder, ns)
        text = path.read_text(encoding="utf-8", errors="replace")
        text = text.replace(
            f"[cn.li.{ns}.runtime.multipart-entity :as multipart]",
            "[cn.li.mcbase.runtime.multipart-entity :as multipart]",
        )
        text = re.sub(
            rf"\[cn\.li\.(mc1201|mc1211|mc262)\.runtime\.world-effects-core :as core\]\s*",
            "",
            text,
        )
        if "world-core-atom" not in text:
            text = text.replace(
                "[cn.li.mcmod.util.log :as log])\n",
                """[cn.li.mcmod.util.log :as log])

(defonce ^:private world-core-atom (atom nil))

(defn install-world-core!
  [m]
  (reset! world-core-atom m)
  m)

(defn- core []
  (let [m @world-core-atom]
    (when (nil? m)
      (throw (IllegalStateException. \"world-effects-core not installed\")))
    m))
""",
            )
        for fn_name in (
            "spawn-projectile-in-level!",
            "entities-in-radius",
            "entities-in-aabb",
            "find-blocks-in-radius-in-level",
            "play-sound-in-level!",
            "trigger-behavior-hit-in-level!",
        ):
            text = text.replace(f"(core/{fn_name}", f"((:{fn_name} (core))")
        path.write_text(text, encoding="utf-8", newline="\n")
        print("world adapter unlock", path.relative_to(ROOT))

        corep = read_mc("runtime/world_effects_core.clj", folder, ns)
        ctext = corep.read_text(encoding="utf-8", errors="surrogateescape")
        if "install-world-core!" not in ctext:
            ctext = ctext.replace(
                "(:require ",
                f"(:require [cn.li.{ns}.runtime.adapter.world-effects :as world-adapter]\n            ",
                1,
            )
            # if no :require (java-only imports), inject after ns
            if f"cn.li.{ns}.runtime.adapter.world-effects" not in ctext:
                ctext = re.sub(
                    r"(\(ns [^\)]+\))",
                    rf"\1\n(:require [cn.li.{ns}.runtime.adapter.world-effects :as world-adapter])",
                    ctext,
                    count=1,
                )
            ctext = (
                ctext.rstrip()
                + """

(world-adapter/install-world-core!
  {:spawn-projectile-in-level! spawn-projectile-in-level!
   :entities-in-radius entities-in-radius
   :entities-in-aabb entities-in-aabb
   :find-blocks-in-radius-in-level find-blocks-in-radius-in-level
   :play-sound-in-level! play-sound-in-level!
   :trigger-behavior-hit-in-level! trigger-behavior-hit-in-level!})
"""
            )
            corep.write_text(ctext, encoding="utf-8", errors="surrogateescape", newline="\n")
            print("world core wire", corep.relative_to(ROOT))

    # item-use
    for folder, ns in MC:
        path = read_mc("runtime/event/item_use.clj", folder, ns)
        text = path.read_text(encoding="utf-8", errors="replace")
        text = re.sub(
            rf"\[cn\.li\.(mc1201|mc1211|mc262)\.runtime\.item-handler-core :as core\]\s*",
            "",
            text,
        )
        if "item-core-atom" not in text:
            text = text.replace(
                "[cn.li.mcbase.runtime.event.safe-handler :as safe]\n",
                """[cn.li.mcbase.runtime.event.safe-handler :as safe]
""",
            )
            text = text.replace(
                "(:import [net.minecraft.world InteractionHand]",
                """(defonce ^:private item-core-atom (atom nil))

(defn install-item-core!
  [m]
  (reset! item-core-atom m)
  m)

(defn- core []
  (let [m @item-core-atom]
    (when (nil? m)
      (throw (IllegalStateException. \"item-handler-core not installed\")))
    m))

  (:import [net.minecraft.world InteractionHand]""",
            )
            # fix broken ns form - the above might put defonce inside ns. Fix properly.
        path.write_text(text, encoding="utf-8", newline="\n")

    # Fix item_use properly by rewriting whole file from template
    for folder, ns in MC:
        write(
            ROOT / f"platform-src/minecraft/{folder}/src/main/clojure/cn/li/{ns}/runtime/event/item_use.clj",
            f"""(ns cn.li.{ns}.runtime.event.item-use
  \"Shared item-use event semantics for loader platform adapters.

  Platform layers should only unpack their event/callback objects and translate
  the returned map into loader-specific results.\"
  (:require [cn.li.mcbase.runtime.event.safe-handler :as safe])
  (:import [net.minecraft.world InteractionHand]
           [net.minecraft.world.entity.player Player]
           [net.minecraft.world.item ItemStack]))

(defonce ^:private item-core-atom (atom nil))

(defn install-item-core!
  [m]
  (reset! item-core-atom m)
  m)

(defn- core []
  (let [m @item-core-atom]
    (when (nil? m)
      (throw (IllegalStateException. \"item-handler-core not installed\")))
    m))

(defn- ignored-result
  [^Player player]
  {{:consume? false
   :item-id nil
   :player-uuid (some-> player .getUUID str)
   :plan nil}})

(defn handle-finish-using!
  [entity ^ItemStack stack side label]
  (safe/invoke
   (str label \" item finish-using event\")
   nil
   (fn []
     (when (instance? Player entity)
       ((:dispatch-dsl-item-finish-using! (core)) ^Player entity stack side)))))

(defn handle-use
  \"Returns the shared item-use result map, or a default non-consuming result
  when the event should be ignored or a handler fails.\"
  [^Player player hand ^ItemStack stack side opts label]
  (safe/invoke
   (str label \" item use event\")
   (ignored-result player)
   (fn []
     (if (= hand InteractionHand/MAIN_HAND)
       ((:process-item-use! (core)) player hand stack side opts)
       (ignored-result player)))))
""",
        )
        corep = read_mc("runtime/item_handler_core.clj", folder, ns)
        ctext = corep.read_text(encoding="utf-8", errors="surrogateescape")
        if "install-item-core!" not in ctext:
            if "(:require " in ctext:
                ctext = ctext.replace(
                    "(:require ",
                    f"(:require [cn.li.{ns}.runtime.event.item-use :as item-use]\n            ",
                    1,
                )
            else:
                ctext = re.sub(
                    r"(\(ns [^\)]+\))",
                    rf"\1\n(:require [cn.li.{ns}.runtime.event.item-use :as item-use])",
                    ctext,
                    count=1,
                )
            ctext = (
                ctext.rstrip()
                + """

(item-use/install-item-core!
  {:dispatch-dsl-item-finish-using! dispatch-dsl-item-finish-using!
   :process-item-use! process-item-use!})
"""
            )
            corep.write_text(ctext, encoding="utf-8", errors="surrogateescape", newline="\n")
            print("item core wire", corep.relative_to(ROOT))

    # raycast_core — install Raycast ops
    for folder, ns in MC:
        path = read_mc("runtime/raycast_core.clj", folder, ns)
        text = path.read_text(encoding="utf-8", errors="replace")
        text = re.sub(
            rf"\[cn\.li\.(mc1201|mc1211|mc262)\.runtime Raycast\]\s*",
            "",
            text,
        )
        if "raycast-ops-atom" not in text:
            text = text.replace(
                "[cn.li.mcmod.util.log :as log])\n",
                """[cn.li.mcmod.util.log :as log])

(defonce ^:private raycast-ops-atom (atom nil))

(defn install-raycast-ops!
  \"Install map of Raycast static method wrappers.\"
  [m]
  (reset! raycast-ops-atom m)
  m)

(defn- raycast-ops []
  (let [m @raycast-ops-atom]
    (when (nil? m)
      (throw (IllegalStateException. \"Raycast ops not installed\")))
    m))
""",
            )
        for method in (
            "raycastBlocks",
            "raycastBlocksMatching",
            "raycastCollidableBlocksOrWater",
            "raycastEntities",
            "raycastCombined",
            "raycastCombinedExcluding",
            "raycastCombinedAll",
            "raycastCombinedAllExcluding",
        ):
            # Raycast/method -> ((:method (raycast-ops))
            text = text.replace(f"(Raycast/{method}", f"((:{method} (raycast-ops))")
        path.write_text(text, encoding="utf-8", newline="\n")
        print("raycast unlock", path.relative_to(ROOT))

        # write thin install ns that wraps Java Raycast
        write(
            ROOT / f"platform-src/minecraft/{folder}/src/main/clojure/cn/li/{ns}/runtime/raycast_ops_install.clj",
            f"""(ns cn.li.{ns}.runtime.raycast-ops-install
  \"Install versioned Raycast Java helpers into shared raycast-core.\"
  (:require [cn.li.mcbase.runtime.raycast-core :as raycast-core])
  (:import [cn.li.{ns}.runtime Raycast]))

(defn install!
  []
  (raycast-core/install-raycast-ops!
    {{:raycastBlocks (fn [level sx sy sz dx dy dz maxd]
                     (Raycast/raycastBlocks level sx sy sz dx dy dz maxd))
     :raycastBlocksMatching (fn [level sx sy sz dx dy dz maxd ids]
                              (Raycast/raycastBlocksMatching level sx sy sz dx dy dz maxd ids))
     :raycastCollidableBlocksOrWater (fn [level sx sy sz dx dy dz maxd]
                                       (Raycast/raycastCollidableBlocksOrWater level sx sy sz dx dy dz maxd))
     :raycastEntities (fn [level sx sy sz dx dy dz maxd]
                        (Raycast/raycastEntities level sx sy sz dx dy dz maxd))
     :raycastCombined (fn [level sx sy sz dx dy dz maxd]
                        (Raycast/raycastCombined level sx sy sz dx dy dz maxd))
     :raycastCombinedExcluding (fn [level sx sy sz dx dy dz maxd uuid]
                                 (Raycast/raycastCombinedExcluding level sx sy sz dx dy dz maxd uuid))
     :raycastCombinedAll (fn [level sx sy sz dx dy dz maxd]
                           (Raycast/raycastCombinedAll level sx sy sz dx dy dz maxd))
     :raycastCombinedAllExcluding (fn [level sx sy sz dx dy dz maxd uuid]
                                    (Raycast/raycastCombinedAllExcluding level sx sy sz dx dy dz maxd uuid))}})
  nil)

(install!)
""",
        )


def unlock_commands() -> None:
    for folder, ns in MC:
        path = read_mc("command/action_impls.clj", folder, ns)
        text = path.read_text(encoding="utf-8", errors="replace")
        text = re.sub(
            rf"\[cn\.li\.(mc1201|mc1211|mc262)\.command\.executor-core :as executor\]\s*",
            "",
            text,
        )
        if "executor-atom" not in text:
            text = text.replace(
                "[cn.li.mcbase.command.feedback :as feedback]\n",
                """[cn.li.mcbase.command.feedback :as feedback]
""",
            )
            text = text.replace(
                "[cn.li.mcmod.command.actions :as cmd-actions])\n",
                """[cn.li.mcmod.command.actions :as cmd-actions])

(defonce ^:private executor-atom (atom nil))

(defn install-executor!
  [m]
  (reset! executor-atom m)
  m)

(defn- executor []
  (let [m @executor-atom]
    (when (nil? m)
      (throw (IllegalStateException. \"command executor-core not installed\")))
    m))
""",
            )
        text = text.replace(
            "(executor/execute-send-message-action",
            "((:execute-send-message-action (executor))",
        )
        text = text.replace(
            "(executor/execute-grant-advancement-action",
            "((:execute-grant-advancement-action (executor))",
        )
        path.write_text(text, encoding="utf-8", newline="\n")
        print("action unlock", path.relative_to(ROOT))

        corep = read_mc("command/executor_core.clj", folder, ns)
        ctext = corep.read_text(encoding="utf-8", errors="surrogateescape")
        if "install-executor!" not in ctext:
            ctext = ctext.replace(
                "(:require [cn.li.mcmod.util.log :as log])",
                f"(:require [cn.li.mcmod.util.log :as log]\n            [cn.li.{ns}.command.action-impls :as action-impls])",
            )
            ctext = (
                ctext.rstrip()
                + """

(action-impls/install-executor!
  {:execute-send-message-action execute-send-message-action
   :execute-grant-advancement-action execute-grant-advancement-action})
"""
            )
            corep.write_text(ctext, encoding="utf-8", errors="surrogateescape", newline="\n")

        # brigadier-registry
        path = read_mc("command/brigadier_registry.clj", folder, ns)
        text = path.read_text(encoding="utf-8", errors="replace")
        text = re.sub(
            rf"\[cn\.li\.(mc1201|mc1211|mc262)\.command\.brigadier-tree :as brig-tree\]\s*",
            "",
            text,
        )
        if "brig-tree-atom" not in text:
            text = text.replace(
                "[cn.li.mcmod.command.runtime-hooks :as command-hooks]\n",
                """[cn.li.mcmod.command.runtime-hooks :as command-hooks]
""",
            )
            text = text.replace(
                f"[cn.li.{ns}.command.action-impls] ; Ensure action implementations are loaded\n",
                f"[cn.li.{ns}.command.action-impls] ; Ensure action implementations are loaded\n",
            )
            text = text.replace(
                "[cn.li.mcmod.util.log :as log])\n",
                """[cn.li.mcmod.util.log :as log])

(defonce ^:private brig-tree-atom (atom nil))

(defn install-build-command-node!
  [f]
  (reset! brig-tree-atom f)
  f)

(defn- build-command-node
  [spec]
  (let [f @brig-tree-atom]
    (when (nil? f)
      (throw (IllegalStateException. \"brigadier-tree not installed\")))
    (f spec)))
""",
            )
        text = text.replace("(brig-tree/build-command-node spec)", "(build-command-node spec)")
        path.write_text(text, encoding="utf-8", newline="\n")

        treep = read_mc("command/brigadier_tree.clj", folder, ns)
        ttext = treep.read_text(encoding="utf-8", errors="surrogateescape")
        if "install-build-command-node!" not in ttext:
            ttext = ttext.replace(
                "(:require ",
                f"(:require [cn.li.{ns}.command.brigadier-registry :as brig-reg]\n            ",
                1,
            )
            ttext = ttext.rstrip() + "\n\n(brig-reg/install-build-command-node! build-command-node)\n"
            treep.write_text(ttext, encoding="utf-8", errors="surrogateescape", newline="\n")


def promote_neo_registry_binding() -> None:
    write(
        ROOT
        / "platform-src/loader/neoforge-shared/src/main/clojure/cn/li/neoforgebase/setup/registry_binding.clj",
        """(ns cn.li.neoforgebase.setup.registry-binding
  "Shared NeoForge registry/config phase. Version loaders install Mod* bridges."
  (:require [cn.li.neoforgebase.config.bridge :as config-bridge]
            [cn.li.neoforgebase.integration.side :as side]
            [cn.li.neoforgebase.setup.deferred-registries :as deferred-registries])
  (:import [net.neoforged.bus.api IEventBus]))

(defonce ^:private registry-bridge-atom (atom nil))

(defn install-registry-bridge!
  "Install {:register-entities! :register-recipes! :register-triggers!
            :register-features! :register-client-hooks!}."
  [bridge]
  (reset! registry-bridge-atom bridge)
  bridge)

(defn- bridge! []
  (let [b @registry-bridge-atom]
    (when (nil? b)
      (throw (IllegalStateException. "registry-binding bridge not installed")))
    b))

(defn register-config-phase!
  ([^IEventBus mod-bus _opts]
   (register-config-phase! mod-bus nil _opts))
  ([^IEventBus mod-bus mod-container _opts]
   (config-bridge/register-all! mod-bus mod-container)
   (config-bridge/install-config-persist-op!)
   nil))

(defn register-registry-phase!
  [^IEventBus mod-bus {:keys [datagen-run?
                             sounds-register
                             effects-register
                             particle-types-register
                             fluid-types-register
                             fluids-register
                             blocks-register
                             items-register
                             block-entities-register
                             creative-tabs-register
                             gui-menu-register]}]
  (let [b (bridge!)]
    ((:register-entities! b) mod-bus)
    ((:register-recipes! b) mod-bus)
    ((:register-triggers! b) mod-bus)
    (when (and (side/client-side?) (not datagen-run?))
      ((:register-client-hooks! b)))
    ((:register-features! b) mod-bus)
    (deferred-registries/register-deferred-registries! mod-bus [sounds-register
                                                              effects-register
                                                              particle-types-register
                                                              fluid-types-register
                                                              fluids-register
                                                              blocks-register
                                                              items-register
                                                              block-entities-register
                                                              creative-tabs-register
                                                              gui-menu-register]))
  nil)
""",
    )
    for folder, ns in NEO:
        p = ROOT / f"platform-src/loader/{folder}/src/main/clojure/cn/li/{ns}/setup/registry_binding.clj"
        if p.exists():
            p.unlink()
            print("delete", p.relative_to(ROOT))
        # expand shared_event_install
        sei = ROOT / f"platform-src/loader/{folder}/src/main/clojure/cn/li/{ns}/setup/shared_event_install.clj"
        text = sei.read_text(encoding="utf-8")
        if "registry-binding" not in text:
            text = text.replace(
                "(:require ",
                "(:require [cn.li.neoforgebase.setup.registry-binding :as registry-binding]\n            ",
                1,
            )
            # add imports for Mod* if missing
            for cls in ("ModRecipeTypes", "ModCriterionTriggers", "ModFeatures"):
                if cls not in text:
                    text = text.replace(
                        f"[cn.li.{ns}.entity ModEntities]",
                        f"[cn.li.{ns}.entity ModEntities]\n           [cn.li.{ns}.recipe ModRecipeTypes]\n           [cn.li.{ns}.trigger ModCriterionTriggers]\n           [cn.li.{ns}.worldgen ModFeatures]",
                    )
            if "lifecycle-listeners" not in text:
                text = text.replace(
                    "(:require ",
                    f"(:require [cn.li.{ns}.setup.lifecycle-listeners :as lifecycle-listeners]\n            ",
                    1,
                )
            install_extra = """
  (registry-binding/install-registry-bridge!
    {:register-entities! (fn [mod-bus] (ModEntities/register mod-bus))
     :register-recipes! (fn [mod-bus] (ModRecipeTypes/register mod-bus))
     :register-triggers! (fn [mod-bus] (ModCriterionTriggers/register mod-bus))
     :register-features! (fn [mod-bus] (ModFeatures/register mod-bus))
     :register-client-hooks! (fn [] (lifecycle-listeners/register-client-hooks!))})
"""
            text = text.replace(
                "(capability-setup/install-register-network!",
                install_extra + "  (capability-setup/install-register-network!",
            )
            # if capability-setup block missing, append before final nil
            if "install-registry-bridge!" not in text:
                text = text.replace(
                    "  nil)\n",
                    install_extra + "  nil)\n",
                    1,
                )
            sei.write_text(text, encoding="utf-8", newline="\n")
            print("sei expand", sei.relative_to(ROOT))
        rewrite_exact(
            [
                (f"cn.li.{ns}.setup.registry-binding", "cn.li.neoforgebase.setup.registry-binding"),
            ],
            [ROOT / f"platform-src/loader/{folder}"],
        )


def wire_raycast_install_into_adapters() -> None:
    """Ensure loader adapter registries require raycast-ops-install."""
    for folder, ns, loader_ns in (
        ("forge-1.20.1", "mc1201", "forge1201"),
        ("fabric-1.20.1", "mc1201", "fabric1201"),
        ("neoforge-1.21.1", "mc1211", "neoforge1211"),
        ("neoforge-26.2", "mc262", "neoforge262"),
    ):
        # also ensure minecraft side platform_init or similar loads it
        # Prefer requiring from raycast-core consumers — adapters registry
        for rel in (
            f"platform-src/loader/{folder}/src/main/clojure/cn/li/{loader_ns}/runtime/adapters/registry.clj",
            f"platform-src/loader/{folder}/src/main/clojure/cn/li/{loader_ns}/runtime/adapters.clj",
        ):
            path = ROOT / rel
            if not path.exists():
                continue
            text = path.read_text(encoding="utf-8", errors="surrogateescape")
            if "raycast-ops-install" in text:
                continue
            text = text.replace(
                "(:require ",
                f"(:require [cn.li.{ns}.runtime.raycast-ops-install]\n            ",
                1,
            )
            path.write_text(text, encoding="utf-8", errors="surrogateescape", newline="\n")
            print("raycast load", path.relative_to(ROOT))


def fix_host_screen_circular() -> None:
    """After promote, host_container must require mcbase.gui.screen.impl."""
    for folder, ns in MC:
        host = ROOT / f"platform-src/minecraft/{folder}/src/main/clojure/cn/li/{ns}/gui/reactive/host_container.clj"
        if not host.exists():
            continue
        text = host.read_text(encoding="utf-8", errors="surrogateescape")
        text = text.replace(
            f"[cn.li.{ns}.gui.screen.impl :as screen-impl]",
            "[cn.li.mcbase.gui.screen.impl :as screen-impl]",
        )
        host.write_text(text, encoding="utf-8", errors="surrogateescape", newline="\n")


def fix_core_wires_after_promote() -> None:
    """Versioned cores that required versioned adapters must point to mcbase."""
    for folder, ns in MC:
        for rel, old, new in (
            (
                "runtime/block_manipulation_core.clj",
                f"cn.li.{ns}.runtime.adapter.block-manipulation",
                "cn.li.mcbase.runtime.adapter.block-manipulation",
            ),
            (
                "runtime/world_effects_core.clj",
                f"cn.li.{ns}.runtime.adapter.world-effects",
                "cn.li.mcbase.runtime.adapter.world-effects",
            ),
            (
                "runtime/item_handler_core.clj",
                f"cn.li.{ns}.runtime.event.item-use",
                "cn.li.mcbase.runtime.event.item-use",
            ),
            (
                "command/executor_core.clj",
                f"cn.li.{ns}.command.action-impls",
                "cn.li.mcbase.command.action-impls",
            ),
            (
                "command/brigadier_tree.clj",
                f"cn.li.{ns}.command.brigadier-registry",
                "cn.li.mcbase.command.brigadier-registry",
            ),
        ):
            path = ROOT / f"platform-src/minecraft/{folder}/src/main/clojure/cn/li/{ns}/{rel}"
            if not path.exists():
                continue
            text = path.read_text(encoding="utf-8", errors="surrogateescape")
            if old in text:
                text = text.replace(old, new)
                path.write_text(text, encoding="utf-8", errors="surrogateescape", newline="\n")
                print("retarget", path.relative_to(ROOT))


def main() -> None:
    unlock_and_promote_multipart()
    unlock_screen()
    unify_from_1201("gui/screen/impl.clj")
    unlock_runtime_adapters()
    unlock_commands()

    # Promote unlocked identical files
    promote_clj("gui/screen/impl.clj", "gui.screen.impl")
    promote_clj("gui/screen/registry.clj", "gui.screen.registry")
    promote_clj("runtime/adapter/block_manipulation.clj", "runtime.adapter.block-manipulation")
    promote_clj("runtime/adapter/world_effects.clj", "runtime.adapter.world-effects")
    promote_clj("runtime/event/item_use.clj", "runtime.event.item-use")
    promote_clj("runtime/raycast_core.clj", "runtime.raycast-core")
    promote_clj("command/action_impls.clj", "command.action-impls")
    promote_clj("command/brigadier_registry.clj", "command.brigadier-registry")

    fix_host_screen_circular()
    fix_core_wires_after_promote()
    wire_raycast_install_into_adapters()

    # Fix raycast_ops_install to require mcbase raycast-core (already does in template after promote)
    for folder, ns in MC:
        p = ROOT / f"platform-src/minecraft/{folder}/src/main/clojure/cn/li/{ns}/runtime/raycast_ops_install.clj"
        if p.exists():
            t = p.read_text(encoding="utf-8")
            t = t.replace(f"cn.li.{ns}.runtime.raycast-core", "cn.li.mcbase.runtime.raycast-core")
            # ensure require path is mcbase
            if "cn.li.mcbase.runtime.raycast-core" not in t:
                t = t.replace(
                    f"(ns cn.li.{ns}.runtime.raycast-ops-install",
                    f"(ns cn.li.{ns}.runtime.raycast-ops-install",
                )
            p.write_text(t, encoding="utf-8", newline="\n")

    promote_neo_registry_binding()
    print("BATCH5 DONE")


if __name__ == "__main__":
    main()
