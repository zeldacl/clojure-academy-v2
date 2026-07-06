(ns cn.li.mc1201.gui.cgui.traversal
  "CLIENT-ONLY widget tree traversal and hit-testing helpers for CGUI."
  (:require [clojure.string :as str]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.events :as events]))

(defn collect-widgets-z-ordered
  [root abs-pos parent-scale parent-size]
  (when (and root (cgui-core/visible? root))
    (let [pos (cgui-core/get-pos root)
          own-scale (double (or @(:scale root) 1.0))
          cum-scale (* parent-scale own-scale)
          [px py] abs-pos
          [wx wy] pos
          [pw ph] (or parent-size [0 0])
          [w h] (cgui-core/get-size root)
          tm (get @(:metadata root) :transform-meta {})
          pivot-x (or (:pivot-x tm) 0.0)
          pivot-y (or (:pivot-y tm) 0.0)
          align-w (:align-width tm)
          align-h (:align-height tm)
          ;; LambdaLib2 uses SCALED widget dimensions for alignment offset.
          ;; e.g. logo1 raw 899×236, scale 0.25 → effective 224.75×59 centered in rightPart.
          sw (* w own-scale)
          sh (* h own-scale)
          align-offset-x (case align-w :center (/ (- pw sw) 2.0) :right (- pw sw) 0.0)
          align-offset-y (case align-h
                           :center (/ (- ph sh) 2.0)
                           :middle (/ (- ph sh) 2.0)
                           :bottom (- ph sh)
                           0.0)
          pivot-shift-x (* pivot-x w)
          pivot-shift-y (* pivot-y h)
          ;; LambdaLib2: child screen pos = parent.abs + child.transform * parent.cumulative_scale
          child-x (+ align-offset-x wx (- pivot-shift-x))
          child-y (+ align-offset-y wy (- pivot-shift-y))
          abs-x (+ px (* child-x parent-scale))
          abs-y (+ py (* child-y parent-scale))
          children (cgui-core/get-widgets root)
          next-parent-size [w h]
          next-pos [abs-x abs-y]]
      (cons [root next-pos cum-scale]
            (mapcat #(collect-widgets-z-ordered % next-pos cum-scale next-parent-size) children)))))

(defn- point-inside-widget?
  [mx-rel my-rel abs-x abs-y scale widget]
  (let [[w h] (cgui-core/get-size widget)
        x0 (double abs-x)
        y0 (double abs-y)
        x1 (+ x0 (* (double w) (double scale)))
        y1 (+ y0 (* (double h) (double scale)))]
    (and (>= (double mx-rel) x0)
         (< (double mx-rel) x1)
         (>= (double my-rel) y0)
         (< (double my-rel) y1))))

(defn hit-path
  "Return widgets from root to deepest hit along z-order (deepest last)."
  [root mx my left top]
  (let [mx-rel (- mx left)
        my-rel (- my top)
        ;; Pass root's own size as parent-size so alignment offsets compute to 0
        ;; (the root has no screen parent — it's positioned by left/top directly).
        root-size (cgui-core/get-size root)
        widgets (collect-widgets-z-ordered root [0 0] 1.0 root-size)]
  ;; Iterate in render order and keep the last matching widget (top-most in painter order).
    (loop [path []
           best nil
           remaining widgets]
      (if (empty? remaining)
        (if best (conj path best) path)
        (let [[widget [abs-x abs-y] scale] (first remaining)
              inside (point-inside-widget? mx-rel my-rel abs-x abs-y scale widget)]
          (recur (if inside (conj path widget) path)
                 (if inside widget best)
                 (rest remaining)))))))

(defn widget-has-click-handler?
  [widget event-key]
  (boolean (seq (events/get-widget-event-handlers widget event-key))))

(defn find-interactive-ancestor
  "From deepest hit, walk up the hit-path and return the nearest widget with a click handler."
  [hit-path event-key]
  (some #(when (widget-has-click-handler? % event-key) %)
        (reverse hit-path)))

(defn hit-test
  "Return the deepest visible widget containing point `(mx,my)` in screen space."
  [root mx my left top]
  (let [path (hit-path root mx my left top)]
    (when (seq path) (last path))))

(defn hit-test-interactive
  "Return deepest hit or nearest ancestor that handles `event-key` (:left-click / :right-click)."
  [root mx my left top event-key]
  (let [path (hit-path root mx my left top)]
    (or (find-interactive-ancestor path event-key)
        (when (seq path) (last path)))))
