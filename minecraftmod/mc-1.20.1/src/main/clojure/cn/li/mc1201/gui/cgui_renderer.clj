(ns cn.li.mc1201.gui.cgui-renderer
  "CLIENT-ONLY render tree traversal helpers extracted from cgui_runtime_impl."
  (:require [cn.li.mcmod.gui.cgui :as cgui]))

(defn collect-widgets-z-ordered
  [root abs-pos parent-scale parent-size]
  (when (and root (cgui/visible? root))
    (let [pos (cgui/get-pos root)
          own-scale (double (or @(:scale root) 1.0))
          cum-scale (* parent-scale own-scale)
          [px py] abs-pos
          [wx wy] pos
          [pw ph] (or parent-size [0 0])
          [w h] (cgui/get-size root)
          tm (get @(:metadata root) :transform-meta {})
          pivot-x (or (:pivot-x tm) 0.0)
          pivot-y (or (:pivot-y tm) 0.0)
          align-w (when-let [a (:align-width tm)] (-> a name clojure.string/lower-case keyword))
          align-h (when-let [a (:align-height tm)] (-> a name clojure.string/lower-case keyword))
          align-offset-x (case align-w :center (int (Math/round (/ (- pw w) 2.0))) :right (int (Math/round (- pw w))) 0)
          align-offset-y (case align-h :center (int (Math/round (/ (- ph h) 2.0))) :bottom (int (Math/round (- ph h))) 0)
          pivot-shift-x (* pivot-x w)
          pivot-shift-y (* pivot-y h)
          abs-x (+ px align-offset-x wx (- pivot-shift-x))
          abs-y (+ py align-offset-y wy (- pivot-shift-y))
          children (cgui/get-widgets root)
          next-parent-size [w h]
          next-pos [abs-x abs-y]]
      (cons [root next-pos cum-scale]
            (mapcat #(collect-widgets-z-ordered % next-pos cum-scale next-parent-size) children)))))
