(ns cn.li.fabric1201.setup.content-registration
  "Fabric content registration extracted from mod entry namespace."
  (:require [cn.li.fabric1201.registry.fabric-dispatch :as fabric-dispatch]
            [cn.li.mc1201.block.logic-pipeline :as logic-pipeline]
            [cn.li.mc1201.entity.mob-logic-pipeline :as mob-pipeline]
            [cn.li.mcmod.entity.dsl :as edsl]
            [cn.li.mcmod.protocol.core :as registry-core]
            [cn.li.mcmod.protocol.metadata :as metadata]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mc1201.block.blockstate-properties :as bsp])
  (:import [cn.li.fabric1201.entity FabricScriptedEntityAccess]
           [cn.li.fabric1201.shim FabricBootstrapHelper]
           [cn.li.mc1201.block IScriptedBlock]
           [cn.li.mc1201.entity.spec ScriptedProjectileSpec ScriptedEffectSpec ScriptedRaySpec ScriptedMarkerSpec ScriptedBlockBodySpec]
           [net.minecraft.world.item Item Item$Properties]))

(defn- metadata-call
  "Call metadata function `f` with `args`. Returns nil if `f` is nil."
  [f & args]
  (when f
    (apply f args)))

(defn- has-block-state-properties?
  [block-id]
  (boolean (metadata-call metadata/has-block-state-properties? block-id)))

(defn- install-bundle-on-block!
  [block tile-id bundles]
  (when (and block tile-id bundles)
    (when-let [bundle (get bundles tile-id)]
      (when (instance? IScriptedBlock block)
        (logic-pipeline/install-bundle-to-block! block bundle)))))

(defn register-all-blocks!
  [{:keys [registered-blocks base-properties carrier-properties]}]
  (let [bundles (logic-pipeline/compile-all-bundles)]
    (doseq [block-id (or (metadata-call metadata/get-all-block-ids) [])]
      (let [registry-name (metadata-call metadata/get-block-registry-name block-id)
            fluid-id (metadata-call metadata/get-fluid-id-for-block block-id)
            needs-dynamic-properties? (has-block-state-properties? block-id)
            has-be? (boolean (metadata-call metadata/has-block-entity? block-id))
            tile-id (when has-be?
                      (metadata-call metadata/get-block-tile-id block-id))
            block-inst (cond
                         (and fluid-id (not (metadata-call metadata/fluid-block? block-id)))
                         (FabricBootstrapHelper/createPlainBlock base-properties)

                         fluid-id
                         (throw (ex-info "Fabric fluid-backed block registration not wired"
                                         {:block-id block-id
                                          :fluid-id fluid-id}))

                         (and needs-dynamic-properties? has-be?)
                         (let [props (bsp/get-all-properties block-id)]
                           (FabricBootstrapHelper/createCarrierScriptedDynamicBlock block-id tile-id props carrier-properties))

                         needs-dynamic-properties?
                         (let [props (bsp/get-all-properties block-id)]
                           (FabricBootstrapHelper/createDynamicStateBlock block-id props base-properties))

                         has-be?
                         (FabricBootstrapHelper/createCarrierScriptedBlock block-id tile-id carrier-properties)

                         :else
                         (FabricBootstrapHelper/createPlainBlock base-properties))
            _ (install-bundle-on-block! block-inst tile-id bundles)
            registered (fabric-dispatch/register-block registry-name block-inst)]
        (registry-core/swap-state! registered-blocks #(assoc % block-id registered))))))

(defn assert-scripted-blocks-bundled!
  [{:keys [registered-blocks]}]
  (let [block-ids (or (metadata-call metadata/get-all-block-ids) [])
        blocks (keep #(registry-core/lookup registered-blocks %) block-ids)
        scripted (filter #(instance? IScriptedBlock %) blocks)]
    (logic-pipeline/assert-all-blocks-have-bundle! scripted #{}))
  nil)

(defn register-block-entities!
  [{:keys [mod-id registered-blocks registered-block-entities]}]
  (doseq [tile-id (or (metadata-call metadata/get-all-tile-ids) [])]
    (let [registry-name (metadata-call metadata/get-tile-registry-name tile-id)
          block-ids (metadata-call metadata/get-tile-block-ids tile-id)
          blocks (keep #(registry-core/lookup registered-blocks %) block-ids)]
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
            (registry-core/swap-state! registered-block-entities #(assoc % tile-id registered))))))))

(defn- create-standalone-item
  [_item-spec]
  (Item. (Item$Properties.)))

(defn register-all-items!
  [{:keys [registered-items registered-blocks]}]
  (doseq [item-id (or (metadata-call metadata/get-all-item-ids) [])]
    (let [registry-name (metadata-call metadata/get-item-registry-name item-id)
          item-spec (metadata-call metadata/get-item-spec item-id)
          item-inst (create-standalone-item item-spec)
          registered (fabric-dispatch/register-item registry-name item-inst)]
      (registry-core/swap-state! registered-items #(assoc % item-id registered))))

  (doseq [block-id (or (metadata-call metadata/get-all-block-ids) [])]
    (when (and (metadata-call metadata/should-create-block-item? block-id)
               (not (metadata-call metadata/fluid-block? block-id)))
      (when-let [block-inst (registry-core/lookup registered-blocks block-id)]
        (let [registry-name (metadata-call metadata/get-block-registry-name block-id)
              block-item (FabricBootstrapHelper/createBlockItem block-inst)
              registered (fabric-dispatch/register-item registry-name block-item)]
          (registry-core/swap-state! registered-items #(assoc % (str block-id "-item") registered)))))))

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
          :scripted-mob (do (mob-pipeline/compile-all-mob-bundles) nil)
          nil)))))

(defn register-all-particles!
  []
  (doseq [particle-id (or (metadata-call metadata/get-all-particle-ids) [])]
    (let [registry-name (metadata-call metadata/get-particle-registry-name particle-id)
          particle-spec (metadata-call metadata/get-particle-spec particle-id)
          always-show? (boolean (:always-show? particle-spec))]
      (fabric-dispatch/register-particle registry-name always-show?))))

(defn register-content!
  [{:keys [mod-id] :as ctx}]
  (register-all-blocks! ctx)
  (assert-scripted-blocks-bundled! ctx)
  (register-block-entities! (assoc ctx :mod-id mod-id))
  (register-all-items! ctx)
  (register-all-entities!)
  (register-all-particles!)
  nil)
