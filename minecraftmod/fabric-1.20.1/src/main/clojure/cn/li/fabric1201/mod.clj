(ns cn.li.fabric1201.mod
  "Fabric 1.20.1 mod placeholder entry.

  Compile-unblocking stub that avoids touching Minecraft registries during AOT."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.fabric1201.init :as init]
            [cn.li.ac.core :as core]
            [cn.li.fabric1201.platform.bootstrap-entry :as platform-bootstrap]
            [cn.li.fabric1201.registry.fabric-dispatch :as fabric-dispatch]
            [cn.li.mc1201.block.blockstate-properties :as bsp]
            [cn.li.fabric1201.integration.events :as events]
            [cn.li.fabric1201.runtime.damage-interception :as runtime-damage-interception]
            [cn.li.fabric1201.runtime.item-handler :as runtime-item-handler]
            [cn.li.fabric1201.runtime.player-motion :as runtime-player-motion]
            [cn.li.fabric1201.runtime.entity-damage :as runtime-entity-damage]
            [cn.li.fabric1201.runtime.entity-motion :as runtime-entity-motion]
            [cn.li.fabric1201.runtime.entity-query :as runtime-entity-query]
            [cn.li.fabric1201.runtime.raycast :as runtime-raycast]
            [cn.li.fabric1201.runtime.world-effects :as runtime-world-effects]
            [cn.li.fabric1201.runtime.teleportation :as runtime-teleportation]
            [cn.li.fabric1201.runtime.saved-locations :as runtime-saved-locations]
            [cn.li.mc1201.runtime.nbt-core :as runtime-nbt]
                        [cn.li.mc1201.runtime.sync-core :as runtime-sync]
                        [cn.li.fabric1201.runtime.network :as runtime-network]
                        [cn.li.fabric1201.runtime.potion-effects :as runtime-potion-effects]
                        [cn.li.fabric1201.runtime.interop :as runtime-interop]
                        [cn.li.fabric1201.runtime.block-manipulation :as runtime-block-manipulation]
            [cn.li.fabric1201.gui.init :as gui-init]
            [cn.li.fabric1201.config.bridge :as config-bridge]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.registry.metadata :as registry-metadata]
            [cn.li.mcmod.platform.power-runtime :as power-runtime]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.entity.dsl :as edsl]
            [cn.li.mc1201.entity.effect-hooks :as effect-hooks]
            [cn.li.mc1201.entity.ray-hooks :as ray-hooks]
            [cn.li.mc1201.entity.marker-hooks :as marker-hooks])
  (:import [cn.li.fabric1201.entity FabricScriptedEntityAccess]
           [cn.li.fabric1201.shim FabricBootstrapHelper]
           [cn.li.mc1201.entity.spec ScriptedProjectileSpec ScriptedEffectSpec ScriptedRaySpec ScriptedMarkerSpec ScriptedBlockBodySpec]
           [net.minecraft.world.item Item Item$Properties]))

(def mod-id modid/MOD-ID)

(defonce registered-blocks (atom {}))
(defonce registered-items (atom {}))
(defonce registered-block-entities (atom {}))

(defonce base-properties
  (delay (FabricBootstrapHelper/createStoneProperties)))

(defonce carrier-properties
  (delay (FabricBootstrapHelper/carrierBlockProperties @base-properties)))

(defn- has-block-state-properties?
  [block-id]
  (registry-metadata/has-block-state-properties? block-id))

(defn register-scripted-tile-hooks!
  []
  (doseq [tile-id (registry-metadata/get-all-tile-ids)]
    (when-let [spec (registry-metadata/get-tile-spec tile-id)]
      (let [tick-fn (:tick-fn spec)
            read-nbt-fn (:read-nbt-fn spec)
            write-nbt-fn (:write-nbt-fn spec)
            tile-kind (:tile-kind spec)]
        (when (or tick-fn read-nbt-fn write-nbt-fn tile-kind)
          (tile-logic/register-tile-logic! tile-id
                                           {:tile-kind tile-kind
                                            :tick-fn tick-fn
                                            :read-nbt-fn read-nbt-fn
                                            :write-nbt-fn write-nbt-fn}))))))

(defn register-all-blocks!
  []
  (let [get-props (requiring-resolve 'cn.li.mc1201.block.blockstate-properties/get-all-properties)]
    (doseq [block-id (registry-metadata/get-all-block-ids)]
      (let [registry-name (registry-metadata/get-block-registry-name block-id)
            fluid-id (registry-metadata/get-fluid-id-for-block block-id)
            needs-dynamic-properties? (has-block-state-properties? block-id)
            has-be? (registry-metadata/has-block-entity? block-id)
            tile-id (when has-be?
                      (or (registry-metadata/get-block-tile-id block-id) block-id))
            block-inst (cond
                         (and fluid-id (not (registry-metadata/fluid-block? block-id)))
                         (FabricBootstrapHelper/createPlainBlock @base-properties)

                         fluid-id
                         (do
                           (log/warn "Fabric block registration: fluid-backed block falls back to plain block until fluid chain is wired" {:block-id block-id :fluid-id fluid-id})
                           (FabricBootstrapHelper/createPlainBlock @base-properties))

                         (and needs-dynamic-properties? has-be?)
                         (let [props (get-props block-id)]
                           (FabricBootstrapHelper/createCarrierScriptedDynamicBlock block-id tile-id props @carrier-properties))

                         needs-dynamic-properties?
                         (let [props (get-props block-id)]
                           (FabricBootstrapHelper/createDynamicStateBlock block-id props @base-properties))

                         has-be?
                         (FabricBootstrapHelper/createCarrierScriptedBlock block-id tile-id @carrier-properties)

                         :else
                         (FabricBootstrapHelper/createPlainBlock @base-properties))
            registered (fabric-dispatch/register-block registry-name block-inst)]
        (swap! registered-blocks assoc block-id registered)))))

(defn register-block-entities!
  []
  (doseq [tile-id (registry-metadata/get-all-tile-ids)]
    (let [registry-name (registry-metadata/get-tile-registry-name tile-id)
          block-ids (registry-metadata/get-tile-block-ids tile-id)
          blocks (keep #(get @registered-blocks %) block-ids)]
      (when (seq blocks)
        (let [pairs (map vector block-ids blocks)
              block-id-map (java.util.IdentityHashMap.)]
          (doseq [[resolved-block-id block-inst] pairs]
            (.put block-id-map block-inst resolved-block-id))
          (let [be-type (FabricBootstrapHelper/createScriptedBlockEntityType
                          tile-id
                          blocks
                          (reify java.util.function.Function
                            (apply [_ block-inst]
                              (.get block-id-map block-inst))))
                registered (FabricBootstrapHelper/registerBlockEntityType mod-id registry-name be-type)]
            (swap! registered-block-entities assoc tile-id registered)))))))

(defn- create-standalone-item
  [_item-spec]
  (Item. (Item$Properties.)))

(defn register-all-items!
  []
  (doseq [item-id (registry-metadata/get-all-item-ids)]
    (let [registry-name (registry-metadata/get-item-registry-name item-id)
          item-spec (registry-metadata/get-item-spec item-id)
          item-inst (create-standalone-item item-spec)
          registered (fabric-dispatch/register-item registry-name item-inst)]
      (swap! registered-items assoc item-id registered)))

  (doseq [block-id (registry-metadata/get-all-block-ids)]
    (when (and (registry-metadata/should-create-block-item? block-id)
               (not (registry-metadata/fluid-block? block-id)))
      (when-let [block-inst (get @registered-blocks block-id)]
        (let [registry-name (registry-metadata/get-block-registry-name block-id)
              block-item (FabricBootstrapHelper/createBlockItem block-inst)
              registered (fabric-dispatch/register-item registry-name block-item)]
          (swap! registered-items assoc (str block-id "-item") registered))))))

(defn- register-scripted-projectile-spec!
  [registry-name entity-spec]
  (let [projectile (get-in entity-spec [:properties :projectile])
        hooks (:hooks projectile)
        spec (ScriptedProjectileSpec.
               (str (or (:default-item-id projectile) ""))
               (double (or (:gravity projectile) 0.05))
               (double (or (:damage projectile) 0.0))
               (double (or (:pickup-distance-sqr projectile) 2.25))
               (not (false? (:drop-item-on-discard? projectile)))
               (name (or (:on-hit-block hooks) :none))
               (name (or (:on-hit-entity hooks) :none))
               (name (or (:on-anchored-tick hooks) :none))
               (name (or (:on-anchored-hurt hooks) :none)))]
    (FabricScriptedEntityAccess/registerScriptedProjectileSpec
      (str registry-name)
      spec))
  nil)

(defn- register-scripted-effect-spec!
  [registry-name entity-spec]
  (let [effect (get-in entity-spec [:properties :effect])]
    (letfn [(normalize-hook-params [params]
              (into {}
                    (map (fn [[k v]]
                           [(cond
                              (keyword? k) (name k)
                              (string? k) k
                              :else (str k))
                            v]))
                    (or params {})))]
      (let [spec (ScriptedEffectSpec.
                   (int (or (:life-ticks effect) 15))
                   (not (false? (:follow-owner? effect)))
                   (str (or (:renderer-id effect) "effect-billboard"))
                   (name (or (:hook effect) :none))
                   (normalize-hook-params (:hook-params effect)))]
        (FabricScriptedEntityAccess/registerScriptedEffectSpec
          (str registry-name)
          spec))))
  nil)

(defn- register-scripted-ray-spec!
  [registry-name entity-spec]
  (let [ray (get-in entity-spec [:properties :ray])]
    (FabricScriptedEntityAccess/registerScriptedRaySpec
      (str registry-name)
      (ScriptedRaySpec.
        (int (or (:life-ticks ray) 30))
        (double (or (:length ray) 15.0))
        (double (or (:blend-in-ms ray) 100.0))
        (double (or (:blend-out-ms ray) 300.0))
        (double (or (:inner-width ray) 0.03))
        (double (or (:outer-width ray) 0.1))
        (double (or (:glow-width ray) 0.05))
        (int (or (:start-color ray) 0xFFFFFFFF))
        (int (or (:end-color ray) 0xFFFFFFFF))
        ""  ; renderer id
        (name (or (:hook ray) :none)))))
  nil)

(defn- register-scripted-marker-spec!
  [registry-name entity-spec]
  (let [marker (get-in entity-spec [:properties :marker])
        spec (ScriptedMarkerSpec.
               (int (or (:life-ticks marker) 15))
               (not (false? (:follow-target? marker)))
               (not (false? (:ignore-depth? marker)))
               (not (false? (:available? marker)))
               ""  ; renderer id
               (name (or (:hook marker) :none)))]
    (FabricScriptedEntityAccess/registerScriptedMarkerSpec
      (str registry-name)
      spec))
  nil)

(defn- register-scripted-block-body-spec!
  [registry-name entity-spec]
  (let [block-body (get-in entity-spec [:properties :block-body])
        spec (ScriptedBlockBodySpec.
               (str (or (:default-block-id block-body) ""))
               (double (or (:gravity block-body) 0.05))
               (double (or (:damage block-body) 0.0))
               (not (false? (:place-when-collide? block-body)))
               ""  ; renderer id
               (name (or (:hook block-body) :none)))]
    (FabricScriptedEntityAccess/registerScriptedBlockBodySpec
      (str registry-name)
      spec))
  nil)

(defn- register-all-entities!
  "Register all entities declared in entity DSL."
  []
  (doseq [entity-id (edsl/list-entities)]
    (let [entity-spec (edsl/get-entity entity-id)
          registry-name (edsl/get-entity-registry-name entity-id)
          entity-kind (:entity-kind entity-spec)]
      (if (nil? entity-kind)
        (log/error "Skipping entity registration: missing :entity-kind" {:entity-id entity-id})
        (case entity-kind
          :scripted-projectile (register-scripted-projectile-spec! registry-name entity-spec)
          :scripted-effect (register-scripted-effect-spec! registry-name entity-spec)
          :scripted-ray (register-scripted-ray-spec! registry-name entity-spec)
          :scripted-marker (register-scripted-marker-spec! registry-name entity-spec)
          :scripted-block-body (register-scripted-block-body-spec! registry-name entity-spec)
          nil)))))

(defn mod-init
  "Main mod initialization called from Java ModInitializer."
  []
  (log/info "Initializing MyMod (Fabric 1.20.1) from Clojure...")
  (platform-bootstrap/init-platform!)
  (init/init-from-java)
  (core/init)
  (config-bridge/load-all!)
  (bsp/init-all-properties!)
  (register-scripted-tile-hooks!)
  (register-all-blocks!)
  (register-block-entities!)
  (register-all-items!)
  (register-all-entities!)
  (effect-hooks/register-all-effect-hooks!)
  (ray-hooks/register-all-ray-hooks!)
  (marker-hooks/register-all-marker-hooks!)
  (runtime-damage-interception/install-damage-interception!)
  (runtime-item-handler/init!)
  (runtime-player-motion/install-player-motion!)
  (runtime-entity-damage/install-entity-damage!)
  (runtime-entity-motion/install-entity-motion!)
  (runtime-entity-query/install-entity-query!)
  (runtime-raycast/install-raycast!)
  (runtime-world-effects/install-world-effects!)
  (runtime-teleportation/install-teleportation!)
  (runtime-saved-locations/install-saved-locations!)
  (runtime-potion-effects/install-potion-effects!)
  (runtime-interop/install-runtime-interop!)
  (runtime-block-manipulation/install-block-manipulation!)
  (runtime-network/init!)
  (power-runtime/init-damage-handlers!)
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
  (let [tile-id (or (when (contains? @registered-block-entities tile-or-block-id)
                      tile-or-block-id)
                    (registry-metadata/get-block-tile-id tile-or-block-id))]
    (get @registered-block-entities tile-id)))

(defn get-registered-block-item
  "Get a registered block item by its block ID."
  [block-id]
  (get @registered-items (str block-id "-item")))
