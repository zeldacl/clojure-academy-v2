(ns cn.li.presentation.core.transform-test
  (:require [clojure.test :refer :all]
            [cn.li.presentation.core.transform :as transform]))

(deftest forward-inverse-roundtrip
  (let [xf {:panel-scale 0.5 :tilt-degrees 0.0}
        rect {:x 100.0 :y 50.0 :width 200.0 :height 100.0}
        [sx sy] (transform/forward-point xf rect 150.0 75.0)
        [lx ly] (transform/inverse-point xf rect sx sy)]
    (is (< (Math/abs (- lx 150.0)) 1e-4))
    (is (< (Math/abs (- ly 75.0)) 1e-4))))

(deftest forward-scales-about-center
  (let [xf {:panel-scale 0.5 :tilt-degrees 0.0}
        rect {:x 0.0 :y 0.0 :width 100.0 :height 100.0}
        ;; Point at right edge of content → halfway toward center after 0.5 scale.
        [sx sy] (transform/forward-point xf rect 100.0 50.0)]
    (is (< (Math/abs (- sx 75.0)) 1e-4))
    (is (< (Math/abs (- sy 50.0)) 1e-4))))

(deftest normalize-reads-panel-scale
  (is (= 0.35 (:panel-scale (transform/normalize {:panel-scale 0.35 :tilt-degrees 2.0}))))
  (is (= 2.0 (:tilt-degrees (transform/from-style-entry {:transform {:panel-scale 1.0 :tilt-degrees 2.0}})))))
