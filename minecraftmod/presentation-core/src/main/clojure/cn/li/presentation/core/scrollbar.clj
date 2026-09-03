(ns cn.li.presentation.core.scrollbar
  "Declarative vertical scrollbar helpers for schema-5 mounts.

   Authoring (preserved by the compiler as :node/scrollbar):
     :style {:scrollbar {:for <scroll-node-key>
                         :min-y <thumb top at progress 0>
                         :max-y <thumb top at progress 1>
                         :thumb? true}}   ;; paint offsets this node")

(defn progress
  "Normalize scroll offset against max-offset into [0,1]."
  [offset max-off]
  (let [max-off (float (or max-off 0.0))
        offset (float (or offset 0.0))]
    (if (pos? max-off)
      (float (max 0.0 (min 1.0 (/ offset max-off))))
      0.0)))

(defn thumb-y
  "Local thumb top for progress in [0,1]."
  [sb ratio]
  (let [min-y (float (or (:min-y sb) 0.0))
        max-y (float (or (:max-y sb) min-y))
        travel (float (- max-y min-y))
        r (float (max 0.0 (min 1.0 (double (or ratio 0.0)))))]
    (float (+ min-y (* r travel)))))

(defn offset-for-pointer
  "Map an absolute pointer y on a scrollbar hit rect to a scroll offset."
  [sb rect-y py max-off]
  (let [min-y (float (or (:min-y sb) 0.0))
        max-y (float (or (:max-y sb) min-y))
        travel (float (max 0.0 (- max-y min-y)))
        local (float (- (float py) (float rect-y)))
        ratio (if (pos? travel)
                (float (max 0.0 (min 1.0 (/ local travel))))
                0.0)]
    (float (* ratio (float (or max-off 0.0))))))

(defn offset-for-drag
  "Relative drag: thumb follows pointer delta (upstream DragBar)."
  [sb start-offset start-py py max-off]
  (let [min-y (float (or (:min-y sb) 0.0))
        max-y (float (or (:max-y sb) min-y))
        travel (float (max 0.0 (- max-y min-y)))
        max-off (float (or max-off 0.0))
        start-thumb (thumb-y sb (progress start-offset max-off))
        new-thumb (float (max min-y (min max-y (+ start-thumb (- (float py) (float start-py))))))
        ratio (if (pos? travel)
                (float (/ (- new-thumb min-y) travel))
                0.0)]
    (float (* ratio max-off))))
