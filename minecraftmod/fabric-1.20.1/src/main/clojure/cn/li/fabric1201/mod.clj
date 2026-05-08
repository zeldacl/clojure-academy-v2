(ns cn.li.fabric1201.mod
  "Fabric 1.20.1 mod placeholder entry.

  Compile-unblocking stub that avoids touching Minecraft registries during AOT."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.fabric1201.init :as init]
            [cn.li.ac.core :as core]
            [cn.li.fabric1201.platform.bootstrap-entry :as platform-bootstrap]
            [cn.li.fabric1201.blockstate-properties :as bsp]
            [cn.li.fabric1201.integration.events :as events]
            [cn.li.fabric1201.gui.init :as gui-init]
            [cn.li.fabric1201.config.bridge :as config-bridge]
            [cn.li.mcmod.util.log :as log]))

(def mod-id modid/MOD-ID)

(defonce registered-blocks (atom {}))
(defonce registered-items (atom {}))
(defonce registered-block-entities (atom {}))

(defn mod-init
  "Main mod initialization called from Java ModInitializer."
  []
  (log/info "Initializing MyMod (Fabric 1.20.1) from Clojure...")
  (platform-bootstrap/init-platform!)
  (init/init-from-java)
  (core/init)
  (config-bridge/load-all!)
  (bsp/init-all-properties!)
  (gui-init/init-common!)
  (gui-init/init-server!)
  (events/register-events)
  (log/info "Fabric mod initialization complete"))

(defn get-registered-block
  "Get a registered block by its DSL ID."
  [block-id]
  (get @registered-blocks block-id))

(defn get-registered-item
  "Get a registered item by its DSL ID."
  [item-id]
  (get @registered-items item-id))

(defn get-registered-block-entity-type
  "Get a registered BlockEntityType by tile-id or block-id."
  [tile-or-block-id]
  (get @registered-block-entities tile-or-block-id))

(defn get-registered-block-item
  "Get a registered block item by its block ID."
  [block-id]
  (get @registered-items (str block-id "-item")))
