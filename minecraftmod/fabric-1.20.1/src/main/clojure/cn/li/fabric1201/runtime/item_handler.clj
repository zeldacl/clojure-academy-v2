(ns cn.li.fabric1201.runtime.item-handler
  "Item use event handler for runtime-driven items (Fabric layer)."
  (:require [cn.li.mcmod.platform.power-runtime :as power-runtime]
            [cn.li.mcmod.platform.entity :as entity]
            [clojure.string :as str]
            [cn.li.mcmod.registry.metadata :as registry-metadata]
            [cn.li.mcmod.item.dsl :as idsl]
            [cn.li.mcmod.util.log :as log])
  (:import [net.fabricmc.fabric.api.event.player UseItemCallback]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.world InteractionResultHolder InteractionHand]
           [net.minecraft.world.entity.player Player]
           [net.minecraft.world.item ItemStack]
           [net.minecraft.resources ResourceLocation]))

(defonce ^:private installed? (atom false))

(defn- get-item-id
  [^ItemStack stack]
  (when stack
    (try
      (let [item (.getItem stack)
            ^ResourceLocation registry-name (.getKey BuiltInRegistries/ITEM item)]
        (when registry-name
          (str (.getNamespace registry-name) ":" (.getPath registry-name))))
      (catch Exception e
        (log/warn "Failed to get item ID:" (ex-message e))
        nil))))

(defn- resolve-dsl-item-spec
  "Resolve DSL item spec from runtime item id (`namespace:path`)."
  [item-id]
  (when-let [[_namespace registry-path] (some-> item-id (str/split #":" 2))]
    (some (fn [dsl-id]
            (when (= registry-path (registry-metadata/get-item-registry-name dsl-id))
              (registry-metadata/get-item-spec dsl-id)))
          (registry-metadata/get-all-item-ids))))

(defn- dsl-right-click-consume?
  [^Player player item-id side hand stack]
  (when-let [item-spec (resolve-dsl-item-spec item-id)]
    (let [ret (idsl/handle-right-click item-spec {:player player
                                                  :item-id item-id
                                                  :item-spec item-spec
                                                  :item-stack stack
                                                  :hand hand
                                                  :side side})]
      (boolean (:consume? ret)))))

(defn- dispatch-dsl-item-use!
  [^Player player item-id hand stack side]
  (when-let [item-spec (resolve-dsl-item-spec item-id)]
    (idsl/handle-use item-spec {:player player
                                :item-id item-id
                                :item-spec item-spec
                                :item-stack stack
                                :hand hand
                                :side side})))

(defn- run-plan-actions!
  [^Player player hand stack side player-uuid plan]
  (when plan
    (doseq [action (:client-actions plan)]
      (case (:kind action)
        :notify-local-effect
        (power-runtime/client-notify-charge-coin-throw! player-uuid)

        :open-screen
        (power-runtime/client-open-skill-tree-screen! player-uuid nil)

        nil))

    (doseq [action (:server-actions plan)]
      (case (:kind action)
        :consume-item
        (when (and (= side :server)
                   (or (not (:unless-instabuild? action))
                       (not (.. player (getAbilities) instabuild))))
          (when (and stack (not (.isEmpty stack)))
            (.shrink stack (int (or (:count action) 1)))
            (when (.isEmpty stack)
              (.setItemInHand player hand ItemStack/EMPTY))))

        :domain-action
        (power-runtime/on-runtime-item-action! (:action action) player-uuid (:payload action))

        :spawn-scripted-effect
        (when (= side :server)
          (entity/player-spawn-entity-by-id! player
                                             (str (:entity-id action))
                                             (double (or (:speed action) 0.0))))

        nil))))

(defn- on-item-use
  "Handle Fabric UseItemCallback event."
  [^Player player world ^InteractionHand hand]
  (try
    (if (not= hand InteractionHand/MAIN_HAND)
      (InteractionResultHolder/pass ItemStack/EMPTY)
      (let [player-uuid (str (.getUUID player))
            ability-activated? (boolean (get-in (power-runtime/get-player-state player-uuid)
                                                [:resource-data :activated]))
            stack (.getItemInHand player hand)
            item-id (get-item-id stack)
            side (if (.isClientSide world) :client :server)
            plan (power-runtime/build-item-use-plan player-uuid item-id ability-activated? side)]
        (dispatch-dsl-item-use! player item-id hand stack side)
        (run-plan-actions! player hand stack side player-uuid plan)
        (let [consume? (or (:consume? plan)
                           (dsl-right-click-consume? player item-id side hand stack))]
          (if consume?
            (InteractionResultHolder/success stack)
            (InteractionResultHolder/pass ItemStack/EMPTY)))))
    (catch Exception e
      (log/error "Error handling Fabric item use event" e)
      (InteractionResultHolder/pass ItemStack/EMPTY))))

(defn init!
  "Initialize Fabric runtime item use event handler."
  []
  (if-not (compare-and-set! installed? false true)
    (log/info "Fabric runtime item handler already initialized, skipping")
    (do
      (.register UseItemCallback/EVENT
                 (reify UseItemCallback
                   (interact [_ player world hand]
                     (on-item-use player world hand))))
      (log/info "Fabric runtime item handler initialized"))))
