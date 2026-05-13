(ns cn.li.forge1201.registry.entities
  "Entity registration for Forge 1.20.1."
  (:require [cn.li.forge1201.integration.bootstrap :as bootstrap]
            [cn.li.forge1201.registry.state :as registry-state]
            [cn.li.mcmod.entity.dsl :as edsl]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.forge1201.entity ModEntities]))

(defn- register-scripted-projectile-spec!
  [registry-name entity-spec]
  (let [projectile (get-in entity-spec [:properties :projectile])
        hooks (:hooks projectile)]
    (ModEntities/registerScriptedProjectileSpec
      (str registry-name)
      (str (or (:default-item-id projectile) ""))
      (double (or (:gravity projectile) 0.05))
      (double (or (:damage projectile) 0.0))
      (double (or (:pickup-distance-sqr projectile) 2.25))
      (not (false? (:drop-item-on-discard? projectile)))
      (name (or (:on-hit-block hooks) :none))
      (name (or (:on-hit-entity hooks) :none))
      (name (or (:on-anchored-tick hooks) :none))
      (name (or (:on-anchored-hurt hooks) :none))))
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
      (ModEntities/registerScriptedEffectSpec
        (str registry-name)
        (int (or (:life-ticks effect) 15))
        (not (false? (:follow-owner? effect)))
        (str (or (:renderer-id effect) "effect-billboard"))
        (name (or (:hook effect) :none))
        (normalize-hook-params (:hook-params effect)))))
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
      (ModEntities/registerScriptedRaySpec
        (str registry-name)
        (int (or (:life-ticks ray) 30))
        (double (or (:length ray) 15.0))
        (double (or (:blend-in-ms ray) 100.0))
        (double (or (:blend-out-ms ray) 300.0))
        (double (or (:inner-width ray) 0.03))
        (double (or (:outer-width ray) 0.045))
        (double (or (:glow-width ray) 0.3))
        (int (or (:start-color ray) 0x78DCFF))
        (int (or (:end-color ray) 0x32AAFF))
        (str (or (:renderer-id ray) "ray-composite"))
        (name (or (:hook ray) :none))
        (normalize-hook-params (:hook-params ray)))))
  nil)

(defn- register-scripted-marker-spec!
  [registry-name entity-spec]
  (let [marker (get-in entity-spec [:properties :marker])]
    (ModEntities/registerScriptedMarkerSpec
      (str registry-name)
      (int (or (:life-ticks marker) 40))
      (not (false? (:follow-target? marker)))
      (not (false? (:ignore-depth? marker)))
      (not (false? (:available? marker)))
      (str (or (:renderer-id marker) "marker-billboard"))
      (name (or (:hook marker) :none))))
  nil)

(defn- register-scripted-block-body-spec!
  [registry-name entity-spec]
  (let [block-body (get-in entity-spec [:properties :block-body])]
    (ModEntities/registerScriptedBlockBodySpec
      (str registry-name)
      (str (or (:default-block-id block-body) "minecraft:stone"))
      (double (or (:gravity block-body) 0.05))
      (double (or (:damage block-body) 0.0))
      (not (false? (:place-when-collide? block-body)))
      (str (or (:renderer-id block-body) "block-body"))
      (name (or (:hook block-body) :none))))
  nil)

(defn register-all-entities!
  [mod-id]
  (doseq [entity-id (edsl/list-entities)]
    (let [entity-spec (edsl/get-entity entity-id)
          registry-name (edsl/get-entity-registry-name entity-id)
          entity-kind (:entity-kind entity-spec)]
      (if (nil? entity-kind)
        (log/error "Skipping entity registration: missing :entity-kind" {:entity-id entity-id})
        (let [_ (case entity-kind
                  :scripted-projectile (register-scripted-projectile-spec! registry-name entity-spec)
                  :scripted-effect (register-scripted-effect-spec! registry-name entity-spec)
                  :scripted-ray (register-scripted-ray-spec! registry-name entity-spec)
                  :scripted-marker (register-scripted-marker-spec! registry-name entity-spec)
                  :scripted-block-body (register-scripted-block-body-spec! registry-name entity-spec)
                  nil)
              registered-obj (ModEntities/register
                               registry-name
                               (reify java.util.function.Supplier
                                 (get [_]
                                   (bootstrap/create-entity-type-by-kind
                                     (str mod-id ":" registry-name)
                                     (name entity-kind)
                                     (name (or (:category entity-spec) :misc))
                                     (:width entity-spec)
                                     (:height entity-spec)
                                     (:client-tracking-range entity-spec)
                                     (:update-interval entity-spec)
                                     (:fire-immune? entity-spec)))))]
          (swap! registry-state/registered-entities assoc entity-id registered-obj))))))
