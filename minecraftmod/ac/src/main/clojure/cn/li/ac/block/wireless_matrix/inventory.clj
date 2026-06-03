(ns cn.li.ac.block.wireless-matrix.inventory
  "Wireless matrix slot schema and derived plate/core counts."
  (:require [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.platform.item :as pitem]))

(def ^:private matrix-slot-schema-id :wireless-matrix)

(def ^:private matrix-slot-schema-config
  {:schema-id matrix-slot-schema-id
   :slots [{:id :plate-a :type :plate :x 78 :y 11}
           {:id :plate-b :type :plate :x 53 :y 60}
           {:id :plate-c :type :plate :x 104 :y 60}
           {:id :core :type :core :x 78 :y 36}]})

(defonce ^:private matrix-slot-schema-registration
  (delay
    (slot-schema/register-slot-schema! matrix-slot-schema-config)))

(defn ensure-matrix-slot-schema! []
  @matrix-slot-schema-registration
  matrix-slot-schema-id)

(defn plate-slot-indexes []
  (slot-schema/slot-indexes-by-type matrix-slot-schema-id :plate))

(defn core-slot-index []
  (slot-schema/slot-index matrix-slot-schema-id :core))

(defn all-slot-indexes []
  (slot-schema/all-slot-indexes matrix-slot-schema-id))

(defn slot-count []
  (slot-schema/tile-slot-count matrix-slot-schema-id))

(defn required-plate-count []
  (count (plate-slot-indexes)))

(defn- slot-has-stack? [stk]
  (and stk (try (pos? (long (pitem/item-get-count stk))) (catch Exception _ true))))

(defn recalculate-counts [state]
  (let [plate-count (count (for [slot (plate-slot-indexes)
                                 :let [stk (get-in state [:inventory slot])]
                                 :when (slot-has-stack? stk)]
                             slot))
        core-stack (get-in state [:inventory (core-slot-index)])
        core-level (if (slot-has-stack? core-stack)
                     (inc (int (max 0 (pitem/item-get-damage core-stack))))
                     0)]
    (assoc state :plate-count plate-count :core-level core-level)))

(defn is-working? [state]
  (and (> (:core-level state 0) 0)
       (= (:plate-count state 0) (required-plate-count))))
