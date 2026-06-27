(ns cn.li.forge1201.registry.content-registration
  "Forge content registration extracted from the mod entrypoint so loader bootstrap
  stays focused on lifecycle coordination."
  (:require [cn.li.forge1201.integration.bootstrap :as bootstrap]
            [cn.li.forge1201.registry.item-properties :as item-properties]
            [cn.li.forge1201.registry.state :as registry-state]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.entity.dsl :as edsl]
            [cn.li.mcmod.protocol.metadata :as registry-metadata]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mc1201.block.blockstate-properties :as blockstate-props])
  (:import [cn.li.forge1201.entity ModEntities]
           [cn.li.mc1201.effect ScriptedMobEffect]
           [net.minecraft.core.particles SimpleParticleType]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.sounds SoundEvent]
           [net.minecraft.world.effect MobEffectCategory]
           [net.minecraft.world.item BlockItem Item$Properties]
           [net.minecraftforge.registries DeferredRegister RegistryObject]))

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

(defn- has-block-state-properties?
  [block-id]
  (registry-metadata/has-block-state-properties? block-id))

(defn- registry-source-snapshot
  [source]
  (cond
    (map? source) source
    (fn? source) (or (source) {})
    (instance? clojure.lang.IDeref source) (or @source {})
    :else {}))

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
  [{:keys [blocks-register registered-fluids-source base-properties carrier-properties]}]
  (doseq [block-id (registry-metadata/get-all-block-ids)]
    (let [registry-name (registry-metadata/get-block-registry-name block-id)
          fluid-id (registry-metadata/get-fluid-id-for-block block-id)
          needs-dynamic-properties? (has-block-state-properties? block-id)
          has-be? (registry-metadata/has-block-entity? block-id)
          tile-id (when has-be?
                    (registry-metadata/get-block-tile-id block-id))
          registered-obj (.register ^DeferredRegister blocks-register registry-name
                                    (reify java.util.function.Supplier
                                      (get [_]
                                        (cond
                                            (and fluid-id has-be?)
                                            (when-let [fluid-source-ro (get (registry-source-snapshot registered-fluids-source) fluid-id)]
                                              (bootstrap/create-scripted-liquid-block
                                                (reify java.util.function.Supplier
                                                  (get [_]
                                                    (.get ^RegistryObject fluid-source-ro)))
                                                block-id
                                                tile-id))
                                            fluid-id
                                            (when-let [fluid-source-ro (get (registry-source-snapshot registered-fluids-source) fluid-id)]
                                              (bootstrap/create-liquid-block
                                                (reify java.util.function.Supplier
                                                  (get [_]
                                                    (.get ^RegistryObject fluid-source-ro)))))
                                            (and needs-dynamic-properties? has-be?)
                                            (let [props (blockstate-props/get-all-properties block-id)]
                                              (bootstrap/create-carrier-scripted-dynamic-block block-id tile-id props base-properties))
                                            needs-dynamic-properties?
                                            (let [props (blockstate-props/get-all-properties block-id)]
                                              (bootstrap/create-dynamic-state-block block-id props base-properties))
                                            has-be?
                                            (bootstrap/create-carrier-scripted-block block-id tile-id carrier-properties)
                                            :else
                                            (bootstrap/create-plain-block base-properties)))))]
      (registry-state/register-block! block-id registered-obj))))

(defn register-all-fluids!
  [{:keys [fluid-types-register fluids-register items-register]}]
  (doseq [fluid-id (registry-metadata/get-all-fluid-ids)]
    (let [fluid-spec (registry-metadata/get-fluid-spec fluid-id)
          physical (:physical fluid-spec)
          rendering (:rendering fluid-spec)
          behavior (:behavior fluid-spec)
          block-spec (:block fluid-spec)
          registry-name (registry-metadata/get-fluid-registry-name fluid-id)
          flowing-name (str registry-name "_flowing")
          fluid-type-ro (.register ^DeferredRegister fluid-types-register registry-name
                                   (reify java.util.function.Supplier
                                     (get [_]
                                       (bootstrap/create-fluid-type
                                         (:luminosity physical)
                                         (:density physical)
                                         (:viscosity physical)
                                         (:temperature physical)
                                         false
                                         (:supports-boat physical)
                                         (:still-texture rendering)
                                         (:flowing-texture rendering)
                                         (:overlay-texture rendering)
                                         (:tint-color rendering)))))
          source-holder (atom nil)
          flowing-holder (atom nil)
          bucket-holder (atom nil)
          source-ro (.register ^DeferredRegister fluids-register registry-name
                               (reify java.util.function.Supplier
                                 (get [_]
                                   (bootstrap/create-source-fluid
                                     (bootstrap/create-flowing-fluid-properties
                                       (reify java.util.function.Supplier
                                         (get [_] (.get ^RegistryObject fluid-type-ro)))
                                       (reify java.util.function.Supplier
                                         (get [_] (.get ^RegistryObject @source-holder)))
                                       (reify java.util.function.Supplier
                                         (get [_] (.get ^RegistryObject @flowing-holder)))
                                       (when (:has-bucket? block-spec)
                                         (reify java.util.function.Supplier
                                           (get [_] (.get ^RegistryObject @bucket-holder))))
                                       (when-let [block-id (:block-id block-spec)]
                                         (reify java.util.function.Supplier
                                           (get [_]
                                             (.get ^RegistryObject (registry-state/get-registered-block-ro block-id)))))
                                       (:slope-find-distance behavior)
                                       (:level-decrease-per-block behavior)
                                       (:tick-rate behavior)
                                       (:explosion-resistance behavior)
                                       (:can-convert-to-source physical))))))
          flowing-ro (.register ^DeferredRegister fluids-register flowing-name
                                (reify java.util.function.Supplier
                                  (get [_]
                                    (bootstrap/create-flowing-fluid
                                      (bootstrap/create-flowing-fluid-properties
                                        (reify java.util.function.Supplier
                                          (get [_] (.get ^RegistryObject fluid-type-ro)))
                                        (reify java.util.function.Supplier
                                          (get [_] (.get ^RegistryObject @source-holder)))
                                        (reify java.util.function.Supplier
                                          (get [_] (.get ^RegistryObject @flowing-holder)))
                                        (when (:has-bucket? block-spec)
                                          (reify java.util.function.Supplier
                                            (get [_] (.get ^RegistryObject @bucket-holder))))
                                        (when-let [block-id (:block-id block-spec)]
                                          (reify java.util.function.Supplier
                                            (get [_]
                                              (.get ^RegistryObject (registry-state/get-registered-block-ro block-id)))))
                                        (:slope-find-distance behavior)
                                        (:level-decrease-per-block behavior)
                                        (:tick-rate behavior)
                                        (:explosion-resistance behavior)
                                        (:can-convert-to-source physical))))))]
      (reset! source-holder source-ro)
      (reset! flowing-holder flowing-ro)
      (registry-state/register-fluid-type! fluid-id fluid-type-ro)
      (registry-state/register-fluid-source! fluid-id source-ro)
      (registry-state/register-fluid-flowing! fluid-id flowing-ro)
      (when (:has-bucket? block-spec)
        (let [bucket-ro (.register ^DeferredRegister items-register (:bucket-registry-name block-spec)
                                   (reify java.util.function.Supplier
                                     (get [_]
                                       (bootstrap/create-fluid-bucket
                                         (reify java.util.function.Supplier
                                           (get [_] (.get ^RegistryObject source-ro)))))))]
          (reset! bucket-holder bucket-ro)
          (registry-state/register-item! (:bucket-item-id block-spec) bucket-ro))))))

(defn register-block-entities!
  [{:keys [block-entities-register]}]
  (doseq [tile-id (registry-metadata/get-all-tile-ids)]
    (let [registry-name (registry-metadata/get-tile-registry-name tile-id)
          block-ids (registry-metadata/get-tile-block-ids tile-id)
          ros (keep (fn [block-id]
                      (when-let [ro (registry-state/get-registered-block-ro block-id)]
                        [block-id ro]))
                    block-ids)]
      (when (seq ros)
        (let [registered-obj
              (.register
                ^DeferredRegister block-entities-register
                registry-name
                (reify java.util.function.Supplier
                  (get [_]
                    (let [pairs (keep (fn [[block-id ^RegistryObject ro]]
                                        (when (.isPresent ro)
                                          [block-id (.get ro)]))
                                      ros)
                          block-insts (mapv second pairs)
                          block-id-by-inst (java.util.IdentityHashMap.)]
                      (doseq [[block-id inst] pairs]
                        (.put block-id-by-inst inst block-id))
                      (bootstrap/create-scripted-block-entity-type
                        tile-id
                        block-insts
                        (reify java.util.function.Function
                          (apply [_ block-inst]
                            (.get block-id-by-inst block-inst))))))))]
          (registry-state/register-block-entity! tile-id registered-obj))))))

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
          (registry-state/register-entity! entity-id registered-obj))))))

(defn- effect-category->forge
  ^MobEffectCategory
  [category-kw]
  (case category-kw
    :beneficial MobEffectCategory/BENEFICIAL
    :neutral MobEffectCategory/NEUTRAL
    MobEffectCategory/HARMFUL))

(defn register-all-sounds!
  [{:keys [sounds-register mod-id]}]
  (doseq [sound-id (registry-metadata/get-all-sound-ids)]
    (let [registry-name (registry-metadata/get-sound-registry-name sound-id)
          registered-obj (.register ^DeferredRegister sounds-register registry-name
                                    (reify java.util.function.Supplier
                                      (get [_]
                                        (SoundEvent/createVariableRangeEvent
                                          (ResourceLocation. mod-id registry-name)))))]
      (registry-state/register-sound! sound-id registered-obj))))

(defn register-all-effects!
  [{:keys [effects-register]}]
  (doseq [effect-id (registry-metadata/get-all-effect-ids)]
    (let [effect-spec (registry-metadata/get-effect-spec effect-id)
          registry-name (registry-metadata/get-effect-registry-name effect-id)
          category (effect-category->forge (:category effect-spec))
          color (int (or (:color effect-spec) 0xAA0000))
          tick-interval (int (or (:tick-interval effect-spec) 20))
          damage-per-tick (float (or (:damage-per-tick effect-spec) 0.0))
          registered-obj (.register ^DeferredRegister effects-register registry-name
                                    (reify java.util.function.Supplier
                                      (get [_]
                                        (ScriptedMobEffect. category color tick-interval damage-per-tick))))]
      (registry-state/register-effect! effect-id registered-obj))))

(defn register-all-particles!
  [{:keys [particle-types-register]}]
  (doseq [particle-id (registry-metadata/get-all-particle-ids)]
    (let [particle-spec (registry-metadata/get-particle-spec particle-id)
          registry-name (registry-metadata/get-particle-registry-name particle-id)
          always-show? (boolean (:always-show? particle-spec))
          registered-obj (.register ^DeferredRegister particle-types-register registry-name
                                    (reify java.util.function.Supplier
                                      (get [_]
                                        (SimpleParticleType. always-show?))))]
      (registry-state/register-particle! particle-id registered-obj))))

(defn register-all-items!
  [{:keys [items-register]}]
  (doseq [item-id (registry-metadata/get-all-item-ids)]
    (let [registry-name (registry-metadata/get-item-registry-name item-id)
          item-spec (registry-metadata/get-item-spec item-id)
          registered-obj (.register ^DeferredRegister items-register registry-name
                                    (reify java.util.function.Supplier
                                      (get [_]
                                        (item-properties/create-standalone-item item-spec))))]
      (registry-state/register-item! item-id registered-obj)))
  (doseq [block-id (registry-metadata/get-all-block-ids)]
    (when (and (registry-metadata/should-create-block-item? block-id)
               (or (not (registry-metadata/fluid-block? block-id))
                   ;; Allow BlockItem for fluid blocks that have a block entity
                   ;; (e.g. imag_phase needs item form for animated inventory icon)
                   (registry-metadata/has-block-entity? block-id)))
      (let [registry-name (registry-metadata/get-block-registry-name block-id)
            block-registered (registry-state/get-registered-block-ro block-id)
            registered-obj (.register ^DeferredRegister items-register registry-name
                                      (reify java.util.function.Supplier
                                        (get [_]
                                          (when (and block-registered (.isPresent ^RegistryObject block-registered))
                                            (BlockItem. (.get ^RegistryObject block-registered)
                                                        (Item$Properties.))))))]

        (registry-state/register-item! (str block-id "-item") registered-obj)))))

(defn register-core-content!
  [{:keys [mod-id] :as ctx}]
  (register-scripted-tile-hooks!)
  (register-all-fluids! ctx)
  (register-all-blocks! ctx)
  (register-all-entities! mod-id)
  (register-all-sounds! (assoc ctx :mod-id mod-id))
  (register-all-effects! ctx)
  (register-all-particles! ctx)
  (register-block-entities! ctx)
  (register-all-items! ctx)
  nil)
