(ns cn.li.fabric1201.mod
  "Fabric 1.20.1 mod placeholder entry.

  Compile-unblocking stub that avoids touching Minecraft registries during AOT."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.fabric1201.init :as init]
            [cn.li.ac.core :as core]
            [cn.li.fabric1201.platform.bootstrap-entry :as platform-bootstrap]
            [cn.li.fabric1201.blockstate-properties :as bsp]
            [cn.li.fabric1201.integration.events :as events]
            [cn.li.fabric1201.runtime.damage-interception :as runtime-damage-interception]
            [cn.li.fabric1201.runtime.item-handler :as runtime-item-handler]
            [cn.li.fabric1201.gui.init :as gui-init]
            [cn.li.fabric1201.config.bridge :as config-bridge]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.entity.dsl :as edsl])
  (:import [cn.li.fabric1201.entity FabricScriptedEntityAccess]
           [cn.li.mc1201.entity.spec ScriptedProjectileSpec ScriptedEffectSpec ScriptedRaySpec ScriptedMarkerSpec ScriptedBlockBodySpec]))

(def mod-id modid/MOD-ID)

(defonce registered-blocks (atom {}))
(defonce registered-items (atom {}))
(defonce registered-block-entities (atom {}))

(defn- register-scripted-projectile-spec!
  [registry-name entity-spec]
  (let [projectile (get-in entity-spec [:properties :projectile])
        hooks (:hooks projectile)]
    (let [spec (ScriptedProjectileSpec.
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
        spec)))
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
    (letfn [(normalize-hook-params [params]
              (into {}
                    (map (fn [[k v]]
                           [(cond
                              (keyword? k) (name k)
                              (string? k) k
                              :else (str k))
                            v]))
                    (or params {})))]
      (let [spec (ScriptedRaySpec.
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
                   (name (or (:hook ray) :none)))]
        (FabricScriptedEntityAccess/registerScriptedRaySpec
          (str registry-name)
          spec))))
  nil)

(defn- register-scripted-marker-spec!
  [registry-name entity-spec]
  (let [marker (get-in entity-spec [:properties :marker])]
    (let [spec (ScriptedMarkerSpec.
                 (int (or (:life-ticks marker) 15))
                 (not (false? (:follow-target? marker)))
                 (not (false? (:ignore-depth? marker)))
                 (not (false? (:available? marker)))
                 ""  ; renderer id
                 (name (or (:hook marker) :none)))]
      (FabricScriptedEntityAccess/registerScriptedMarkerSpec
        (str registry-name)
        spec)))
  nil)

(defn- register-scripted-block-body-spec!
  [registry-name entity-spec]
  (let [block-body (get-in entity-spec [:properties :block-body])]
    (let [spec (ScriptedBlockBodySpec.
                 (str (or (:default-block-id block-body) ""))
                 (double (or (:gravity block-body) 0.05))
                 (double (or (:damage block-body) 0.0))
                 (not (false? (:place-when-collide? block-body)))
                 ""  ; renderer id
                 (name (or (:hook block-body) :none)))]
      (FabricScriptedEntityAccess/registerScriptedBlockBodySpec
        (str registry-name)
        spec)))
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
  (register-all-entities!)
  (runtime-damage-interception/install-damage-interception!)
  (runtime-item-handler/init!)
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
