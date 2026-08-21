(ns cn.li.combat.actions
  "Generic bounded action coordinators. Platform mutation is supplied as an
  injected atomic handler; this namespace owns only budget and ordering rules."
  (:require [cn.li.mcmod.runtime.seeded-rng :as seeded-rng]))

(defn commit-block-break-budget!
  [request atomic-break!]
  (let [{:keys [owner world-id blocks energy limit drop-chance seed]} request
        remaining (double (or energy 0.0))
        limit (max 0 (min 4096 (long (or limit 4096))))
        drop-chance (double (or drop-chance 0.0))
        seed (long (or seed 0))]
    (loop [xs (seq (or blocks []))
           index 0
           remaining remaining
           broken 0]
      (if (or (nil? xs) (>= index limit) (not (pos? remaining)))
        {:status :applied :broken broken :remaining-energy remaining}
        (let [block (first xs)
              point (or (:position block) block)
              [x y z] (if (vector? point) point
                          [(:x point) (:y point) (:z point)])
              hardness (double (or (:hardness block) 0.0))
              rng (seeded-rng/next-long (unchecked-add seed index))
              result (when (and atomic-break! (>= hardness 0.0)
                               (<= hardness remaining))
                       (atomic-break! {:owner owner :world-id world-id
                                       :position [x y z]
                                       :drop? (< (seeded-rng/unit-double rng)
                                                 drop-chance)}))
              applied? (= :applied (:status result))]
          (recur (next xs) (inc index)
                 (if applied? (- remaining hardness) remaining)
                 (if applied? (inc broken) broken)))))))
