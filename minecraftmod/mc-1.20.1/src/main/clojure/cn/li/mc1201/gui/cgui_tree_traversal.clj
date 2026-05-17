(ns cn.li.mc1201.gui.cgui-tree-traversal
  "CLIENT-ONLY widget tree traversal and hit-testing helpers for CGUI."
  (:require [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mc1201.gui.cgui-renderer :as cgui-renderer]))

(defn collect-widgets-z-ordered
  [root abs-pos parent-scale parent-size]
  (cgui-renderer/collect-widgets-z-ordered root abs-pos parent-scale parent-size))

(defn hit-test
  "Return the deepest visible widget containing point `(mx,my)` in screen space."
  [root mx my left top]
  (let [mx-rel (- mx left)
        my-rel (- my top)]
    (loop [stack (list [root 0 0])
           best nil]
      (if (empty? stack)
        best
        (let [[node ox oy] (peek stack)
              rest-stack (pop stack)]
          (if (or (nil? node) (not (cgui-core/visible? node)))
            (recur rest-stack best)
            (let [pos (cgui-core/get-pos node)
                  size (cgui-core/get-size node)
                  [wx wy] pos
                  [w h] size
                  x0 (+ ox wx)
                  y0 (+ oy wy)
                  x1 (+ x0 w)
                  y1 (+ y0 h)
                  inside (and (>= mx-rel x0) (< mx-rel x1) (>= my-rel y0) (< my-rel y1))]
              (recur (reduce (fn [acc child]
                               (conj acc [child x0 y0]))
                             rest-stack
                             (reverse (cgui-core/get-widgets node)))
                     (if inside node best)))))))))
