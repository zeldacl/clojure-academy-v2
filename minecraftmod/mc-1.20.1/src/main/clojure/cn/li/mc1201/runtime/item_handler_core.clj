(remove-ns 'cn.li.mc1201.runtime.item-handler-core)

(ns cn.li.mc1201.runtime.item-handler-core
  "Shared Minecraft-side item runtime helpers (no loader API imports)."
  (:require [clojure.string :as str]
            [cn.li.mcmod.item.dsl :as idsl]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.hooks.core :as hooks-core]
            [cn.li.mcmod.protocol.metadata :as registry-metadata]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.world InteractionHand]
           [net.minecraft.world.entity.player Player]
           [net.minecraft.world.item ItemStack]))

(defn get-item-id
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

(defn resolve-dsl-item-spec
  "Resolve DSL item spec from runtime item id (`namespace:path`)."
  [item-id]
  (when-let [[_namespace registry-path] (some-> item-id (str/split #":" 2))]
    (some (fn [dsl-id]
            (when (= registry-path (registry-metadata/get-item-registry-name dsl-id))
              (registry-metadata/get-item-spec dsl-id)))
          (registry-metadata/get-all-item-ids))))

(defn dispatch-dsl-item-use!
  [^Player player item-id hand stack side]
  (when-let [item-spec (resolve-dsl-item-spec item-id)]
    (idsl/handle-use item-spec {:player player
                                :item-id item-id
                                :item-spec item-spec
                                :item-stack stack
                                :hand hand
                                :side side})))

(defn dispatch-dsl-item-right-click-consume?
  [^Player player item-id side hand stack]
  (when-let [item-spec (resolve-dsl-item-spec item-id)]
    (let [ret (idsl/handle-right-click item-spec {:player player
                                                  :item-id item-id
                                                  :item-spec item-spec
                                                  :item-stack stack
                                                  :hand hand
                                                  :side side})]
      (boolean (:consume? ret)))))

(defn dispatch-dsl-item-finish-using!
  [^Player player stack side]
  (let [item-id (get-item-id stack)]
    (when-let [item-spec (resolve-dsl-item-spec item-id)]
      (idsl/handle-finish-using item-spec {:player player
                                           :item-id item-id
                                           :item-spec item-spec
                                           :item-stack stack
                                           :side side}))
    item-id))

(defn- run-plan-actions!
  [^Player player hand ^ItemStack stack side player-uuid plan {:keys [open-screen-fn]}]
  (when plan
    (doseq [action (:client-actions plan)]
      (case (:kind action)
        :notify-local-effect
        (hooks-core/client-notify-charge-coin-throw! player-uuid)

        :open-screen
        (if open-screen-fn
          (open-screen-fn player player-uuid)
          (hooks-core/client-open-skill-tree-screen! player-uuid nil))

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
        (hooks-core/on-runtime-item-action! (:action action) player-uuid (:payload action))

        :spawn-scripted-effect
        (when (= side :server)
          (entity/player-spawn-entity-by-id! player
                                             (str (:entity-id action))
                                             (double (or (:speed action) 0.0))))

        nil))))

(defn process-item-use!
  "Shared main-hand runtime item-use flow.

  Returns {:consume? boolean :item-id string|nil :player-uuid string :plan map|nil}."
  [^Player player hand stack side opts]
  (if (not= hand InteractionHand/MAIN_HAND)
    {:consume? false
     :item-id nil
     :player-uuid nil
     :plan nil}
    (let [player-uuid (str (.getUUID player))
          item-id (get-item-id stack)
          ability-activated? (boolean (get-in (hooks-core/get-player-state player-uuid)
                                              [:resource-data :activated]))
          plan (hooks-core/build-item-use-plan player-uuid item-id ability-activated? side)]
      (dispatch-dsl-item-use! player item-id hand stack side)
      (run-plan-actions! player hand stack side player-uuid plan opts)
      {:consume? (or (:consume? plan)
                     (dispatch-dsl-item-right-click-consume? player item-id side hand stack))
       :item-id item-id
       :player-uuid player-uuid
       :plan plan})))
