(ns cn.li.fabric1201.mod
  "Fabric 1.20.1 mod placeholder entry.

  Compile-unblocking stub that avoids touching Minecraft registries during AOT."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.fabric1201.init :as init]
            [cn.li.ac.core :as core]
            [cn.li.fabric1201.platform.bootstrap-entry :as platform-bootstrap]
            [cn.li.mc1201.block.blockstate-properties :as bsp]
            [cn.li.fabric1201.setup.lifecycle-init :as lifecycle-init]
            [cn.li.fabric1201.setup.content-registration :as content-registration]
            [cn.li.fabric1201.setup.runtime-setup :as runtime-setup]
            [cn.li.fabric1201.setup.event-wiring :as event-wiring]
            [cn.li.fabric1201.config.bridge :as config-bridge]
            [cn.li.fabric1201.config.gameplay-bridge :as gameplay-bridge]
            [cn.li.mc1201.config.gameplay-bridge :as shared-gameplay-bridge]
            [cn.li.mcmod.registry.metadata :as registry-metadata]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mc1201.entity.effect-hooks :as effect-hooks]
            [cn.li.mc1201.entity.ray-hooks :as ray-hooks]
            [cn.li.mc1201.entity.marker-hooks :as marker-hooks])
  (:import [cn.li.fabric1201.shim FabricBootstrapHelper]))

(def mod-id modid/MOD-ID)

(defonce registered-blocks (atom {}))
(defonce registered-items (atom {}))
(defonce registered-block-entities (atom {}))

(defonce base-properties
  (delay (FabricBootstrapHelper/createStoneProperties)))

(defonce carrier-properties
  (delay (FabricBootstrapHelper/carrierBlockProperties @base-properties)))

(defn- registration-context
  []
  {:mod-id mod-id
   :registered-blocks registered-blocks
   :registered-items registered-items
   :registered-block-entities registered-block-entities
   :base-properties @base-properties
   :carrier-properties @carrier-properties})

(defn mod-init
  "Main mod initialization called from Java ModInitializer."
  []
  (log/info "Initializing MyMod (Fabric 1.20.1) from Clojure...")
  (lifecycle-init/init-lifecycle!
    {:init-platform! platform-bootstrap/init-platform!
     :init-from-java! init/init-from-java
     :init-core! core/init
     :load-config! config-bridge/load-all!
     :bind-gameplay-config! #(shared-gameplay-bridge/bind-gameplay-config! (gameplay-bridge/provider-map))
     :init-blockstate-properties! bsp/init-all-properties!
     :register-content! #(do
                           (content-registration/register-content! (registration-context))
                           (effect-hooks/register-all-effect-hooks!)
                           (ray-hooks/register-all-ray-hooks!)
                           (marker-hooks/register-all-marker-hooks!))
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
