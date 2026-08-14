(ns cn.li.presentation.core.layout
  "Pure Clojure layout engine for retained presentation nodes.

   The engine intentionally returns immutable layout maps; render backends
   consume the result but never decide geometry."
  (:require [cn.li.presentation.core.tree :as tree]))

(defn- finite [value fallback]
  (if (and (number? value) (Double/isFinite (double value)))
    (double value)
    (double fallback)))

(defn- clamp-size [value props axis]
  (let [min-key (if (= axis :width) :min-width :min-height)
        max-key (if (= axis :width) :max-width :max-height)]
    (max (finite (get props min-key) 0.0)
         (min (finite (get props max-key Double/POSITIVE_INFINITY)
                     Double/POSITIVE_INFINITY)
              (max 0.0 (finite value 0.0))))))

(defn- content-size [children axis]
  (if (= axis :width)
    (reduce max 0.0 (map #(double (get-in % [:layout :width] 0.0)) children))
    (reduce + 0.0 (map #(double (get-in % [:layout :height] 0.0)) children))))

(defn- resolve-size [value available content]
  (cond
    (number? value) (double value)
    (= value :content) (double content)
    (= value :fill) (double available)
    (and (vector? value) (= :fraction (first value))
         (number? (second value))) (* available (double (second value)))
    (and (map? value) (= :fraction (:kind value)))
    (* available (double (or (:value value) 0.0)))
    :else (double available)))

(defn- child-main-size [child axis]
  (let [props (:props child)
        key (if (= axis :width) :width :height)]
    (when (contains? props key) (get props key))))

(declare layout-node)

(defn- layout-children [node width height]
  (let [props (:props node)
        direction (or (:direction props) (if (= (:type node) :row) :row :column))
        children (:children node)
        gap (max 0.0 (finite (:gap props) 0.0))
        horizontal (= direction :row)
        total-main (if horizontal width height)
        cross (if horizontal height width)
        fixed (reduce + 0.0 (keep (fn [child]
                                    (let [spec (child-main-size child (if horizontal :width :height))]
                                      (when (and spec (not= spec :fill))
                                        (resolve-size spec total-main 0.0))))
                                  children))
        fill-count (count (filter #(#{:fill nil} (child-main-size % (if horizontal :width :height))) children))
        remaining (max 0.0 (- total-main (* gap (max 0 (dec (count children)))) fixed))]
    (loop [remaining-children children
           offset 0.0
           result []]
      (if-let [child (first remaining-children)]
        (let [main-spec (child-main-size child (if horizontal :width :height))
              main (if (or (= main-spec :fill) (nil? main-spec))
                     (if (pos? fill-count) (/ remaining fill-count) 0.0)
                     (resolve-size main-spec total-main 0.0))
              child-width (if horizontal main cross)
              child-height (if horizontal cross main)
              child-x (if horizontal offset 0.0)
              child-y (if horizontal 0.0 offset)
              laid (layout-node child child-x child-y child-width child-height true)]
          (recur (next remaining-children)
                 (+ offset main gap)
                 (conj result laid)))
        result))))

(defn layout-node
  "Layout a retained node inside x/y/available width/height."
  [node x y available-width available-height & [allocated?]]
  (let [props (:props node)
        content-w (content-size (:children node) :width)
        content-h (content-size (:children node) :height)
        width (clamp-size (if allocated?
                           available-width
                           (resolve-size (:width props) available-width content-w))
                          props :width)
        height0 (if allocated?
                  available-height
                  (resolve-size (:height props) available-height content-h))
        aspect (when (number? (:aspect-ratio props)) (double (:aspect-ratio props)))
        height (clamp-size (if (and aspect (pos? aspect) (not (contains? props :height)))
                             (/ width aspect) height0)
                           props :height)
        children (layout-children node width height)]
    (assoc node :layout {:x (double x) :y (double y)
                         :width width :height height
                         :children (mapv :layout children)}
           :children children)))

(defn layout
  "Measure and lay out a retained tree against viewport dimensions."
  [root width height]
  (when root (layout-node root 0.0 0.0 (double width) (double height))))
