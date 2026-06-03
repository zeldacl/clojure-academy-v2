(ns cn.li.ac.block.machine.container
  "Generate standard tile container function maps from declarative config."
  (:require [cn.li.ac.block.machine.runtime :as machine-runtime]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.item :as pitem]))

(defn- stack-count [stack]
  (if (or (nil? stack)
          (try (boolean (pitem/item-is-empty? stack)) (catch Exception _ true)))
    0
    (try (int (pitem/item-get-count stack)) (catch Exception _ 0))))

(defn make-inventory-container-fns
  "Build container fns for a fixed-size [:inventory slot] vector in customState.

  Options:
  - :default-state map
  - :slot-count int
  - :inventory-key keyword (default :inventory)
  - :can-place? (fn [be slot item face] -> bool)
  - :can-take? (fn [be slot item face] -> bool)
  - :transform-state (fn [state] -> state) applied after inventory mutation
  - :slots-for-face optional (fn [be face] -> int[])"
  [{:keys [default-state slot-count inventory-key can-place? can-take? transform-state slots-for-face]
    :or {inventory-key :inventory
         transform-state identity}}]
  (let [inv-path (fn [state] (get state inventory-key))
        assoc-slot (fn [state slot item]
                     (assoc-in state [inventory-key slot] item))
        apply-transform (fn [state]
                          (transform-state state))]
    {:get-size (fn [_be] slot-count)
     :get-item (fn [be slot]
                 (get-in (machine-runtime/state-or-default be default-state) [inventory-key slot]))
     :set-item! (fn [be slot item]
                  (let [state (machine-runtime/state-or-default be default-state)
                        state' (apply-transform (assoc-slot state slot item))]
                    (platform-be/set-custom-state! be state')))
     :remove-item (fn [be slot amount]
                    (let [state (machine-runtime/state-or-default be default-state)
                          item (get-in state [inventory-key slot])]
                      (when item
                        (let [cnt (stack-count item)]
                          (if (<= cnt amount)
                            (do (platform-be/set-custom-state! be (apply-transform (assoc-slot state slot nil)))
                                item)
                            (pitem/item-split item amount))))))
     :remove-item-no-update (fn [be slot]
                              (let [state (machine-runtime/state-or-default be default-state)
                                    item (get-in state [inventory-key slot])]
                                (platform-be/set-custom-state! be (apply-transform (assoc-slot state slot nil)))
                                item))
     :clear! (fn [be]
               (platform-be/set-custom-state! be
                 (apply-transform
                   (assoc (machine-runtime/state-or-default be default-state)
                          inventory-key (vec (repeat slot-count nil))))))
     :still-valid? (fn [_be _player] true)
     :slots-for-face (or slots-for-face (fn [_be _face] (int-array (range slot-count))))
     :can-place-through-face? (or can-place? (fn [_be _slot _item _face] false))
     :can-take-through-face? (or can-take? (fn [_be _slot _item _face] false))}))
