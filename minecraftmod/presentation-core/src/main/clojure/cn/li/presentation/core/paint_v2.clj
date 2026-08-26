(ns cn.li.presentation.core.paint-v2
  "Small deterministic painter for normalized UI artifacts.

   It emits only the new UI Render IR. Layout and paint caches can replace
   this pure function later without changing the artifact or host contracts."
  (:import [cn.li.mcmod.runtime RenderCommand$UiImage RenderCommand$UiImageBatch
            RenderCommand$UiQuad RenderCommand$UiQuadBatch RenderCommand$UiText
            UiResourceRef UiResourceRef$Kind]))

(defn- state-value [state path]
  (if (and (vector? path) (= :state (first path)))
    (get-in state (subvec path 1))
    path))

(defn- dimension [value fallback]
  (cond
    (number? value) (float value)
    (= :fill value) (float fallback)
    :else (float fallback)))

(defn- rect-for [parent {:keys [x y width height] :as layout}]
  (let [{px :x py :y pw :width ph :height} parent]
    {:x (+ px (float (or x 0.0)))
     :y (+ py (float (or y 0.0)))
     :width (dimension width pw)
     :height (dimension height ph)}))

(defn- child-rects [rect direction children]
  (let [count (max 1 (count children))
        horizontal (= :row direction)
        available (if horizontal (:width rect) (:height rect))
        each (/ available count)]
    (mapv (fn [index _]
            (if horizontal
              (assoc rect :x (+ (:x rect) (* index each)) :width each)
              (assoc rect :y (+ (:y rect) (* index each)) :height each)))
          (range) children)))

(defn- command-for [node rect state]
  (let [type (:type node)
        bind (:bind node)
        value (state-value state (get bind :text))
        rgba (unchecked-int (or (get-in node [:style :rgba]) 0xFFFFFFFF))
        x (float (:x rect)) y (float (:y rect))
        width (float (:width rect)) height (float (:height rect))]
    (case type
      :rect [(RenderCommand$UiQuadBatch.
               [(RenderCommand$UiQuad. x y width height rgba)])]
      :progress (let [ratio (float (max 0.0 (min 1.0
                                                (double (or (state-value state (get bind :value)) 0.0)))))]
                  [(RenderCommand$UiQuadBatch.
                    [(RenderCommand$UiQuad. x y width height 0x55202020)
                     (RenderCommand$UiQuad. x y (* width ratio) height 0xFF35C7FF)])])
      :text [(RenderCommand$UiText. 0 (str (or value "")) x y rgba)]
      :button [(RenderCommand$UiQuadBatch.
                 [(RenderCommand$UiQuad. x y width height rgba)])
               (RenderCommand$UiText. 0
                                       (str (or (when (map? value) (:label value))
                                                value
                                                (get-in node [:semantics :label])
                                                ""))
                                       (+ x 4.0) (+ y 4.0) (unchecked-int 0xFFFFFFFF))]
      :image (when-let [resource (get-in node [:style :resource])]
               [(RenderCommand$UiImageBatch.
                  (UiResourceRef. (str (or (:namespace resource) "academy"))
                                  (str (:path resource))
                                  UiResourceRef$Kind/TEXTURE)
                  [(RenderCommand$UiImage. x y width height rgba)])])
      nil)))

(declare paint-node)

(defn- paint-node [node rect state]
  (let [children (:children node)
        direction (or (get-in node [:layout :direction])
                      (when (= :row (:type node)) :row)
                      (when (= :column (:type node)) :column))
        child-rects (if direction
                      (child-rects rect direction children)
                      (mapv (constantly rect) children))]
    (into (vec (command-for node rect state))
          (mapcat (fn [child child-rect]
                    (paint-node child child-rect state))
                  children child-rects))))

(defn paint-view [artifact state geometry]
  (let [root (:nodes artifact)
        rect {:x 0.0 :y 0.0
              :width (float (max 1 (:viewport-width geometry 1)))
              :height (float (max 1 (:viewport-height geometry 1)))}]
    (vec (paint-node root (rect-for rect (:layout root)) state))))