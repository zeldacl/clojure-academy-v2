(ns cn.li.ac.ability.service.radiation-marks
  "Pure state transitions for Combat Core's Meltdowner radiation marks.

   The composition root owns the atom that stores these values; this
   namespace contains no platform, Context, packet, or damage-listener code.")

(defn mark
  [existing {:keys [source-player-id target-id rate tick duration]}]
  {:source-player-id source-player-id
   :target-id target-id
   :ticks-left (max (long (or duration 60))
                    (long (or (:ticks-left existing) 0)))
   :rate (double (or rate 1.0))
   :updated-at-tick tick})

(defn tick
  [marks tick-id]
  (into {}
        (keep (fn [[target value]]
                (let [left (dec (long (or (:ticks-left value) 0)))]
                  (when (pos? left)
                    [target (assoc value :ticks-left left
                                   :updated-at-tick tick-id)]))))
        marks))

(defn clear-owner
  [marks owner]
  (into {}
        (remove (fn [[_ value]]
                  (= (str owner) (str (:source-player-id value)))))
        marks))

