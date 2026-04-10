(ns cn.li.ac.item.constraint-plate
  "Constraint Plate - component for Wireless Matrix

  Required for Matrix to function (need 3 plates)."
  (:require [cn.li.mcmod.item.dsl :as idsl]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.platform.item :as item]
            [clojure.string :as str]))

(def ^:private constraint-plate-id "constraint_plate")

(defonce ^:private constraint-plate-installed? (atom false))

(defn init-constraint-plate! []
  (when (compare-and-set! constraint-plate-installed? false true)
    (idsl/register-item!
      (idsl/create-item-spec
        constraint-plate-id
        {:max-stack-size 64
         :creative-tab :misc
         :properties {:tooltip ["用于无线矩阵的限制板"
                                "需要3个才能激活矩阵"]
                      :model-texture "constraint_plate"}
         :on-use (fn [_event-data]
                   (log/debug "Using constraint plate"))}))
    (log/info "Constraint Plate initialized")))

(defn is-constraint-plate?
  "Check if ItemStack (or item-like value) is a constraint plate.

  Accepts either a platform `ItemStack` or an item/spec-like map.
  For ItemStacks we extract the description id and compare against the
  DSL `:id` to make the predicate robust across representations.
  "
  [item-stack]
  (when item-stack
    (let [id-from-spec #(when (map? %) (:id %))
       item-obj (try (item/item-get-item item-stack) (catch Throwable _ nil))
       registry-name (when item-obj
             (try (item/item-get-registry-name item-obj) (catch Throwable _ nil)))
       desc (when item-obj
         (try (str (item/item-get-description-id item-obj)) (catch Throwable _ nil)))
       id-from-stack (or registry-name (when desc (last (str/split desc #"\\."))))
       id (or id-from-stack (id-from-spec item-stack))
            result (boolean
                (or (= id constraint-plate-id)
                  (and (string? desc) (str/includes? desc constraint-plate-id))))]
      (try
        (log/debug "is-constraint-plate?" {:stack-class (class item-stack)
                                            :desc desc
                                            :derived-id id
                                            :result result})
        (catch Throwable _))
      result)))
