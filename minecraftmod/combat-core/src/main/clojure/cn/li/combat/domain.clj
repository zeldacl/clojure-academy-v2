(ns cn.li.combat.domain
  "Generic combat-domain state transitions.

   Marks are intentionally keyed by target and mark type.  The domain layer
   stores only neutral facts; damage/VFX consumers decide what a mark means
   through their own declarative documents.")

(defn- mark-value [existing event]
  {:source-player-id (:source-player-id event)
   :target-id (:target-id event)
   :mark-type (:mark-type event)
   :ticks-left (max (long (or (:duration event) 60))
                    (long (or (:ticks-left existing) 0)))
   :rate (double (or (:rate event) 1.0))
   :updated-at-tick (:tick event)})

(defn- tick-marks [marks tick]
  (reduce-kv
   (fn [result target types]
     (let [types (reduce-kv
                  (fn [acc mark-type value]
                    (let [left (dec (long (or (:ticks-left value) 0)))]
                      (if (pos? left)
                        (assoc acc mark-type (assoc value :ticks-left left
                                                     :updated-at-tick tick))
                        acc)))
                  {} types)]
       (if (seq types) (assoc result target types) result)))
   {} (or marks {})))

(defn- clear-owner [marks owner]
  (into {}
        (keep (fn [[target types]]
               (let [types (into {}
                                 (remove (fn [[_ value]]
                                           (= (str owner)
                                              (str (:source-player-id value)))))
                                 types)]
                 (when (seq types) [target types]))))
        (or marks {})))

(defn apply-event
  "Apply one neutral domain event to the immutable domain state map."
  [state event]
  (case (:type event)
    :entity-mark
    (let [target (str (:target-id event))
          mark-type (:mark-type event)]
      (assoc-in state [:entity-marks target mark-type]
                (mark-value (get-in state [:entity-marks target mark-type]) event)))

    :entity-mark-clear
    (let [target (str (:target-id event))
          mark-type (:mark-type event)]
      (if mark-type
        (update-in state [:entity-marks target]
                    (fn [types]
                      (let [types (dissoc (or types {}) mark-type)]
                        (when (seq types) types))))
        (update state :entity-marks dissoc target)))

    :entity-marks-clear-all
    (dissoc state :entity-marks)

    :entity-marks-replace
    (assoc state :entity-marks (or (:marks event) {}))

    :entity-owner-clear
    (update state :entity-marks #(clear-owner (or %) (:owner event)))

    :combat-tick
    (update state :entity-marks #(tick-marks (or %) (:tick event)))

    state))
