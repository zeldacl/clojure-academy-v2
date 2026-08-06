#!/usr/bin/env python3
"""Batch6: promote platform_init, runtime_ops, and NeoForge setup/common via SPI."""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def write(rel: str, text: str) -> None:
    p = ROOT / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8", newline="\n")
    print("wrote", rel)


def delete(rel: str) -> None:
    p = ROOT / rel
    if p.exists():
        p.unlink()
        print("deleted", rel)


# --- platform_init ---
PLATFORM_INIT = '''(ns cn.li.mcbase.bootstrap.platform-init
  "Shared platform bootstrap install wrappers.

  Version modules install installer-core / accessor-registry hooks, then call
  their menu-bridge-install. Loaders call the shared install entrypoints.")

(defonce ^:private hooks-atom (atom nil))

(defn install-platform-init-hooks!
  "Install map with :install-platform-core! :install-platform-services!
   :init-default-accessors!."
  [m]
  (reset! hooks-atom m)
  m)

(defn- hooks []
  (let [m @hooks-atom]
    (when (nil? m)
      (throw (IllegalStateException. "platform-init hooks not installed")))
    m))

(defn install-platform-core!
  "Install the full shared platform core for adapters that can provide
  all required world/block/entity/item operations through PlatformAdapter."
  [adapter]
  ((:install-platform-core! (hooks)) adapter)
  ((:init-default-accessors! (hooks))))

(defn install-platform-services!
  [adapter world-fns-map be-fns-map]
  ((:install-platform-services! (hooks)) adapter world-fns-map be-fns-map)
  ((:init-default-accessors! (hooks))))
'''

PLATFORM_INIT_INSTALL = '''(ns cn.li.%(ns)s.bootstrap.platform-init
  "Install versioned installer/accessor hooks into shared platform-init."
  (:require [cn.li.mcbase.bootstrap.platform-init :as shared]
            [cn.li.%(ns)s.gui.menu-bridge-install :as menu-bridge-install]
            [cn.li.%(ns)s.bootstrap.installer-core :as core]
            [cn.li.%(ns)s.runtime.accessor-registry :as accessor-registry]))

(shared/install-platform-init-hooks!
  {:install-platform-core! core/install-platform-core!
   :install-platform-services! core/install-platform-services!
   :init-default-accessors! accessor-registry/init-default-accessors!})

(menu-bridge-install/install!)

(def install-platform-core! shared/install-platform-core!)
(def install-platform-services! shared/install-platform-services!)
'''

# --- runtime_ops ---
RUNTIME_OPS = '''(ns cn.li.mcbase.platform.runtime-ops
  "Minecraft-touching runtime callables shared by Forge/Fabric adapter maps.

  Consumed by cn.li.platform.adapter.minecraft-ops/build-adapter-map.
  Version modules install RuntimeAccess/BlockRegistry/ItemRegistry/ParticleEntity
  wrappers via install-runtime-ops!."
  (:require [cn.li.mcbase.runtime ItemPlayerOps])
  (:import [net.minecraft.core BlockPos]
           [net.minecraft.world.entity.item ItemEntity]
           [net.minecraft.world.entity.player Player]
           [net.minecraft.world.item ItemStack]
           [net.minecraft.world.level Level]
           [net.minecraft.world.level.block.state BlockState]
           [net.minecraft.world.phys BlockHitResult]))

(defonce ^:private ops-atom (atom nil))

(defn install-runtime-ops!
  "Install map of versioned Java helper wrappers."
  [m]
  (reset! ops-atom m)
  m)

(defn- ops []
  (let [m @ops-atom]
    (when (nil? m)
      (throw (IllegalStateException. "runtime-ops not installed")))
    m))

(defn- raytrace-block
  [player reach fluid-source-only?]
  (when-let [^BlockHitResult hit ((:playerRaytraceBlock (ops))
                                  player
                                  (double (or reach 5.0))
                                  (boolean fluid-source-only?))]
    (let [^BlockPos hit-pos (.getBlockPos hit)
          ^BlockPos place-pos (.relative hit-pos (.getDirection hit))
          ^Level level ((:getEntityLevel (ops)) player)
          ^BlockState hit-state (.getBlockState level hit-pos)]
      {:hit-pos {:x (.getX hit-pos) :y (.getY hit-pos) :z (.getZ hit-pos)}
       :place-pos {:x (.getX place-pos) :y (.getY place-pos) :z (.getZ place-pos)}
       :block-id ((:getBlockKey (ops)) (.getBlock hit-state))})))

(defn- drop-player-main-hand-item-at!
  [player amount x y z]
  (let [n (int (max 0 (or amount 0)))]
    (cond
      (nil? player) false
      (zero? n) true
      (boolean (.isCreative ^Player player)) true
      :else
      (let [^ItemStack stack (.getMainHandItem ^Player player)]
        (if (or (nil? stack)
                (.isEmpty stack)
                (< (int (.getCount stack)) n))
          false
          (let [^ItemStack drop-stack (.copy stack)
                level ^Level ((:getEntityLevel (ops)) player)]
            (.setCount drop-stack n)
            (.shrink stack n)
            (if (or (nil? level) (.isClientSide level))
              true
              (boolean (.addFreshEntity level
                                        (ItemEntity. level (double x) (double y) (double z) drop-stack))))))))))

(defn standard-runtime-ops
  "Return the shared runtime callable map (without loader-specific slots)."
  []
  {:entity-class #((:getEntityClass (ops)))
   :player-class #((:getPlayerClass (ops)))
   :server-player-class #((:getServerPlayerClass (ops)))
   :inventory-class #((:getInventoryClass (ops)))
   :menu-class #((:getAbstractContainerMenuClass (ops)))
   :item-stack-class #((:getItemStackClass (ops)))
   :item-class #((:getItemClass (ops)))
   :block-state-class #((:getBlockStateClass (ops)))
   :level-class #((:getLevelClass (ops)))
   :item-registry-name (fn [item] ((:getItemKeyString (ops)) item))
   :block-registry-name (fn [block] ((:getBlockKey (ops)) block))
   :item-stack-of (fn [nbt] ((:itemStackOf (ops)) nbt))
   :create-item-stack-by-id (fn [item-id count]
                              ((:createItemStackById (ops)) (str item-id) (int count)))
   :item-stack-empty? (fn [stack] ((:isItemStackEmpty (ops)) stack))
   :player-level (fn [player] ((:getEntityLevel (ops)) player))
   :player-container-menu (fn [player] ((:getPlayerContainerMenu (ops)) player))
   :count-player-item-by-id (fn [player item-id]
                              (ItemPlayerOps/countPlayerItemById player (str item-id)))
   :consume-player-item-by-id! (fn [player item-id amount]
                                 (ItemPlayerOps/consumePlayerItemById
                                  player (str item-id) (int (or amount 0))))
   :drop-player-main-hand-item-at! drop-player-main-hand-item-at!
   :give-player-item-stack! (fn [player stack]
                              (ItemPlayerOps/givePlayerItemStack player stack))
   :spawn-entity-by-id! (fn [player entity-id speed]
                          ((:spawnEntityByIdFromPlayer (ops))
                           player (str entity-id) (float (or speed 1.0))))
   :spawn-tracked-entity-by-id! (fn [player entity-id speed life]
                                  ((:spawnTrackedEntityByIdFromPlayer (ops))
                                   player
                                   (str entity-id)
                                   (float (or speed 0.0))
                                   (when life (int life))))
   :raytrace-block raytrace-block
   :inventory-owner (fn [inventory] ((:getInventoryPlayer (ops)) inventory))
   :menu-container-id (fn [menu] ((:getMenuContainerId (ops)) menu))})
'''

# Fix require - ItemPlayerOps is Java, use :import not :require
RUNTIME_OPS = RUNTIME_OPS.replace(
    "  (:require [cn.li.mcbase.runtime ItemPlayerOps])\n  (:import [net.minecraft.core BlockPos]",
    "  (:import [cn.li.mcbase.runtime ItemPlayerOps]\n           [net.minecraft.core BlockPos]",
)

RUNTIME_OPS_INSTALL = '''(ns cn.li.%(ns)s.platform.runtime-ops
  "Install versioned RuntimeAccess helpers into shared runtime-ops."
  (:require [cn.li.mcbase.platform.runtime-ops :as shared])
  (:import [cn.li.%(ns)s.runtime BlockRegistry ItemInventory
            ItemRegistry ParticleEntity RuntimeAccess]))

(shared/install-runtime-ops!
  {:playerRaytraceBlock (fn [& args] (apply RuntimeAccess/playerRaytraceBlock args))
   :getEntityLevel (fn [& args] (apply RuntimeAccess/getEntityLevel args))
   :getBlockKey (fn [& args] (apply BlockRegistry/getBlockKey args))
   :getEntityClass (fn [] (RuntimeAccess/getEntityClass))
   :getPlayerClass (fn [] (RuntimeAccess/getPlayerClass))
   :getServerPlayerClass (fn [] (RuntimeAccess/getServerPlayerClass))
   :getInventoryClass (fn [] (RuntimeAccess/getInventoryClass))
   :getAbstractContainerMenuClass (fn [] (RuntimeAccess/getAbstractContainerMenuClass))
   :getItemStackClass (fn [] (RuntimeAccess/getItemStackClass))
   :getItemClass (fn [] (RuntimeAccess/getItemClass))
   :getBlockStateClass (fn [] (RuntimeAccess/getBlockStateClass))
   :getLevelClass (fn [] (RuntimeAccess/getLevelClass))
   :getItemKeyString (fn [& args] (apply ItemInventory/getItemKeyString args))
   :itemStackOf (fn [& args] (apply RuntimeAccess/itemStackOf args))
   :createItemStackById (fn [& args] (apply ItemRegistry/createItemStackById args))
   :isItemStackEmpty (fn [& args] (apply ItemInventory/isItemStackEmpty args))
   :getPlayerContainerMenu (fn [& args] (apply RuntimeAccess/getPlayerContainerMenu args))
   :spawnEntityByIdFromPlayer (fn [& args] (apply ParticleEntity/spawnEntityByIdFromPlayer args))
   :spawnTrackedEntityByIdFromPlayer (fn [& args] (apply ParticleEntity/spawnTrackedEntityByIdFromPlayer args))
   :getInventoryPlayer (fn [& args] (apply RuntimeAccess/getInventoryPlayer args))
   :getMenuContainerId (fn [& args] (apply RuntimeAccess/getMenuContainerId args))})

(def standard-runtime-ops shared/standard-runtime-ops)
'''

# --- neo common ---
NEO_COMMON = '''(ns cn.li.neoforgebase.setup.common
  "NeoForge common-setup wiring shared by 1.21.1 and 26.2.

  Version loaders install step fns via install-common-setup-steps! and call
  shared-event-install before exposing run-common-setup!."
  (:require [cn.li.mcmod.util.log :as log])
  (:import [cn.li.neoforgebase.bootstrap ForgeBootstrapGuard]))

(defonce ^:private steps-atom (atom nil))

(defn install-common-setup-steps!
  "Install ordered common-setup step fns (no-arg)."
  [m]
  (reset! steps-atom m)
  m)

(defn- steps []
  (let [m @steps-atom]
    (when (nil? m)
      (throw (IllegalStateException. "common-setup steps not installed")))
    m))

(defn run-common-setup!
  []
  (if-not (ForgeBootstrapGuard/markCommonSetupCompleteIfAbsent)
    (log/info "Forge common setup wiring already complete; skipping duplicate invocation")
    (let [s (steps)]
      ((:assert-scripted-blocks-bundled! s))
      ((:init-common-gui! s))
      ((:init-common-lifecycle! s))
      ((:init-forge-energy! s))
      ((:init-ic2-energy! s))
      ((:init-item-handler! s))
      ((:init-tutorial-events! s))
      ((:init-imc! s))
      ((:register-world-state-changed! s))
      ((:register-common-event-listeners! s))
      (log/info "Forge common setup wiring complete"))))
'''

NEO_COMMON_INSTALL = '''(ns cn.li.%(ns)s.setup.common
  "Install versioned common-setup steps into neoforgebase.setup.common."
  (:require [cn.li.neoforgebase.setup.common :as shared]
            [cn.li.%(ns)s.setup.shared-event-install :as shared-event-install]
            [cn.li.%(ns)s.gui.init :as gui-init]
            [cn.li.%(ns)s.registry.content-registration :as content-registration]
            [cn.li.%(ns)s.runtime.lifecycle :as runtime-lifecycle]
            [cn.li.%(ns)s.integration.forge-energy :as forge-energy]
            [cn.li.neoforgebase.integration.ic2-energy :as ic2-energy]
            [cn.li.%(ns)s.runtime.item-handler :as runtime-item-handler]
            [cn.li.%(ns)s.integration.tutorial-events :as tutorial-events]
            [cn.li.neoforgebase.integration.imc-dispatch :as imc-dispatch]
            [cn.li.%(ns)s.integration.events.world :as world-events]
            [cn.li.%(ns)s.setup.event-registration :as event-registration]))

(shared/install-common-setup-steps!
  {:assert-scripted-blocks-bundled! content-registration/assert-scripted-blocks-bundled!
   :init-common-gui! gui-init/init-common!
   :init-common-lifecycle! runtime-lifecycle/init-common!
   :init-forge-energy! forge-energy/init-forge-energy!
   :init-ic2-energy! ic2-energy/init-ic2-energy!
   :init-item-handler! runtime-item-handler/init!
   :init-tutorial-events! tutorial-events/init!
   :init-imc! imc-dispatch/init!
   :register-world-state-changed! world-events/register-on-world-state-changed!
   :register-common-event-listeners! event-registration/register-common-event-listeners!})

(shared-event-install/install!)

(def run-common-setup! shared/run-common-setup!)
'''


def main() -> None:
    write(
        "platform-src/minecraft/base/src/main/clojure/cn/li/mcbase/bootstrap/platform_init.clj",
        PLATFORM_INIT,
    )
    write(
        "platform-src/minecraft/base/src/main/clojure/cn/li/mcbase/platform/runtime_ops.clj",
        RUNTIME_OPS,
    )
    write(
        "platform-src/loader/neoforge-shared/src/main/clojure/cn/li/neoforgebase/setup/common.clj",
        NEO_COMMON,
    )

    for ns, folder in [
        ("mc1201", "mc-1.20.1"),
        ("mc1211", "mc-1.21.1"),
        ("mc262", "mc-26.2"),
    ]:
        write(
            f"platform-src/minecraft/{folder}/src/main/clojure/cn/li/{ns}/bootstrap/platform_init.clj",
            PLATFORM_INIT_INSTALL % {"ns": ns},
        )
        write(
            f"platform-src/minecraft/{folder}/src/main/clojure/cn/li/{ns}/platform/runtime_ops.clj",
            RUNTIME_OPS_INSTALL % {"ns": ns},
        )

    for ns, folder in [
        ("neoforge1211", "neoforge-1.21.1"),
        ("neoforge262", "neoforge-26.2"),
    ]:
        write(
            f"platform-src/loader/{folder}/src/main/clojure/cn/li/{ns}/setup/common.clj",
            NEO_COMMON_INSTALL % {"ns": ns},
        )

    # Loaders already require versioned platform-init / runtime-ops / setup.common
    # which now install + re-export — no call-site updates required.

    blocked = ROOT / "scripts/DEDUP_REMAINING_BLOCKED.md"
    blocked.write_text(
        """# Remaining identical-but-blocked files

## MSDF note (checked 2026-08-06)

1.20.1 / 1.21.1 share several MSDF files (RenderTypes, MSDFAwareGlyph, setup/tick, cgui font).
`MsdfFontFace` / `MsdfFontManager` differ (1.21.1 FreeType stub). **26.2 has no MSDF pipeline.**

Cannot promote MSDF Java into `minecraft-base`: base compiles for all targets including 26.2
(ResourceLocation→Identifier, ShaderInstance/RenderType, GlyphInfo.bake forks). Options later:
- catalog component included only for Loom 1.20.1/1.21.1 targets, or
- finish 1.21.1 FreeType port + 26.2 MSDF rewrite then re-evaluate.

Already in mcbase (version-agnostic FX): MsdfTextFx, MsdfGlyphFlags, MsdfGlowAnimator.

## Batch5 / Batch6 promoted

Batch5: screen, multipart, adapters, item-use, raycast, commands, neo registry_binding.
Batch6: `bootstrap/platform_init`, `platform/runtime_ops`, NeoForge `setup/common`
(version files remain thin install + re-export).

## MC residual (intentional thin wrappers)
- gui/menu_bridge_install.clj (versioned DelegatingCMenuBridge factory)

## NeoForge residual (intentional version hub)
- setup/shared_event_install.clj
""",
        encoding="utf-8",
        newline="\n",
    )
    print("updated DEDUP_REMAINING_BLOCKED.md")


if __name__ == "__main__":
    main()
