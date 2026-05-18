(ns cn.li.ac.block.wireless-node.inventory
  "Wireless node slot schema and container inventory operations."
  (:require [cn.li.ac.block.wireless-node.state :as node-state]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.mcmod.gui.slot-registry :as slot-registry]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.item :as pitem]))

(def node-slot-schema-id :wireless-node)

(defn ensure-node-slot-schema!
  []
  (slot-schema/register-slot-schema!
    {:schema-id node-slot-schema-id
     :slots [{:id :input :type :energy :x 42 :y 10}
             {:id :output :type :output :x 42 :y 80}]}))

(def ^:private node-input-slot-index*
  (delay
    (ensure-node-slot-schema!)
    (slot-schema/slot-index node-slot-schema-id :input)))

(def ^:private node-output-slot-index*
  (delay
    (ensure-node-slot-schema!)
    (slot-schema/slot-index node-slot-schema-id :output)))

(def ^:private node-slot-indexes*
  (delay
    (ensure-node-slot-schema!)
    (slot-schema/all-slot-indexes node-slot-schema-id)))

(def ^:private node-slot-count*
  (delay
    (ensure-node-slot-schema!)
    (slot-registry/get-slot-count node-slot-schema-id)))

(defn node-input-slot-index [] @node-input-slot-index*)
(defn node-output-slot-index [] @node-output-slot-index*)
(defn node-slot-indexes [] @node-slot-indexes*)
(defn node-slot-count [] @node-slot-count*)

(def node-container-fns
  {:get-size (fn [_be] (node-slot-count))

   :get-item (fn [be slot]
               (get-in (or (platform-be/get-custom-state be) node-state/node-default-state)
                       [:inventory slot]))

   :set-item! (fn [be slot item]
                (let [state  (or (platform-be/get-custom-state be) node-state/node-default-state)
                      state' (assoc-in state [:inventory slot] item)]
                  (platform-be/set-custom-state! be state')))

   :remove-item (fn [be slot amount]
                  (let [state (or (platform-be/get-custom-state be) node-state/node-default-state)
                        item  (get-in state [:inventory slot])]
                    (when item
                      (let [cnt (pitem/item-get-count item)]
                        (if (<= cnt amount)
                          (do
                            (platform-be/set-custom-state! be (assoc-in state [:inventory slot] nil))
                            item)
                          (pitem/item-split item amount))))))

   :remove-item-no-update (fn [be slot]
                            (let [state (or (platform-be/get-custom-state be) node-state/node-default-state)
                                  item  (get-in state [:inventory slot])]
                              (platform-be/set-custom-state! be (assoc-in state [:inventory slot] nil))
                              item))

   :clear! (fn [be]
             (platform-be/set-custom-state! be (assoc (or (platform-be/get-custom-state be) node-state/node-default-state)
                                                 :inventory (vec (repeat (node-slot-count) nil)))))

   :still-valid? (fn [_be _player] true)
   :slots-for-face (fn [_be _face] (int-array (node-slot-indexes)))

   :can-place-through-face? (fn [_be slot item _face]
                              (and (= slot (node-input-slot-index))
                                   (energy/is-energy-item-supported? item)))

   :can-take-through-face? (fn [_be slot _item _face]
                             (= slot (node-output-slot-index)))})
