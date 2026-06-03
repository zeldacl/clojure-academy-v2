(ns cn.li.ac.block.wireless-node.inventory
  "Wireless node slot schema and container inventory operations."
  (:require [cn.li.ac.block.machine.container :as machine-container]
            [cn.li.ac.block.wireless-node.state :as node-state]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]))

(def node-slot-schema-id :wireless-node)

(def ^:private node-slot-schema-config
  {:schema-id node-slot-schema-id
   :slots [{:id :input :type :energy :x 42 :y 10}
           {:id :output :type :output :x 42 :y 80}]})

(defonce ^:private node-slot-schema-registration
  (delay
    (slot-schema/register-slot-schema! node-slot-schema-config)))

(defn ensure-node-slot-schema! []
  @node-slot-schema-registration)

(defn node-input-slot-index []
  (slot-schema/slot-index node-slot-schema-id :input))

(defn node-output-slot-index []
  (slot-schema/slot-index node-slot-schema-id :output))

(defn node-slot-indexes []
  (slot-schema/all-slot-indexes node-slot-schema-id))

(defn node-slot-count []
  (slot-schema/tile-slot-count node-slot-schema-id))

(def node-container-fns
  (machine-container/make-inventory-container-fns
    {:default-state node-state/node-default-state
     :slot-count (node-slot-count)
     :slots-for-face (fn [_be _face] (int-array (node-slot-indexes)))
     :can-place? (fn [_be slot item _face]
                   (and (= slot (node-input-slot-index))
                        (energy/is-energy-item-supported? item)))
     :can-take? (fn [_be slot _item _face]
                  (= slot (node-output-slot-index)))}))
