(ns cn.li.ac.block.wireless-matrix.state
  "Wireless matrix schema lifecycle and controller BE resolution."
  (:require [cn.li.ac.block.machine.runtime :as machine-runtime]
            [cn.li.ac.block.wireless-matrix.schema :as matrix-schema]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.world :as world]))

(def ^:private matrix-rt
  (machine-runtime/schema-runtime matrix-schema/unified-matrix-schema))

(def matrix-default-state (:default-state matrix-rt))
(def matrix-scripted-load-fn (:load-fn matrix-rt))
(def matrix-scripted-save-fn (:save-fn matrix-rt))

(defn safe-state [be]
  (machine-runtime/state-or-default be matrix-default-state))

(defn resolve-controller-be [be]
  (if-not be
    nil
    (let [state (safe-state be)]
      (if (zero? (long (:sub-id state 0)))
        be
        (let [world-obj (platform-be/be-get-world-safe be)
              cx (:controller-pos-x state)
              cy (:controller-pos-y state)
              cz (:controller-pos-z state)]
          (if (and world-obj (number? cx) (number? cy) (number? cz))
            (or (world/world-get-tile-entity* world-obj (pos/create-block-pos (long cx) (long cy) (long cz)))
                be)
            be))))))
