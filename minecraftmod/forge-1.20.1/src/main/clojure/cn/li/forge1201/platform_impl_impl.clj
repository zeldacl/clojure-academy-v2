
(ns cn.li.forge1201.platform-impl-impl
  "Bridge-invoked platform initializer.

  This namespace is intentionally free of direct Minecraft/Forge imports so it can
  be AOT-compiled safely under checkClojure/compileClojure."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.platform.nbt :as nbt]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.item :as item]
            [cn.li.mcmod.platform.resource :as resource]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.platform.be :as be]
            [cn.li.forge1201.platform-bindings :as bindings]))


(defonce ^:private initialized? (atom false))
(defonce ^:private entity-ops-installed? (atom false))
(defonce ^:private item-ops-installed? (atom false))

(defn- install-item-ops!
  []
  (when (compare-and-set! item-ops-installed? false true)
    (let [item-stack-cls (Class/forName "net.minecraft.world.item.ItemStack")
          item-cls (Class/forName "net.minecraft.world.item.Item")]
      (extend item-stack-cls item/IItemStack
              {:item-is-empty? (fn [this]
                                 (.isEmpty this))
               :item-get-count (fn [this]
                                 (.getCount this))
               :item-get-max-stack-size (fn [this]
                                          (.getMaxStackSize this))
               :item-is-equal? (fn [this other]
                                 (.sameItem this other))
               :item-save-to-nbt (fn [this nbt]
                                   (.save this nbt))
               :item-get-or-create-tag (fn [this]
                                         (.getOrCreateTag this))
               :item-get-max-damage (fn [this]
                                      (.getMaxDamage this))
               :item-set-damage! (fn [this damage]
                                   (.setDamageValue this (int damage)))
               :item-get-damage (fn [this]
                                  (.getDamageValue this))
               :item-get-item (fn [this]
                                (.getItem this))
               :item-get-tag-compound (fn [this]
                                        (.getTag this))
               :item-split (fn [this amount]
                             (.split this (int amount)))})
      (extend item-cls item/IItem
              {:item-get-description-id (fn [this]
                                          (.getDescriptionId this))
               :item-get-registry-name (fn [this]
                                         (when-let [get-item-registry-name
                                                    (requiring-resolve 'cn.li.forge1201.ability.item-handler/get-item-registry-name)]
                                           (get-item-registry-name this)))}))))

(defn- install-entity-ops!
  []
  (when (compare-and-set! entity-ops-installed? false true)
    (let [entity-cls (Class/forName "net.minecraft.world.entity.Entity")
          player-cls (Class/forName "net.minecraft.world.entity.player.Player")
          inventory-cls (Class/forName "net.minecraft.world.entity.player.Inventory")
          menu-cls (Class/forName "net.minecraft.world.inventory.AbstractContainerMenu")
          server-player-cls (Class/forName "net.minecraft.server.level.ServerPlayer")
          local-player-cls (Class/forName "net.minecraft.client.player.LocalPlayer")
          player-impl {:entity-distance-to-sqr (fn [this x y z]
                                                 (.distanceToSqr this (double x) (double y) (double z)))
                       :player-get-level (fn [this]
                                           (try
                                             (.level this)
                                             (catch Exception _
                                               (clojure.lang.Reflector/getInstanceField this "level"))))
                       :player-get-name (fn [this]
                                          (str (.getName this)))
                       :player-get-uuid (fn [this]
                                          (.getUUID this))
                       :player-get-container-menu (fn [this]
                                                    (try
                                                      (.containerMenu this)
                                                      (catch Exception _
                                                        (clojure.lang.Reflector/getInstanceField this "containerMenu"))))}]
      (extend entity-cls entity/IEntityOps
        {:entity-distance-to-sqr (fn [this x y z]
           (.distanceToSqr this (double x) (double y) (double z)))})
      (extend player-cls entity/IEntityOps player-impl)
      ;; Concrete classes are extended explicitly because some transformed runtime
      ;; classes do not reliably inherit protocol maps from Player in dev environment.
      (extend server-player-cls entity/IEntityOps player-impl)
      (extend local-player-cls entity/IEntityOps player-impl)
      (extend inventory-cls entity/IEntityOps
        {:inventory-get-player (fn [this]
               (clojure.lang.Reflector/getInstanceField this "player"))})
      (extend menu-cls entity/IEntityOps
        {:menu-get-container-id (fn [this]
          (clojure.lang.Reflector/getInstanceField this "containerId"))}))))

(defn- install-resource-factory!
  []
  (let [resource-location-cls (Class/forName "net.minecraft.resources.ResourceLocation")
        ctor (.getConstructor resource-location-cls (into-array Class [String String]))]
    (alter-var-root #'resource/*resource-factory*
                    (constantly (fn [namespace path]
                                  (.newInstance ctor (object-array [(str namespace) (str path)])))))))

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
    (install-resource-factory!)
    (alter-var-root #'nbt/*nbt-factory*
      (constantly {:create-compound bindings/create-nbt-compound
                   :create-list bindings/create-nbt-list}))
    (alter-var-root #'item/*item-factory*
      (constantly (fn [nbt]
                    (let [item-stack-cls (Class/forName "net.minecraft.world.item.ItemStack")
                          of-method (.getMethod item-stack-cls "of" (into-array Class [(Class/forName "net.minecraft.nbt.CompoundTag")]))]
                      (.invoke of-method nil (object-array [nbt]))))))
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