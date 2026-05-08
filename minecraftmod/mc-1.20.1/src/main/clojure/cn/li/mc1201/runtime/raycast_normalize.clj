(ns cn.li.mc1201.runtime.raycast-normalize)

(defn normalize-bridge-map
  "Normalize Java bridge raycast result maps.

  - Convert incoming keys to keywords.
  - Convert string values for :face and :hit-type to keywords.
  - Return nil when input result is nil."
  [result]
  (when result
    (let [normalized (into {}
                           (map (fn [[key value]] [(keyword key) value]))
                           result)]
      (cond-> normalized
        (string? (:face normalized)) (update :face keyword)
        (string? (:hit-type normalized)) (update :hit-type keyword)))))
