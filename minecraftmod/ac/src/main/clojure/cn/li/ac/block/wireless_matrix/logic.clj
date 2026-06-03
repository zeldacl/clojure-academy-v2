(ns cn.li.ac.block.wireless-matrix.logic
  "Wireless matrix tick, container, and block events."
  (:require [cn.li.ac.block.machine.container :as machine-container]
            [cn.li.ac.block.machine.runtime :as machine-runtime]
            [cn.li.ac.block.wireless-matrix.capability :as matrix-capability]
            [cn.li.ac.block.wireless-matrix.inventory :as matrix-inventory]
            [cn.li.ac.block.wireless-matrix.schema :as matrix-schema]
            [cn.li.ac.block.wireless-matrix.state :as matrix-state]
            [cn.li.ac.block.wireless-matrix.sync-broadcast :as matrix-sync]
            [cn.li.ac.item.constraint-plate :as plate]
            [cn.li.ac.item.mat-core :as core]
            [cn.li.ac.wireless.config :as matrix-config]
            [cn.li.mcmod.block.state-schema :as schema]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.acapi.wireless IWirelessMatrix]))

(def matrix-default-state matrix-state/matrix-default-state)
(def matrix-scripted-load-fn matrix-state/matrix-scripted-load-fn)
(def matrix-scripted-save-fn matrix-state/matrix-scripted-save-fn)

(defn safe-state [be]
  (matrix-state/safe-state be))

(defn resolve-controller-be [be]
  (matrix-state/resolve-controller-be be))

(defn ensure-matrix-slot-schema! []
  (matrix-inventory/ensure-matrix-slot-schema!))

(defn get-plate-count [be]
  (if-let [ctrl (resolve-controller-be be)]
    (get (safe-state ctrl) :plate-count 0)
    0))

(defn get-core-level [be]
  (if-let [ctrl (resolve-controller-be be)]
    (get (safe-state ctrl) :core-level 0)
    0))

(defn required-plate-count []
  (matrix-inventory/required-plate-count))

(defn matrix-stats-for-counts [core-level plate-count]
  (matrix-capability/matrix-stats-for-counts core-level plate-count))

(defn- matrix-sync-payload [state pos be]
  (let [impl ^IWirelessMatrix (matrix-capability/->WirelessMatrixImpl be)]
    (-> (schema/schema->sync-payload matrix-schema/unified-matrix-schema state pos)
        (assoc :is-working (matrix-inventory/is-working? state)
               :capacity (.getMatrixCapacity impl)
               :bandwidth (.getMatrixBandwidth impl)
               :range (.getMatrixRange impl)))))

(defn matrix-tick-state
  [state {:keys [level pos be]}]
  (let [ticker (inc (int (get state :update-ticker 0)))
        state1 (assoc state :update-ticker ticker)]
    (if (and (zero? (:sub-id state1 0))
             (zero? (mod ticker (matrix-config/gui-sync-interval))))
      (try
        (let [payload (matrix-sync-payload state1 pos be)
              old-payload (::last-broadcast-state state1)]
          (when (and level pos (not= payload old-payload))
            (matrix-sync/broadcast-matrix-state! level pos payload))
          (assoc state1 ::last-broadcast-state payload))
        (catch Exception e
          (log/debug "Matrix sync skipped:" (ex-message e))
          state1))
      state1)))

(def matrix-scripted-tick-fn
  (machine-runtime/make-tick-fn
    {:default-state matrix-default-state
     :tick-state matrix-tick-state}))

(def matrix-container-fns
  (machine-container/make-inventory-container-fns
    {:default-state matrix-default-state
     :slot-count (matrix-inventory/slot-count)
     :transform-state matrix-inventory/recalculate-counts
     :slots-for-face (fn [_be _face] (int-array (matrix-inventory/all-slot-indexes)))
     :can-place? (fn [_be slot item _face]
                   (cond
                     (contains? (set (matrix-inventory/plate-slot-indexes)) slot)
                     (plate/is-constraint-plate? item)
                     (= slot (matrix-inventory/core-slot-index))
                     (core/is-mat-core? item)
                     :else false))
     :can-take? (fn [_be _slot _item _face] true)}))

(def handle-matrix-right-click
  (machine-runtime/make-open-gui-handler :matrix))

(defn handle-matrix-place []
  (fn [{:keys [player world pos]}]
    (when-let [be (world/world-get-tile-entity* world pos)]
      (let [player-name (try (entity/player-get-name player)
                             (catch Exception _ (str player)))
            state (safe-state be)
            state' (assoc state :placer-name (str player-name))]
        (machine-runtime/commit-state! be world pos state state')))))

(defn handle-matrix-break []
  (fn [{:keys [world pos]}]
    (when-let [be (world/world-get-tile-entity* world pos)]
      (doseq [[idx item] (map-indexed vector (:inventory (safe-state be) []))]
        (when item
          (log/info "Dropping item from slot" idx ":" item))))))
