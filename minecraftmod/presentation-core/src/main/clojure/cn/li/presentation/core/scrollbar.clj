(ns cn.li.presentation.core.scrollbar
  "Declarative vertical scrollbar helpers shared by paint and input routing.

   Authoring shape on a node:
     :style {:scrollbar {:for <scroll-node-key>
                         :min-y <thumb top at progress 0>
                         :max-y <thumb top at progress 1>
                         :thumb? true}}   ;; when true, paint offsets this node

   Hit targets typically omit :thumb?; the moving thumb sets :thumb? true."
  )

(defn spec
  "Return the :scrollbar map from node style, or nil."
  [node]
  (let [sb (get-in node [:style :scrollbar])]
    (when (map? sb) sb)))

(defn find-node
  "Depth-first search for a node with the given :key."
  [node key]
  (when (some? node)
    (if (= key (:key node))
      node
      (some #(find-node % key) (:children node)))))

(defn- dimension [value fallback]
  (cond
    (number? value) (float value)
    (= :fill value) (float fallback)
    :else (float fallback)))

(defn- collection-items [env node]
  (let [path (get-in node [:bind :items])
        items (cond
                (and (vector? path) (= :state (first path)))
                (get-in (:state env) (subvec path 1))
                (and (vector? path) (= :item (first path)))
                (get-in (:item env) (subvec path 1))
                :else path)]
    (if (sequential? items) (vec items) [])))

(defn max-offset
  "Max scroll offset for a :scroll node given current bound items."
  [scroll-node env]
  (when scroll-node
    (let [items (collection-items env scroll-node)
          template (or (first (:children scroll-node)) {:layout {}})
          view-h (dimension (get-in scroll-node [:layout :height]) 0.0)
          extent (dimension (get-in template [:layout :height])
                            (/ view-h (max 1 (count items))))]
      (float (max 0.0 (- (* extent (count items)) view-h))))))

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
  "Map an absolute pointer y on a scrollbar hit rect to a scroll offset.
   Used for click-to-jump on the track."
  [sb rect py max-off]
  (let [min-y (float (or (:min-y sb) 0.0))
        max-y (float (or (:max-y sb) min-y))
        travel (float (max 0.0 (- max-y min-y)))
        local (float (- (float py) (float (:y rect))))
        ratio (if (pos? travel)
                (float (max 0.0 (min 1.0 (/ local travel))))
                0.0)]
    (float (* ratio (float (or max-off 0.0))))))

(defn offset-for-drag
  "Relative drag matching upstream DragBar: thumb follows pointer delta."
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

(defn apply-thumb-rect
  "Offset a thumb node's rect.y from the linked scroll offset."
  [node rect env]
  (let [sb (spec node)]
    (if (and sb (:thumb? sb) (:for sb))
      (let [target (:for sb)
            scroll-node (find-node (:nodes env) target)
            max-off (max-offset scroll-node env)
            offset (float (or (get-in env [:scroll-offsets target]) 0.0))
            ratio (progress offset max-off)
            local-y (thumb-y sb ratio)
            ;; Rebuild absolute y from parent origin implied by layout y.
            parent-y (- (float (:y rect)) (float (or (get-in node [:layout :y]) 0.0)))]
        (assoc rect :y (float (+ parent-y local-y))))
      rect)))
