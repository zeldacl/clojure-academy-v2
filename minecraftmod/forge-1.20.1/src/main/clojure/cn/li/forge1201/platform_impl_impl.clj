
(ns cn.li.forge1201.platform-impl-impl
  "Bridge-invoked platform initializer.

  Keeps business logic free of hardcoded Minecraft types except minimal `^Type` hints on
  `extend` lambdas so checkClojure stays reflection-clean (types are Forge 1.20.1 remapped)."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.platform.nbt :as nbt]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.item :as item]
            [cn.li.mcmod.platform.resource :as resource]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.platform.be :as be]
            [cn.li.forge1201.platform-bindings :as bindings]
            [cn.li.forge1201.side :as side])
  (:import [cn.li.forge1201.bridge ForgeRuntimeBridge]
           [net.minecraft.world InteractionHand]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.world.entity Entity]
           [net.minecraft.world.entity.player Player]
           [net.minecraft.world.item Item ItemStack]))


(defonce ^:private initialized? (atom false))
(defonce ^:private entity-ops-installed? (atom false))
(defonce ^:private item-ops-installed? (atom false))
(defonce ^:private block-state-ops-installed? (atom false))

(defn- install-item-ops!
  []
  (when (compare-and-set! item-ops-installed? false true)
    (let [item-stack-cls (ForgeRuntimeBridge/getItemStackClass)
          item-cls (ForgeRuntimeBridge/getItemClass)]
      (extend item-stack-cls item/IItemStack
              {:item-is-empty? (fn [^ItemStack this]
                                 (.isEmpty this))
               :item-get-count (fn [^ItemStack this]
                                 (.getCount this))
               :item-get-max-stack-size (fn [^ItemStack this]
                                          (.getMaxStackSize this))
               :item-is-equal? (fn [^ItemStack this ^ItemStack other]
                                 (ItemStack/matches this other))
               :item-save-to-nbt (fn [^ItemStack this nbt]
                                   (.save this nbt))
               :item-get-or-create-tag (fn [^ItemStack this]
                                         (.getOrCreateTag this))
               :item-get-max-damage (fn [^ItemStack this]
                                      (.getMaxDamage this))
               :item-set-damage! (fn [^ItemStack this damage]
                                   (.setDamageValue this (int damage)))
               :item-get-damage (fn [^ItemStack this]
                                  (.getDamageValue this))
               :item-get-item (fn [^ItemStack this]
                                (.getItem this))
               :item-get-tag-compound (fn [^ItemStack this]
                                        (.getTag this))
               :item-split (fn [^ItemStack this amount]
                             (.split this (int amount)))})
      (extend item-cls item/IItem
              {:item-get-description-id (fn [^Item this]
                                          (.getDescriptionId this))
               :item-get-registry-name (fn [^Item this]
                 (ForgeRuntimeBridge/getItemKeyString this))}))))

(defn- install-block-state-ops!
  "Extend IBlockStateOps onto BlockState at runtime (after MC bootstrap).

  Do not use extend-type BlockState in platform-bindings: CheckClojure loads that
  namespace and would initialize BlockState before bootstrap."
  []
  (when (compare-and-set! block-state-ops-installed? false true)
    (let [block-state-cls (ForgeRuntimeBridge/getBlockStateClass)]
      (extend block-state-cls world/IBlockStateOps
              {:block-state-is-air (fn [s] (ForgeRuntimeBridge/blockStateIsAir s))
               :block-state-get-block (fn [s] (ForgeRuntimeBridge/blockStateGetBlock s))
               :block-state-get-state-definition (fn [s] (ForgeRuntimeBridge/blockStateGetStateDefinition s))
               :block-state-get-property (fn [_s state-def prop-name]
                                           (ForgeRuntimeBridge/blockStateGetProperty state-def prop-name))
               :block-state-set-property (fn [s prop value]
                                           (ForgeRuntimeBridge/blockStateSetProperty s prop value))}))))

(defn- install-entity-ops!
  []
  (when (compare-and-set! entity-ops-installed? false true)
    (let [entity-cls (ForgeRuntimeBridge/getEntityClass)
          player-cls (ForgeRuntimeBridge/getPlayerClass)
          inventory-cls (ForgeRuntimeBridge/getInventoryClass)
          menu-cls (ForgeRuntimeBridge/getAbstractContainerMenuClass)
          server-player-cls (ForgeRuntimeBridge/getServerPlayerClass)
          player-impl {:entity-distance-to-sqr (fn [^Entity this x y z]
                                                 (.distanceToSqr this (double x) (double y) (double z)))
                       :player-get-level (fn [^Player this]
                                           (ForgeRuntimeBridge/getEntityLevel this))
                       :player-creative? (fn [^Player this]
                                           (.isCreative this))
                       :player-spectator? (fn [^Player this]
                                            (.isSpectator this))
                       :player-get-name (fn [^Player this]
                                          (str (.getName this)))
                       :player-get-uuid (fn [^Player this]
                                          (.getUUID this))
                       :player-get-main-hand-item-id (fn [^Player this]
                                                       (let [^ItemStack stack (.getItemInHand this InteractionHand/MAIN_HAND)]
                                                         (when (and stack (not (.isEmpty stack)))
                                                           (ForgeRuntimeBridge/getItemKeyString (.getItem stack)))))
                       :player-get-main-hand-item-count (fn [^Player this]
                                                          (let [^ItemStack stack (.getItemInHand this InteractionHand/MAIN_HAND)]
                                                            (if (and stack (not (.isEmpty stack)))
                                                              (.getCount stack)
                                                              0)))
                       :player-consume-main-hand-item! (fn [^Player this amount]
                                                         (let [n (int (max 0 (or amount 0)))]
                                                           (cond
                                                             (zero? n) true
                                                             (.isCreative this) true
                                                             :else
                                                             (let [^ItemStack stack (.getItemInHand this InteractionHand/MAIN_HAND)]
                                                               (if (or (nil? stack) (.isEmpty stack) (< (.getCount stack) n))
                                                                 false
                                                                 (do
                                                                   (.shrink stack n)
                                                                   true))))))
                       :player-get-container-menu (fn [^Player this]
                                                    (ForgeRuntimeBridge/getPlayerContainerMenu this))}]
      (extend entity-cls entity/IEntityOps
        {:entity-distance-to-sqr (fn [^Entity this x y z]
           (.distanceToSqr this (double x) (double y) (double z)))})
      (extend player-cls entity/IEntityOps player-impl)
      ;; Concrete classes are extended explicitly because some transformed runtime
      ;; classes do not reliably inherit protocol maps from Player in dev environment.
      (extend server-player-cls entity/IEntityOps player-impl)
      ;; LocalPlayer is client-only; ClientPlatformBridge is @OnlyIn(Dist.CLIENT).
      ;; Access it via our own (non-remapped) class name at runtime, client-side only.
      (when (side/client-side?)
        (let [local-player-cls (-> (Class/forName "cn.li.forge1201.bridge.ClientPlatformBridge")
                                   (.getMethod "getLocalPlayerClass" (into-array Class []))
                                   (.invoke nil (object-array [])))]
          (extend local-player-cls entity/IEntityOps player-impl)))
      (extend inventory-cls entity/IEntityOps
        {:inventory-get-player (fn [this]
               (ForgeRuntimeBridge/getInventoryPlayer this))})
      (extend menu-cls entity/IEntityOps
        {:menu-get-container-id (fn [this]
          (ForgeRuntimeBridge/getMenuContainerId this))})))) 

(defn- install-resource-factory!
  []
  (alter-var-root #'resource/*resource-factory*
                  (constantly (fn [namespace path]
                                (ResourceLocation. (str namespace) (str path))))))

(defn init-platform!
  "Initialize Forge 1.20.1 platform implementations.

  NOTE: Protocol extension migration is in progress. This function is kept as the
  stable entrypoint for Java SPI provider invocation."
  []
  (when (compare-and-set! initialized? false true)
    ;; Install entity protocol implementations with runtime class lookup only,
    ;; avoiding compile-time MC class loading during checkClojure.
    (install-entity-ops!)
    (install-item-ops!)
    (install-block-state-ops!)
    (install-resource-factory!)
    (alter-var-root #'nbt/*nbt-factory*
      (constantly {:create-compound bindings/create-nbt-compound
                   :create-list bindings/create-nbt-list}))
    (alter-var-root #'item/*item-factory*
      (constantly (fn [nbt]
                    (ForgeRuntimeBridge/itemStackOf nbt))))
    (alter-var-root #'item/*item-stack-resolver*
      (constantly (fn [item-id count]
                    (let [stack (ForgeRuntimeBridge/createItemStackById (str item-id) (int count))]
                      (when-not (ForgeRuntimeBridge/isItemStackEmpty stack)
                        stack)))))
    (alter-var-root #'nbt/*nbt-has-key-fn* (constantly bindings/nbt-has-key?))
    (alter-var-root #'pos/*position-factory* (constantly bindings/create-block-pos))
    (alter-var-root #'pos/*pos-above-fn* (constantly bindings/pos-above))
    (alter-var-root #'world/*world-get-tile-entity-fn* (constantly bindings/world-get-tile-entity))
    (alter-var-root #'world/*world-get-block-state-fn* (constantly bindings/world-get-block-state))
    (alter-var-root #'world/*world-set-block-fn* (constantly bindings/world-set-block))
    (alter-var-root #'world/*world-remove-block-fn* (constantly bindings/world-remove-block))
    (alter-var-root #'world/*world-break-block-fn* (constantly bindings/world-break-block))
    (alter-var-root #'world/*world-place-block-by-id-fn* (constantly bindings/world-place-block-by-id))
    (alter-var-root #'world/*world-is-chunk-loaded-fn* (constantly bindings/world-is-chunk-loaded?))
    (alter-var-root #'world/*world-get-day-time-fn* (constantly bindings/world-get-day-time))
    (alter-var-root #'world/*world-get-dimension-id-fn* (constantly bindings/world-get-dimension-id))
    (alter-var-root #'world/*world-get-players-fn* (constantly bindings/world-get-players))
    (alter-var-root #'world/*world-is-raining-fn* (constantly bindings/world-is-raining))
    (alter-var-root #'world/*world-is-client-side-fn* (constantly bindings/world-is-client-side))
    (alter-var-root #'world/*world-can-see-sky-fn* (constantly bindings/world-can-see-sky))
    (alter-var-root #'be/*be-get-level-fn* (constantly bindings/be-get-level))
    (alter-var-root #'be/*be-get-world-fn* (constantly bindings/be-get-world))
    (alter-var-root #'be/*be-get-custom-state-fn* (constantly bindings/be-get-custom-state))
    (alter-var-root #'be/*be-set-custom-state-fn* (constantly bindings/be-set-custom-state!))
    (alter-var-root #'be/*be-get-block-id-fn* (constantly bindings/be-get-block-id))
    (alter-var-root #'be/*be-set-changed-fn* (constantly bindings/be-set-changed!))
    (log/info "platform-impl-impl initialized via SPI entrypoint"))
  nil)