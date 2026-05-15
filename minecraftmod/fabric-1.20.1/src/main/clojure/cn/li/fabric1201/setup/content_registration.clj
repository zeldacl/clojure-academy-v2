(ns cn.li.fabric1201.setup.content-registration
  "Fabric content registration extracted from mod entry namespace."
  (:require [cn.li.fabric1201.registry.fabric-dispatch :as fabric-dispatch]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.entity.dsl :as edsl]
            [cn.li.mcmod.protocol.metadata :as registry-metadata]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.fabric1201.entity FabricScriptedEntityAccess]
           [cn.li.fabric1201.shim FabricBootstrapHelper]
           [cn.li.mc1201.entity.spec ScriptedProjectileSpec ScriptedEffectSpec ScriptedRaySpec ScriptedMarkerSpec ScriptedBlockBodySpec]
           [net.minecraft.world.item Item Item$Properties]))

(defn- metadata-call
  [var-sym & args]
  (let [resolved (requiring-resolve var-sym)]
    (cond
      (and resolved (bound? resolved))
      (apply resolved args)

      :else
      (do
        (require 'cn.li.mcmod.protocol.metadata :reload)
        (let [resolved2 (requiring-resolve var-sym)]
          (when (and resolved2 (bound? resolved2))
            (apply resolved2 args)))))))

(defn- has-block-state-properties?
  [block-id]
  (boolean (metadata-call 'cn.li.mcmod.protocol.metadata/has-block-state-properties? block-id)))

(defn register-scripted-tile-hooks!
  []
  (doseq [tile-id (or (metadata-call 'cn.li.mcmod.protocol.metadata/get-all-tile-ids) [])]
    (when-let [spec (metadata-call 'cn.li.mcmod.protocol.metadata/get-tile-spec tile-id)]
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
  [{:keys [registered-blocks base-properties carrier-properties]}]
  (let [get-props (requiring-resolve 'cn.li.mc1201.block.blockstate-properties/get-all-properties)]
    (doseq [block-id (or (metadata-call 'cn.li.mcmod.protocol.metadata/get-all-block-ids) [])]
      (let [registry-name (metadata-call 'cn.li.mcmod.protocol.metadata/get-block-registry-name block-id)
            fluid-id (metadata-call 'cn.li.mcmod.protocol.metadata/get-fluid-id-for-block block-id)
            needs-dynamic-properties? (has-block-state-properties? block-id)
            has-be? (boolean (metadata-call 'cn.li.mcmod.protocol.metadata/has-block-entity? block-id))
            tile-id (when has-be?
                      (or (metadata-call 'cn.li.mcmod.protocol.metadata/get-block-tile-id block-id) block-id))
            block-inst (cond
                         (and fluid-id (not (metadata-call 'cn.li.mcmod.protocol.metadata/fluid-block? block-id)))
                         (FabricBootstrapHelper/createPlainBlock base-properties)

                         fluid-id
                         (do
                           (log/warn "Fabric block registration: fluid-backed block falls back to plain block until fluid chain is wired" {:block-id block-id :fluid-id fluid-id})
                           (FabricBootstrapHelper/createPlainBlock base-properties))

                         (and needs-dynamic-properties? has-be?)
                         (let [props (get-props block-id)]
                           (FabricBootstrapHelper/createCarrierScriptedDynamicBlock block-id tile-id props carrier-properties))

                         needs-dynamic-properties?
                         (let [props (get-props block-id)]
                           (FabricBootstrapHelper/createDynamicStateBlock block-id props base-properties))

                         has-be?
                         (FabricBootstrapHelper/createCarrierScriptedBlock block-id tile-id carrier-properties)

                         :else
                         (FabricBootstrapHelper/createPlainBlock base-properties))
            registered (fabric-dispatch/register-block registry-name block-inst)]
        (swap! registered-blocks assoc block-id registered)))))

(defn register-block-entities!
  [{:keys [mod-id registered-blocks registered-block-entities]}]
  (doseq [tile-id (or (metadata-call 'cn.li.mcmod.protocol.metadata/get-all-tile-ids) [])]
    (let [registry-name (metadata-call 'cn.li.mcmod.protocol.metadata/get-tile-registry-name tile-id)
          block-ids (metadata-call 'cn.li.mcmod.protocol.metadata/get-tile-block-ids tile-id)
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
  [{:keys [registered-items registered-blocks]}]
  (doseq [item-id (or (metadata-call 'cn.li.mcmod.protocol.metadata/get-all-item-ids) [])]
    (let [registry-name (metadata-call 'cn.li.mcmod.protocol.metadata/get-item-registry-name item-id)
          item-spec (metadata-call 'cn.li.mcmod.protocol.metadata/get-item-spec item-id)
          item-inst (create-standalone-item item-spec)
          registered (fabric-dispatch/register-item registry-name item-inst)]
      (swap! registered-items assoc item-id registered)))

  (doseq [block-id (or (metadata-call 'cn.li.mcmod.protocol.metadata/get-all-block-ids) [])]
    (when (and (metadata-call 'cn.li.mcmod.protocol.metadata/should-create-block-item? block-id)
               (not (metadata-call 'cn.li.mcmod.protocol.metadata/fluid-block? block-id)))
      (when-let [block-inst (get @registered-blocks block-id)]
        (let [registry-name (metadata-call 'cn.li.mcmod.protocol.metadata/get-block-registry-name block-id)
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
                   (edsl/resolve-render-profile-key entity-spec :effect "effect-billboard")
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
        (edsl/resolve-render-profile-key entity-spec :ray "ray-composite")
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
               (edsl/resolve-render-profile-key entity-spec :marker "marker-billboard")
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
               (edsl/resolve-render-profile-key entity-spec :block-body "block-body")
               (name (or (:hook block-body) :none)))]
    (FabricScriptedEntityAccess/registerScriptedBlockBodySpec
      (str registry-name)
      spec))
  nil)

(defn register-all-entities!
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

(defn register-content!
  [{:keys [mod-id] :as ctx}]
  (register-scripted-tile-hooks!)
  (register-all-blocks! ctx)
  (register-block-entities! (assoc ctx :mod-id mod-id))
  (register-all-items! ctx)
  (register-all-entities!)
  nil)
