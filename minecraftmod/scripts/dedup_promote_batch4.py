#!/usr/bin/env python3
"""Batch4: unlock + promote GUI/client/hooks clusters and NeoForge capability/loot SPI."""
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


def rewrite(replacements: list[tuple[str, str]]) -> None:
    for path in (ROOT / "platform-src").rglob("*"):
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


def promote_clj(rel: str, ns_suffix: str, source_ns: str = "mc1201") -> None:
    folder = {"mc1201": "mc-1.20.1", "mc1211": "mc-1.21.1", "mc262": "mc-26.2"}[source_ns]
    src = read_mc(rel, folder, source_ns)
    text = src.read_text(encoding="utf-8", errors="replace").replace(f"cn.li.{source_ns}", "cn.li.mcbase")
    write(ROOT / f"platform-src/minecraft/base/src/main/clojure/cn/li/mcbase/{rel}", text)
    delete_mc(rel)
    rewrite(
        [
            (f"cn.li.mc1201.{ns_suffix}", f"cn.li.mcbase.{ns_suffix}"),
            (f"cn.li.mc1211.{ns_suffix}", f"cn.li.mcbase.{ns_suffix}"),
            (f"cn.li.mc262.{ns_suffix}", f"cn.li.mcbase.{ns_suffix}"),
        ]
    )


def promote_java(rel: str, source_ns: str = "mc1201") -> None:
    folder = {"mc1201": "mc-1.20.1", "mc1211": "mc-1.21.1", "mc262": "mc-26.2"}[source_ns]
    src = read_mc(rel, folder, source_ns)
    text = src.read_text(encoding="utf-8", errors="replace").replace(f"cn.li.{source_ns}", "cn.li.mcbase")
    write(ROOT / f"platform-src/minecraft/base/src/main/java/cn/li/mcbase/{rel}", text)
    delete_mc(rel)
    simple = Path(rel).stem
    rewrite(
        [
            (f"cn.li.mc1201.{Path(rel).with_suffix('').as_posix().replace('/', '.')}", f"cn.li.mcbase.{Path(rel).with_suffix('').as_posix().replace('/', '.')}"),
            (f"cn.li.mc1211.{Path(rel).with_suffix('').as_posix().replace('/', '.')}", f"cn.li.mcbase.{Path(rel).with_suffix('').as_posix().replace('/', '.')}"),
            (f"cn.li.mc262.{Path(rel).with_suffix('').as_posix().replace('/', '.')}", f"cn.li.mcbase.{Path(rel).with_suffix('').as_posix().replace('/', '.')}"),
            (f"cn.li.mc1201.shim.{simple}", f"cn.li.mcbase.shim.{simple}"),
            (f"cn.li.mc1211.shim.{simple}", f"cn.li.mcbase.shim.{simple}"),
            (f"cn.li.mc262.shim.{simple}", f"cn.li.mcbase.shim.{simple}"),
            (f"cn.li.mc1201.gui.{simple}", f"cn.li.mcbase.gui.{simple}"),
            (f"cn.li.mc1211.gui.{simple}", f"cn.li.mcbase.gui.{simple}"),
            (f"cn.li.mc262.gui.{simple}", f"cn.li.mcbase.gui.{simple}"),
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


def unlock_cmenu_bridge() -> None:
    """Promote shared CMenuBridge; leave clicked helper on versioned DelegatingCMenuBridge."""
    write(
        ROOT / "platform-src/minecraft/base/src/main/java/cn/li/mcbase/gui/CMenuBridge.java",
        """package cn.li.mcbase.gui;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Shared menu bridge exposing protected AbstractContainerMenu APIs as public
 * methods for Clojure proxy implementations.
 *
 * Versioned {@code DelegatingCMenuBridge} subclasses provide {@code clicked}
 * and {@code callSuperClicked} because ClickType vs ContainerInput forks.
 */
public abstract class CMenuBridge extends AbstractContainerMenu {
    protected CMenuBridge(MenuType<?> menuType, int containerId) {
        super(menuType, containerId);
    }

    public Slot addSlotPublic(Slot slot) {
        return super.addSlot(slot);
    }

    public DataSlot addDataSlotPublic(DataSlot dataSlot) {
        return super.addDataSlot(dataSlot);
    }

    public void callSuperRemoved(Player player) {
        super.removed(player);
    }

    public void callSuperBroadcastChanges() {
        super.broadcastChanges();
    }

    public boolean callSuperMoveItemStackTo(ItemStack stack, int startIndex, int endIndex, boolean reverseDirection) {
        return super.moveItemStackTo(stack, startIndex, endIndex, reverseDirection);
    }

    /**
     * The Clojure container backing this menu. Set by menu/proxy.clj at creation.
     */
    public Object cljContainer;
}
""",
    )
    delete_mc("gui/CMenuBridge.java")

    for folder, ns, click_type in (
        ("mc-1.20.1", "mc1201", "ClickType"),
        ("mc-1.21.1", "mc1211", "ClickType"),
        ("mc-26.2", "mc262", "ContainerInput"),
    ):
        path = ROOT / f"platform-src/minecraft/{folder}/src/main/java/cn/li/{ns}/shim/DelegatingCMenuBridge.java"
        text = path.read_text(encoding="utf-8", errors="replace")
        # extend mcbase CMenuBridge
        text = re.sub(
            r"import cn\.li\.(mc1201|mc1211|mc262)\.gui\.CMenuBridge;",
            "import cn.li.mcbase.gui.CMenuBridge;",
            text,
        )
        # add callSuperClicked if missing
        if "callSuperClicked" not in text:
            insert = f"""
    public void callSuperClicked(int slotIndex, int button, {click_type} clickType, Player player) {{
        super.clicked(slotIndex, button, clickType, player);
    }}
"""
            # insert before last closing brace
            text = text.rstrip()
            if text.endswith("}"):
                text = text[:-1] + insert + "}\n"
        path.write_text(text, encoding="utf-8", newline="\n")
        print("delegating patch", path.relative_to(ROOT))


def unlock_hooks_prefix() -> None:
    """Replace hardcoded version package strings with installable prefix + suffixes."""
    for folder, ns in MC:
        path = read_mc("entity/hook_registry_core.clj", folder, ns)
        text = path.read_text(encoding="utf-8", errors="replace")
        if "*hook-class-prefix*" not in text:
            text = text.replace(
                "(:import [cn.li.mcbase.entity ScriptedEntitySpecAccess]))",
                """(:import [cn.li.mcbase.entity ScriptedEntitySpecAccess]))

(defonce ^:private *hook-class-prefix*
  (atom nil))

(defn install-hook-class-prefix!
  "Install version package prefix, e.g. \\\"cn.li.mc1201\\\"."
  [prefix]
  (reset! *hook-class-prefix* (str prefix))
  prefix)

(defn- hook-class
  [suffix]
  (let [prefix @*hook-class-prefix*]
    (when (nil? prefix)
      (throw (IllegalStateException. \"hook-class-prefix not installed\")))
    (str prefix \".\" suffix)))
""",
            )
        # Use suffixes; resolve to FQCN lazily in resolve-scripted-hook-class
        text = re.sub(
            r":impl-key->hook-class \{:tiered-arcs \"cn\.li\.(?:mc1201|mc1211|mc262)\.entity\.hook\.effect\.TieredArcsEffectHook\"\s*"
            r":owner-offset \"cn\.li\.(?:mc1201|mc1211|mc262)\.entity\.hook\.effect\.OwnerOffsetEffectHook\"\s*"
            r":owner-orbit \"cn\.li\.(?:mc1201|mc1211|mc262)\.entity\.hook\.effect\.OwnerOrbitEffectHook\"\s*"
            r":noop \"cn\.li\.(?:mc1201|mc1211|mc262)\.entity\.hook\.effect\.NoopEffectHook\"\s*"
            r":vertical-ballistic \"cn\.li\.(?:mc1201|mc1211|mc262)\.entity\.hook\.effect\.NoopEffectHook\"\}",
            """:impl-key->hook-suffix {:tiered-arcs \"entity.hook.effect.TieredArcsEffectHook\"
                                   :owner-offset \"entity.hook.effect.OwnerOffsetEffectHook\"
                                   :owner-orbit \"entity.hook.effect.OwnerOrbitEffectHook\"
                                   :noop \"entity.hook.effect.NoopEffectHook\"
                                   :vertical-ballistic \"entity.hook.effect.NoopEffectHook\"}""",
            text,
        )
        text = re.sub(
            r":impl-key->hook-class \{:owner-follow \"cn\.li\.(?:mc1201|mc1211|mc262)\.entity\.hook\.ray\.OwnerFollowRayHook\"\}",
            ':impl-key->hook-suffix {:owner-follow "entity.hook.ray.OwnerFollowRayHook"}',
            text,
        )
        # resolve-scripted-hook-class: build class map from suffixes at call time
        old_resolve = """(defn- resolve-scripted-hook-class
  [{:keys [catalog-impl-key-fn impl-key->hook-class install-key]}
   {:keys [hook-props hook-id]}]
  (let [allowed-classes (hook-support/filter-impl-key->hook-class
                         install-key impl-key->hook-class)"""
        new_resolve = """(defn- resolve-scripted-hook-class
  [{:keys [catalog-impl-key-fn impl-key->hook-class impl-key->hook-suffix install-key]}
   {:keys [hook-props hook-id]}]
  (let [impl-key->hook-class (or impl-key->hook-class
                                 (into {}
                                       (map (fn [[k suffix]] [k (hook-class suffix)]))
                                       impl-key->hook-suffix))
        allowed-classes (hook-support/filter-impl-key->hook-class
                         install-key impl-key->hook-class)"""
        if old_resolve in text:
            text = text.replace(old_resolve, new_resolve)
        path.write_text(text, encoding="utf-8", newline="\n")
        print("hooks unlock", path.relative_to(ROOT))


def unlock_menu_factory() -> None:
    """Inject DelegatingCMenuBridge ctor so proxy can live in mcbase."""
    for folder, ns in MC:
        path = read_mc("gui/menu/proxy.clj", folder, ns)
        text = path.read_text(encoding="utf-8", errors="replace")
        # drop versioned imports; use mcbase CMenuBridge + installed ctor
        text = re.sub(
            r"\(:import \[cn\.li\.(mc1201|mc1211|mc262|mcbase)\.shim DelegatingCMenuBridge\]\s*"
            r"\[cn\.li\.(mc1201|mc1211|mc262|mcbase)\.gui CMenuBridge\]",
            "(:import [cn.li.mcbase.gui CMenuBridge]",
            text,
        )
        if "*new-menu-bridge*" not in text:
            text = text.replace(
                "[net.minecraft.world.item ItemStack]))\n",
                """[net.minecraft.world.item ItemStack]))

(defonce ^:private *new-menu-bridge*
  (atom nil))

(defn install-new-menu-bridge!
  "Install (fn [menu-type window-id] DelegatingCMenuBridge)."
  [f]
  (reset! *new-menu-bridge* f)
  f)

(defn- new-menu-bridge
  [menu-type window-id]
  (let [f @*new-menu-bridge*]
    (when (nil? f)
      (throw (IllegalStateException. \"menu-bridge factory not installed\")))
    (f menu-type window-id)))
""",
            )
        text = text.replace("(DelegatingCMenuBridge. menu-type (int window-id))", "(new-menu-bridge menu-type (int window-id))")
        # McAccess / getServer unify
        text = re.sub(r"\[cn\.li\.(mcbase|mc1201|mc1211|mc262)\.bridge McAccess\]\s*", "", text)
        text = text.replace(
            "(when-let [server (McAccess/serverOf server-player)]",
            "(when-let [server (some-> server-player .level .getServer)]",
        )
        text = text.replace(
            "(when-let [server (some-> server-player .getServer)]",
            "(when-let [server (some-> server-player .level .getServer)]",
        )
        # fully-qualified CMenuBridge → short
        text = re.sub(r"\^cn\.li\.(mc1201|mc1211|mc262)\.gui\.CMenuBridge", "^CMenuBridge", text)
        path.write_text(text, encoding="utf-8", newline="\n")
        print("proxy unlock", path.relative_to(ROOT))

        # version install stub next to provider or bootstrap — write menu_bridge_install.clj
        install_path = ROOT / f"platform-src/minecraft/{folder}/src/main/clojure/cn/li/{ns}/gui/menu_bridge_install.clj"
        write(
            install_path,
            f"""(ns cn.li.{ns}.gui.menu-bridge-install
  "Install versioned DelegatingCMenuBridge factory into shared menu proxy."
  (:require [cn.li.mcbase.gui.menu.proxy :as menu-proxy])
  (:import [cn.li.{ns}.shim DelegatingCMenuBridge]))

(defn install!
  []
  (menu-proxy/install-new-menu-bridge!
    (fn [menu-type window-id]
      (DelegatingCMenuBridge. menu-type (int window-id))))
  nil)
""",
        )


def unlock_data_slot_typehints() -> None:
    for folder, ns in MC:
        for rel in ("gui/slots/data_slot.clj", "gui/slots/sync.clj"):
            path = read_mc(rel, folder, ns)
            text = path.read_text(encoding="utf-8", errors="replace")
            text = re.sub(r"\[cn\.li\.(mc1201|mc1211|mc262)\.gui CMenuBridge\]", "[cn.li.mcbase.gui CMenuBridge]", text)
            text = text.replace(f"^cn.li.{ns}.gui.CMenuBridge", "^cn.li.mcbase.gui.CMenuBridge")
            path.write_text(text, encoding="utf-8", newline="\n")
            print("slot hint", path.relative_to(ROOT))


def wire_menu_bridge_install() -> None:
    """Ensure platform_init / init path calls menu-bridge-install."""
    for folder, ns in MC:
        # prefer platform_init
        for rel in ("bootstrap/platform_init.clj", "bootstrap/init_common.clj", "bootstrap/installer_core.clj"):
            path = ROOT / f"platform-src/minecraft/{folder}/src/main/clojure/cn/li/{ns}/{rel}"
            if not path.exists():
                continue
            text = path.read_text(encoding="utf-8", errors="surrogateescape")
            if "menu-bridge-install" in text:
                break
            # append require + call in platform_init if it has install wrappers
            if rel.endswith("platform_init.clj"):
                if "(:require" in text and f"cn.li.{ns}.gui.menu-bridge-install" not in text:
                    text = text.replace(
                        "(:require ",
                        f"(:require [cn.li.{ns}.gui.menu-bridge-install :as menu-bridge-install]\n            ",
                        1,
                    )
                # find a public install! / init function to hook — call at end of ns load is ok for install
                if "(menu-bridge-install/install!)" not in text:
                    text = text.rstrip() + "\n\n(menu-bridge-install/install!)\n"
                path.write_text(text, encoding="utf-8", errors="surrogateescape", newline="\n")
                print("wire menu install", path.relative_to(ROOT))
                break


def promote_mc_batch() -> None:
    unlock_cmenu_bridge()
    promote_java("shim/UniversalContainer.java")

    unlock_hooks_prefix()
    unlock_menu_factory()
    unlock_data_slot_typehints()

    # unify docstring-only forks then promote
    for rel in (
        "client/session.clj",
        "gui/slots/common.clj",
        "command/feedback.clj",
        "client/effects/hand.clj",
        "gui/menu/container.clj",
        "entity/hook_registry_core.clj",
        "gui/menu/proxy.clj",
        "gui/slots/data_slot.clj",
        "gui/slots/sync.clj",
    ):
        unify_from_1201(rel)

    promote_clj("entity/hook_registry_core.clj", "entity.hook-registry-core")
    # Keep versioned entity.hooks wrappers (prefix install); do not rewrite loader requires.
    promote_clj("client/session.clj", "client.session")
    promote_clj("client/overlay/state.clj", "client.overlay.state")
    promote_clj("client/effects/hand.clj", "client.effects.hand")
    promote_clj("client/runtime/hand_effect_renderer_core.clj", "client.runtime.hand-effect-renderer-core")
    promote_clj("command/feedback.clj", "command.feedback")
    promote_clj("gui/menu/container.clj", "gui.menu.container")
    promote_clj("gui/menu/proxy.clj", "gui.menu.proxy")
    promote_clj("gui/slots/common.clj", "gui.slots.common")
    promote_clj("gui/slots/data_slot.clj", "gui.slots.data-slot")
    promote_clj("gui/slots/sync.clj", "gui.slots.sync")
    promote_clj("gui/provider_bridge.clj", "gui.provider-bridge")

    # after proxy promote, rewrite menu-bridge-install requires (already point to mcbase)
    wire_menu_bridge_install()

    write(
        ROOT / "platform-src/minecraft/base/src/main/clojure/cn/li/mcbase/entity/hooks.clj",
        """(ns cn.li.mcbase.entity.hooks
  "Shared scripted entity hook registration (prefix must be installed first)."
  (:require [cn.li.mcbase.entity.hook-registry-core :as hook-core]))

(defn register-all-hooks!
  []
  (hook-core/register-all-scripted-hooks!))
""",
    )
    for folder, ns in MC:
        write(
            ROOT / f"platform-src/minecraft/{folder}/src/main/clojure/cn/li/{ns}/entity/hooks.clj",
            f"""(ns cn.li.{ns}.entity.hooks
  "Versioned entry: install hook class package prefix then register shared hooks."
  (:require [cn.li.mcbase.entity.hook-registry-core :as hook-core]
            [cn.li.mcbase.entity.hooks :as shared-hooks]))

(defn register-all-hooks!
  []
  (hook-core/install-hook-class-prefix! \"cn.li.{ns}\")
  (shared-hooks/register-all-hooks!))
""",
        )


def promote_neo_spi() -> None:
    write(
        ROOT
        / "platform-src/loader/neoforge-shared/src/main/clojure/cn/li/neoforgebase/integration/events/loot.clj",
        """(ns cn.li.neoforgebase.integration.events.loot
  "Shared loot-table load handlers. Version loaders install LootInjectionHelper bridge."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.protocol.metadata :as registry-metadata])
  (:import [net.neoforged.neoforge.event LootTableLoadEvent]))

(defonce ^:private *add-item-injection*
  (atom nil))

(defn install-add-item-injection!
  "Install (fn [evt item-id weight quality min max] ...)."
  [f]
  (reset! *add-item-injection* f)
  f)

(defn handle-loot-table-load
  [^LootTableLoadEvent evt]
  (try
    (let [add! @*add-item-injection*]
      (when (nil? add!)
        (throw (IllegalStateException. "loot add-item-injection not installed")))
      (let [table-id (str (.getName evt))
            injections (registry-metadata/get-loot-injections-for-table table-id)]
        (when (seq injections)
          (doseq [spec injections]
            (add!
              evt
              (:item-id spec)
              (int (or (:weight spec) 1))
              (int (or (:quality spec) 0))
              (float (or (:min-count spec) 1.0))
              (float (or (:max-count spec) 1.0)))))))
    (catch Throwable t
      (log/error "Error handling loot table load event:" (.getMessage t))
      (.printStackTrace t))))
""",
    )

    write(
        ROOT
        / "platform-src/loader/neoforge-shared/src/main/clojure/cn/li/neoforgebase/setup/capability_wiring.clj",
        """(ns cn.li.neoforgebase.setup.capability-wiring
  "Shared NeoForge capability listener wiring. Version loaders install Java bridges."
  (:require [cn.li.neoforgebase.setup.consumer-support :as consumer-support]
            [cn.li.mcmod.capability.registry :as cap-registry])
  (:import [net.neoforged.neoforge.capabilities RegisterCapabilitiesEvent]
           [net.neoforged.bus.api IEventBus]))

(defonce ^:private *bridge*
  (atom nil))

(defn install-capability-bridge!
  "Install {:get-block :register-block! :get-or-create-block! :block-cap-for-type :register-all!}."
  [bridge]
  (reset! *bridge* bridge)
  bridge)

(defn- bridge! []
  (let [b @*bridge*]
    (when (nil? b)
      (throw (IllegalStateException. "capability bridge not installed")))
    b))

(defn- add-listener!
  [^IEventBus mod-bus ^Class listener-class f]
  (consumer-support/add-normal-listener! mod-bus listener-class f))

(defn- ensure-block-capability-token!
  [key ^Class java-type]
  (let [b (bridge!)
        k (name key)]
    (when-not ((:get-block b) k)
      (if-let [provided ((:block-cap-for-type b) java-type)]
        ((:register-block! b) k provided)
        ((:get-or-create-block! b) k java-type)))
    nil))

(defn- sync-declared-capability-tokens!
  []
  (doseq [[key {:keys [java-type]}] (cap-registry/capability-type-registry-snapshot)]
    (when java-type
      (ensure-block-capability-token! key java-type)))
  nil)

(defn register-capability-listener!
  [^IEventBus mod-bus]
  (let [b (bridge!)]
    (add-listener! mod-bus RegisterCapabilitiesEvent
                   (fn [event]
                     (sync-declared-capability-tokens!)
                     ((:register-all! b) event))))
  nil)
""",
    )

    write(
        ROOT
        / "platform-src/loader/neoforge-shared/src/main/clojure/cn/li/neoforgebase/setup/imc_dispatcher.clj",
        """(ns cn.li.neoforgebase.setup.imc-dispatcher
  "Forge mod-bus listener for processing incoming IMC registrations."
  (:require [cn.li.neoforgebase.integration.imc-dispatch :as imc-dispatch]
            [cn.li.neoforgebase.setup.consumer-support :as consumer-support]
            [cn.li.mcmod.util.log :as log])
  (:import [java.util.function Consumer Supplier]
           [net.neoforged.bus.api IEventBus]
           [net.neoforged.fml.event.lifecycle InterModProcessEvent]
           [net.neoforged.fml InterModComms$IMCMessage]))

(defn- imc-method-key
  [^InterModComms$IMCMessage msg]
  (str (.method msg)))

(defn- imc-supplier
  [^InterModComms$IMCMessage msg]
  (.messageSupplier msg))

(defn- resolve-payload
  [msg]
  (let [supplier (imc-supplier msg)]
    (cond
      (instance? Supplier supplier) (.get ^Supplier supplier)
      (fn? supplier) (supplier)
      :else supplier)))

(defn- handle-imc-message!
  [msg]
  (imc-dispatch/register-by-method-key! (imc-method-key msg) (resolve-payload msg)))

(defn- handle-imc-process-event!
  [^InterModProcessEvent evt]
  (try
    (let [stream (.getIMCStream evt)]
      (.forEach stream
                (reify Consumer
                  (accept [_ msg]
                    (handle-imc-message! msg)))))
    (catch Throwable t
      (log/error "Failed to process IMC registrations" t)
      (log/stacktrace "handle-imc-process-event! caught exception" t))))

(defn register-imc-listener!
  [^IEventBus mod-bus]
  (consumer-support/add-normal-listener! mod-bus InterModProcessEvent handle-imc-process-event!)
  nil)
""",
    )

    write(
        ROOT
        / "platform-src/loader/neoforge-shared/src/main/clojure/cn/li/neoforgebase/setup/capability_setup.clj",
        """(ns cn.li.neoforgebase.setup.capability-setup
  "Shared capability + network registration phase."
  (:require [cn.li.neoforgebase.setup.capability-wiring :as capability-wiring]
            [cn.li.neoforgebase.setup.imc-dispatcher :as imc-dispatcher])
  (:import [net.neoforged.bus.api IEventBus]))

(defonce ^:private *register-network*
  (atom nil))

(defn install-register-network!
  "Install (fn [mod-bus] ...)."
  [f]
  (reset! *register-network* f)
  f)

(defn register-capability-phase!
  [^IEventBus mod-bus _opts]
  (let [register-network! @*register-network*]
    (when (nil? register-network!)
      (throw (IllegalStateException. "capability-setup register-network not installed")))
    (imc-dispatcher/register-imc-listener! mod-bus)
    (capability-wiring/register-capability-listener! mod-bus)
    (register-network! mod-bus)
    nil))
""",
    )

    for folder, ns in NEO:
        for rel in (
            "integration/events/loot.clj",
            "setup/capability_wiring.clj",
            "setup/capability_setup.clj",
            "setup/imc_dispatcher.clj",
        ):
            p = ROOT / f"platform-src/loader/{folder}/src/main/clojure/cn/li/{ns}/{rel}"
            if p.exists():
                p.unlink()
                print("delete", p.relative_to(ROOT))

        write(
            ROOT
            / f"platform-src/loader/{folder}/src/main/clojure/cn/li/{ns}/setup/shared_event_install.clj",
            f"""(ns cn.li.{ns}.setup.shared-event-install
  "Install NeoForge-shared ports that need versioned Mod*/Java bridges."
  (:require [cn.li.neoforgebase.integration.events.entity-attributes :as entity-attr-events]
            [cn.li.neoforgebase.integration.events.loot :as loot-events]
            [cn.li.neoforgebase.setup.capability-wiring :as capability-wiring]
            [cn.li.neoforgebase.setup.capability-setup :as capability-setup])
  (:import [cn.li.{ns}.entity ModEntities]
           [cn.li.{ns}.loot LootInjectionHelper]
           [cn.li.{ns}.capability CapabilityRegistry
            ForgeCapabilityHandler
            ForgeProvidedCapabilitySupport]
           [cn.li.{ns}.network ClojureNetwork]))

(defn install!
  []
  (entity-attr-events/install-register-mob-attrs!
    (fn [event entity-type]
      (ModEntities/registerMobDefaultAttributes event entity-type)))
  (loot-events/install-add-item-injection!
    (fn [evt item-id weight quality min max]
      (LootInjectionHelper/addItemInjection evt item-id weight quality min max)))
  (capability-wiring/install-capability-bridge!
    {{:get-block #(CapabilityRegistry/getBlock %)
     :register-block! (fn [k cap] (CapabilityRegistry/registerBlock k cap))
     :get-or-create-block! (fn [k typ] (CapabilityRegistry/getOrCreateBlock k typ))
     :block-cap-for-type #(ForgeProvidedCapabilitySupport/blockCapabilityForType %)
     :register-all! #(ForgeCapabilityHandler/registerAll %)}})
  (capability-setup/install-register-network!
    (fn [mod-bus]
      (ClojureNetwork/register mod-bus)))
  nil)
""",
        )

        rewrite_neo = [
            (f"cn.li.{ns}.integration.events.loot", "cn.li.neoforgebase.integration.events.loot"),
            (f"cn.li.{ns}.setup.capability-wiring", "cn.li.neoforgebase.setup.capability-wiring"),
            (f"cn.li.{ns}.setup.capability-setup", "cn.li.neoforgebase.setup.capability-setup"),
            (f"cn.li.{ns}.setup.imc-dispatcher", "cn.li.neoforgebase.setup.imc-dispatcher"),
        ]
        for path in (ROOT / f"platform-src/loader/{folder}").rglob("*.clj"):
            text = path.read_text(encoding="utf-8", errors="surrogateescape")
            orig = text
            for a, b in rewrite_neo:
                text = text.replace(a, b)
            if text != orig:
                path.write_text(text, encoding="utf-8", errors="surrogateescape", newline="\n")
                print("rewrite", path.relative_to(ROOT))

        common = ROOT / f"platform-src/loader/{folder}/src/main/clojure/cn/li/{ns}/setup/common.clj"
        if common.exists():
            text = common.read_text(encoding="utf-8", errors="surrogateescape")
            if "shared-event-install" not in text:
                text = text.replace(
                    "(:require ",
                    f"(:require [cn.li.{ns}.setup.shared-event-install :as shared-event-install]\n            ",
                    1,
                )
                text = text.rstrip() + "\n\n(shared-event-install/install!)\n"
                common.write_text(text, encoding="utf-8", newline="\n")
                print("wire common", common.relative_to(ROOT))


def main() -> None:
    promote_mc_batch()
    promote_neo_spi()
    print("BATCH4 DONE")


if __name__ == "__main__":
    main()
