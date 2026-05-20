(ns cn.li.fabric1201.mod
  "Fabric 1.20.1 loader entrypoint.

  Owns Fabric-specific bootstrap ordering and delegates cross-loader lifecycle
  phases to shared setup namespaces."
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.fabric1201.init :as init]
            [cn.li.fabric1201.platform.bootstrap-entry :as platform-bootstrap]
            [cn.li.mc1201.block.blockstate-properties :as bsp]
            [cn.li.fabric1201.setup.lifecycle-init :as lifecycle-init]
            [cn.li.fabric1201.setup.content-registration :as content-registration]
            [cn.li.fabric1201.setup.runtime-setup :as runtime-setup]
            [cn.li.fabric1201.setup.event-wiring :as event-wiring]
            [cn.li.fabric1201.config.bridge :as config-bridge]
            [cn.li.mcmod.lifecycle :as lifecycle]
            [cn.li.mcmod.protocol.metadata :as registry-metadata]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mc1201.entity.hooks :as entity-hooks])
  (:import [cn.li.fabric1201.shim FabricBootstrapHelper]))

(defn- current-mod-id
  []
  modid/*mod-id*)

(defonce registered-blocks (atom {}))
(defonce registered-items (atom {}))
(defonce registered-block-entities (atom {}))

(defonce base-properties
  (delay (FabricBootstrapHelper/createStoneProperties)))

(defonce carrier-properties
  (delay (FabricBootstrapHelper/carrierBlockProperties @base-properties)))

(defn- registration-context
  []
  {:mod-id (current-mod-id)
   :registered-blocks registered-blocks
   :registered-items registered-items
   :registered-block-entities registered-block-entities
   :base-properties @base-properties
   :carrier-properties @carrier-properties})

(defn start-fabric-mod!
  "Main Fabric mod initialization called from the Java ModInitializer."
  []
  (log/info "Initializing MyMod (Fabric 1.20.1) from Clojure...")
  (lifecycle-init/init-lifecycle!
    {:init-platform! platform-bootstrap/init-platform!
     :init-from-java! init/init-from-java
     :load-config! config-bridge/load-all!
     :activate-runtime-content! lifecycle/run-runtime-content-activation!
     :init-blockstate-properties! bsp/init-all-properties!
     :register-content! #(do
                           (content-registration/register-content! (registration-context))
                           (entity-hooks/register-all-hooks!))
     :install-runtime! runtime-setup/install-runtime!
     :register-events! event-wiring/register-events!})
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
  (let [tile-id (or (when (contains? @registered-block-entities tile-or-block-id)
                      tile-or-block-id)
                    (registry-metadata/get-block-tile-id tile-or-block-id))]
    (get @registered-block-entities tile-id)))

(defn get-registered-block-item
  "Get a registered block item by its block ID."
  [block-id]
  (get @registered-items (str block-id "-item")))
