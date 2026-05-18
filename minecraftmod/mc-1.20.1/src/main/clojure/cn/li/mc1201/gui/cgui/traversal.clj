(ns cn.li.mc1201.gui.cgui.traversal
  "CLIENT-ONLY widget tree traversal and hit-testing helpers for CGUI."
  (:require [clojure.string :as str]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]))

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
          align-w (when-let [a (:align-width tm)] (-> a name str/lower-case keyword))
          align-h (when-let [a (:align-height tm)] (-> a name str/lower-case keyword))
          align-offset-x (case align-w :center (int (Math/round (double (/ (- pw w) 2.0)))) :right (int (Math/round (double (- pw w)))) 0)
          align-offset-y (case align-h :center (int (Math/round (double (/ (- ph h) 2.0)))) :bottom (int (Math/round (double (- ph h)))) 0)
          pivot-shift-x (* pivot-x w)
          pivot-shift-y (* pivot-y h)
          abs-x (+ px align-offset-x wx (- pivot-shift-x))
          abs-y (+ py align-offset-y wy (- pivot-shift-y))
          children (cgui-core/get-widgets root)
          next-parent-size [w h]
          next-pos [abs-x abs-y]]
      (cons [root next-pos cum-scale]
            (mapcat #(collect-widgets-z-ordered % next-pos cum-scale next-parent-size) children)))))

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
