(ns cn.li.ac.block.wireless-matrix.stats
  "Shared Wireless Matrix stat formulas.

  Keep this namespace free of block/item/schema dependencies so item tooltips,
  GUI previews, and capability logic can use the same config-backed formula
  without creating namespace cycles."
  (:require [cn.li.ac.wireless.config :as matrix-config]))

(defn stats-for-counts
  "Return Matrix capacity/bandwidth/range for a core and plate count.

  `required-plate-count` is passed in by the block logic because the plate slot
  layout is structural, not a player config value."
  [required-plate-count core-level plate-count]
  (let [core-lv (int core-level)]
    (if (and (> core-lv 0) (= (int plate-count) (int required-plate-count)))
      {:capacity (int (* (matrix-config/capacity-per-core-level) core-lv))
       :bandwidth (double (* core-lv core-lv (matrix-config/bandwidth-factor)))
       :range (double (* (matrix-config/range-base) (Math/sqrt core-lv)))}
      {:capacity 0 :bandwidth 0.0 :range 0.0})))