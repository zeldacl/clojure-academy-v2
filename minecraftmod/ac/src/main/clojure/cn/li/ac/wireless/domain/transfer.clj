(ns cn.li.ac.wireless.domain.transfer
  "Pure transfer planning helpers."
  (:require [cn.li.ac.wireless.domain.model :as model]))

(defn clamp
  [x lo hi]
  (-> x (max lo) (min hi)))

(defn balance-plan
  "Build a pure balancing plan.

  Inputs:
  - entries: [{:id <stable-key> :energy :max-energy :bandwidth}]
  - matrix-bandwidth: transfer budget per balance tick
  - buffer0: current buffer
  - buffer-max: max buffer

  Output:
  {:energies {id new-energy}
   :buffer new-buffer}"
  [entries matrix-bandwidth buffer0 buffer-max]
  (let [active (->> entries
                    (filter #(pos? (double (:max-energy %))))
                    vec)
        max-sum (reduce + 0.0 (map (comp double :max-energy) active))
        buffer0 (clamp (double buffer0) 0.0 (double buffer-max))]
    (if (or (empty? active) (not (pos? max-sum)))
      {:energies {} :buffer buffer0}
      (let [sum (+ (reduce + 0.0 (map (comp double :energy) active)) buffer0)
            percent (clamp (/ sum max-sum) 0.0 1.0)]
        (loop [xs active
               transfer-left (double matrix-bandwidth)
               buffer buffer0
               out {}]
          (if (or (empty? xs) (not (pos? transfer-left)))
            {:energies out
             :buffer (clamp buffer 0.0 (double buffer-max))}
            (let [{:keys [id energy max-energy bandwidth]} (first xs)
                  energy (double energy)
                  max-energy (double max-energy)
                  bandwidth (max 0.0 (double bandwidth))
                  target (* max-energy percent)
                  delta (- target energy)
                  room-in-buffer (- (double buffer-max) buffer)]
              (cond
                (and (pos? delta) (pos? buffer))
                (let [give (min delta bandwidth transfer-left buffer)
                      next-energy (min max-energy (+ energy give))]
                  (recur (rest xs)
                         (- transfer-left give)
                         (- buffer give)
                         (assoc out id next-energy)))

                (and (neg? delta) (pos? room-in-buffer))
                (let [take (min (- delta) bandwidth transfer-left room-in-buffer)
                      next-energy (max 0.0 (- energy take))]
                  (recur (rest xs)
                         (- transfer-left take)
                         (+ buffer take)
                         (assoc out id next-energy)))

                :else
                (recur (rest xs) transfer-left buffer (assoc out id energy))))))))))

(defn node-connection-capacity?
  [connection capacity]
  (model/connection-has-capacity? connection capacity))

(defn- actual-provided-transfer
  [provided required]
  (let [provided (double provided)
        required (double required)]
    (if (> provided required) required provided)))

(defn collect-from-generator-step
  "Pure pull step for one generator slot.

  Returns `nil` when no transfer should occur."
  [transfer-left node-energy node-max gen-bandwidth provided]
  (when (and (pos? (double transfer-left))
             (pos? (double gen-bandwidth)))
    (let [node-space (- (double node-max) (double node-energy))
          required (min (double transfer-left)
                        (double gen-bandwidth)
                        node-space)]
      (when (pos? required)
        (let [actual (actual-provided-transfer provided required)]
          {:required required
           :actual-transfer actual
           :transfer-left (- transfer-left actual)
           :node-energy (+ node-energy actual)})))))

(defn distribute-to-receiver-step
  "Pure push step for one receiver slot.

  Returns `{:give ...}` when a transfer should be attempted.
  Actual energy moved is determined at apply time via `injectEnergy`."
  [transfer-left node-energy rec-bandwidth required-energy]
  (when (and (pos? (double transfer-left))
             (pos? (double node-energy)))
    (let [give0 (min (double node-energy)
                     (double transfer-left)
                     (double rec-bandwidth))
          give (min give0 (double required-energy))]
      (when (pos? give) {:give give}))))
