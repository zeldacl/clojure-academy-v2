(ns cn.li.ability.editor.render-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ability.editor.render :as render]))

(deftest wire-quads-are-all-axis-aligned-test
  (let [quads (render/wire-quads 10.0 20.0 200.0 80.0 2.0 0xFFFFFFFF)]
    (is (= 3 (count quads)))
    (is (every? #(and (>= (:w %) 0.0) (>= (:h %) 0.0)) quads))))

(deftest wire-quads-degenerate-same-point-does-not-throw-test
  (let [quads (render/wire-quads 5.0 5.0 5.0 5.0 2.0 0xFF000000)]
    (is (= 3 (count quads)))))

(deftest default-layout-covers-every-nid-with-no-duplicates-test
  (let [nids ["n1" "n2" "n3" "n4" "n5" "n6" "n7" "n8"]
        layout (render/default-layout nids)]
    (is (= (set nids) (set (keys layout))))
    (is (every? #(and (contains? % :x) (contains? % :y)) (vals layout)))
    ;; distinct positions -- no two nodes stacked exactly on top of each other
    (is (= (count nids) (count (set (vals layout)))))))

(deftest resolve-layout-prefers-stored-position-over-fallback-test
  (let [nids ["n1" "n2"]
        stored {"n1" {:x 999.0 :y 999.0}}
        resolved (render/resolve-layout stored nids)]
    (is (= {:x 999.0 :y 999.0} (get resolved "n1")))
    (is (contains? resolved "n2"))
    (is (not= {:x 999.0 :y 999.0} (get resolved "n2")))))

(deftest resolve-layout-handles-empty-stored-layout-test
  (let [nids ["n1" "n2" "n3"]
        resolved (render/resolve-layout {} nids)]
    (is (= 3 (count resolved)))))
