(ns cn.li.presentation.core.paint
  "Deterministic painter for normalized UI artifacts.

   Collection nodes expand from immutable state and leaf nodes emit only
   neutral Ui* commands. This namespace has no Minecraft/backend dependency."
  (:require [clojure.string])
  (:import [cn.li.mcmod.runtime RenderCommand$UiImage RenderCommand$UiImageBatch
            RenderCommand$UiQuad RenderCommand$UiQuadBatch RenderCommand$UiText RenderCommand$UiItemPreview RenderCommand$UiModelPreview RenderCommand$PushClip RenderCommand$PopClip RenderCommand$Transform RenderCommand$Mask
            UiResourceRef UiResourceRef$Kind]))

(defn- state-value [env path]
  (cond
    (and (vector? path) (= :state (first path)))
    (let [path* (subvec path 1)]
      (if (= :item (first path*))
        (get-in (:item env) (subvec path* 1))
        (get-in (:state env) path*)))
    (and (vector? path) (= :item (first path)))
    (get-in (:item env) (subvec path 1))
    (and (vector? path) (= :parent (first path)))
    (get-in (:state env) (subvec path 1))
    :else path))

(defn- bound-value [env node key]
  (state-value env (get-in node [:bind key])))

(defn- dimension [value fallback]
  (cond
    (number? value) (float value)
    (= :fill value) (float fallback)
    :else (float fallback)))

(defn- rect-for [parent {:keys [x y width height]}]
  (let [{px :x py :y pw :width ph :height} parent]
    {:x (+ px (float (or x 0.0)))
     :y (+ py (float (or y 0.0)))
     :width (dimension width pw)
     :height (dimension height ph)}))

(defn- child-rects [rect direction children]
  (let [count* (max 1 (count children))
        horizontal (= :row direction)
        available (if horizontal (:width rect) (:height rect))
        each (/ available count*)]
    (mapv (fn [index child]
            (let [layout (:layout child)]
              (if horizontal
                (assoc rect :x (+ (:x rect) (* index each))
                           :width (dimension (:width layout) each))
                (assoc rect :y (+ (:y rect) (* index each))
                           :height (dimension (:height layout) each)))))
          (range) children)))

(defn- component [value default]
  (let [number (double (or value default))]
    (if (<= 0.0 number 1.0)
      (Math/round (* 255.0 number))
      (Math/round number))))

(defn- rgba [value fallback]
  (cond
    (integer? value) (unchecked-int value)
    (map? value) (unchecked-int
                   (bit-or (bit-shift-left (component (:a value) 255) 24)
                           (bit-shift-left (component (:r value) 255) 16)
                           (bit-shift-left (component (:g value) 255) 8)
                           (component (:b value) 255)))
    (vector? value) (let [[r g b a] value]
                      (unchecked-int
                       (bit-or (bit-shift-left (component a 255) 24)
                               (bit-shift-left (component r 255) 16)
                               (bit-shift-left (component g 255) 8)
                               (component b 255))))
    :else (unchecked-int fallback)))

(defn- item-label [item]
  (cond
    (nil? item) ""
    (map? item) (str (or (:label item) (:text item) (:name item)
                        (:title item) (:skill-id item) ""))
    :else (str item)))

(defn- composite-resource [source]
  (let [[namespace path] (clojure.string/split (str source) #":" 2)]
    (UiResourceRef. (or (not-empty namespace) "academy")
                    (or (not-empty path) (str source))
                    UiResourceRef$Kind/TEXTURE)))

(defn- command-for [node rect env]
  (let [type (:type node)
        value (bound-value env node :value)
        text-value (bound-value env node :text)
        visible-value (bound-value env node :visible)
        style-rgba (get-in node [:style :rgba])
        bound-color (or (bound-value env node :rgba)
                        (bound-value env node :alpha))
        rgba* (rgba (or style-rgba bound-color) 0xFFFFFFFF)
        x (float (:x rect)) y (float (:y rect))
        width (float (:width rect)) height (float (:height rect))]
    (case type
      :rect [(RenderCommand$UiQuadBatch.
               [(RenderCommand$UiQuad. x y width height rgba*)])]
      :progress (let [ratio (float (max 0.0 (min 1.0
                                                (double (cond
                                                         (boolean? value) (if value 1.0 0.0)
                                                         (number? value) value
                                                         :else 0.0)))))]
                  [(RenderCommand$UiQuadBatch.
                    [(RenderCommand$UiQuad. x y width height (unchecked-int 0x55202020))
                     (RenderCommand$UiQuad. x y (* width ratio) height
                                             (unchecked-int 0xFF35C7FF))])])
      :text [(RenderCommand$UiText. 0 (item-label text-value) x y rgba*)]
      :button [(RenderCommand$UiQuadBatch.
                 [(RenderCommand$UiQuad. x y width height rgba*)])
               (RenderCommand$UiText. 0
                                       (item-label (or (when (map? text-value)
                                                         (:label text-value))
                                                       text-value
                                                       (get-in node [:semantics :label])))
                                       (+ x 4.0) (+ y 4.0)
                                       (unchecked-int 0xFFFFFFFF))]
      :portal [(RenderCommand$UiQuadBatch.
                 [(RenderCommand$UiQuad. x y width height (unchecked-int 0xAA000000))])
                (RenderCommand$UiText. 0 (item-label visible-value)
                                        (+ x 6.0) (+ y 6.0)
                                        (unchecked-int 0xFFFFFFFF))]
      :text-input [(RenderCommand$UiQuadBatch.
                     [(RenderCommand$UiQuad. x y width height
                                             (unchecked-int 0x66000000))])
                   (RenderCommand$UiText. 0 (item-label text-value)
                                           (+ x 4.0) (+ y 4.0)
                                           (unchecked-int 0xFFFFFFFF))]
      :image (when-let [resource (get-in node [:style :resource])]
               [(RenderCommand$UiImageBatch.
                  (UiResourceRef. (str (or (:namespace resource) "academy"))
                                  (str (:path resource))
                                  UiResourceRef$Kind/TEXTURE)
                  [(RenderCommand$UiImage. x y width height rgba*)])])
      :nine-slice [(RenderCommand$UiQuadBatch.
                    [(RenderCommand$UiQuad. x y width height rgba*)])]
      :line [(RenderCommand$UiQuadBatch.
              [(RenderCommand$UiQuad. x y width (max 1.0 height) rgba*)])]
      :gradient [(RenderCommand$UiQuadBatch.
                  [(RenderCommand$UiQuad. x y width height rgba*)])]
      :radial-progress (let [ratio (float (max 0.0 (min 1.0 (double (or value 0.0)))))]
                         [(RenderCommand$UiQuadBatch.
                           [(RenderCommand$UiQuad. x y width height (unchecked-int 0x55202020))
                            (RenderCommand$UiQuad. x y (* width ratio) height rgba*)])])
      :item-preview (let [item-id (int (or (when (map? value) (:item-id value)) value 0))
                          scale (float (or (get-in node [:style :scale]) 1.0))]
                      [(RenderCommand$UiItemPreview. item-id (+ x (/ width 2.0)) (+ y (/ height 2.0)) scale)])
      :model-preview (let [model-id (str (or (when (map? value) (:model-id value)) value ""))]
                       [(RenderCommand$UiModelPreview. model-id x y width height)])
      :slot-anchor (let [item (or (:item env) {})
                         ix (float (or (:x item) x))
                         iy (float (or (:y item) y))
                         iw (float (or (:width item) width))
                         ih (float (or (:height item) height))]
                     [(RenderCommand$UiQuadBatch.
                       [(RenderCommand$UiQuad. ix iy iw ih (unchecked-int 0x22000000))])])
      :clip [(RenderCommand$PushClip. x y width height)]
      :composite (let [item (or (:item env) {})
                       kind (:kind item)
                       ix (float (or (:x item) x))
                       iy (float (or (:y item) y))
                       iw (float (or (:w item) width))
                       ih (float (or (:h item) height))
                       color (rgba (:rgba item) rgba*)]
                   (case kind
                     :quad [(RenderCommand$UiQuadBatch.
                              [(RenderCommand$UiQuad. ix iy iw ih color)])]
                     :image [(RenderCommand$UiImageBatch.
                               (composite-resource (:src item))
                               [(RenderCommand$UiImage. ix iy iw ih color)])]
                     :text [(RenderCommand$UiText. 0 (item-label (:text item)) ix iy color)]
                     :model [(RenderCommand$UiModelPreview.
                              (str (or (:model-id item) (:src item) ""))
                              ix iy iw ih)]
                     []))
      :transform [(RenderCommand$Transform. (str (or (get-in node [:style :transform-id]) "identity")) (or (get-in node [:style :transform]) {}))]
      :mask [(RenderCommand$Mask. (str (or (get-in node [:style :mask-id]) "none")) (or (get-in node [:style :mask]) {}))]
      nil)))

(defn- collection-values [env node]
  (let [items (bound-value env node :items)]
    (if (sequential? items) (vec items) [])))

(defn- item-template [node]
  (or (first (:children node))
      {:type :text :key (keyword (str (name (or (:key node) :item)) "/item"))
       :bind {:text [:state :item]}}))

(declare paint-node)

(defn- collection-item-rects [node rect env items]
  (let [template (or (first (:children node)) {:type :text :layout {}})
        direction (if (= :grid (:type node)) :row :column)
        layout (:layout template)
        count* (max 1 (count items))
        fallback (if (= :row direction) (/ (:width rect) count*) (/ (:height rect) count*))
        extent (dimension (if (= :row direction) (:width layout) (:height layout)) fallback)
        offset (float (or (get-in env [:scroll-offsets (:key node)]) 0.0))]
    (mapv (fn [index _item]
            (if (= :row direction)
              (assoc rect :x (+ (:x rect) (* index extent) (- offset)) :width extent)
              (assoc rect :y (+ (:y rect) (* index extent) (- offset)) :height extent)))
          (range) items)))

(defn- paint-collection [node rect env]
  (let [items (collection-values env node)
        templates (if (= :repeater (:type node))
                    (if (seq (:children node)) (:children node) [(item-template node)])
                    [(item-template node)])
        item-rects (collection-item-rects node rect env items)]
    (vec
     (mapcat (fn [item item-rect]
               (let [item-env (assoc env :item item)]
                 (mapcat (fn [template]
                           (paint-node template item-rect item-env))
                         templates)))
             items item-rects))))
(defn- paint-node [node rect env]
  (let [type (:type node)
        visible (bound-value env node :visible)]
    (if (and (some? visible) (not (boolean visible)))
      []
      (let [children (:children node)
            direction (or (get-in node [:layout :direction])
                          (when (= :row type) :row)
                          (when (= :column type) :column))]
        (cond
          (= :scroll type)
          (vec (concat [(RenderCommand$PushClip. (float (:x rect)) (float (:y rect))
                                             (float (:width rect)) (float (:height rect)))]
                       (paint-collection node rect env)
                       [(RenderCommand$PopClip.)]))

          (#{:grid :repeater} type)
          (paint-collection node rect env)

          :else
          (let [child-rects (if direction
                              (child-rects rect direction children)
                              (mapv (constantly rect) children))]
            (let [commands (command-for node rect env)
                  child-commands (mapcat (fn [child child-rect]
                                           (paint-node child child-rect env))
                                         children child-rects)]
              (vec (concat commands child-commands
                           (when (= :clip type) [(RenderCommand$PopClip.)])
                           (when (= :transform type)
                             [(RenderCommand$Transform. "identity" {})])
                           (when (= :mask type)
                             [(RenderCommand$Mask. "none" {})]))))))))))
(defn- geometry-dimension [geometry key accessor]
  (cond
    (map? geometry) (get geometry key)
    (some? geometry) (try (accessor geometry) (catch Throwable _ nil))
    :else nil))

(defn- normalized-geometry [geometry]
  {:x (float (or (geometry-dimension geometry :origin-x #(.originX ^cn.li.presentation.core.HostGeometry %)) 0.0))
   :y (float (or (geometry-dimension geometry :origin-y #(.originY ^cn.li.presentation.core.HostGeometry %)) 0.0))
   :width (float (max 1 (or (geometry-dimension geometry :viewport-width #(.viewportWidth ^cn.li.presentation.core.HostGeometry %)) 1)))
   :height (float (max 1 (or (geometry-dimension geometry :viewport-height #(.viewportHeight ^cn.li.presentation.core.HostGeometry %)) 1)))
   :scale (float (or (geometry-dimension geometry :scale #(.scale ^cn.li.presentation.core.HostGeometry %)) 1.0))})

(defn paint-view [artifact state geometry]
  (let [root (:nodes artifact)
        host (normalized-geometry geometry)
        rect {:x (:x host) :y (:y host)
              :width (:width host) :height (:height host)}]
     (vec (paint-node root (rect-for rect (:layout root))
                          {:state state
                           :scroll-offsets (get state :presentation/scroll-offsets)}))))
