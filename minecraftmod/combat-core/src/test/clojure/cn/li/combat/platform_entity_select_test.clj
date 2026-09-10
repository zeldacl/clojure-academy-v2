(ns cn.li.combat.platform-entity-select-test
  "Regression: a nil :filter arg must not shadow clojure.core/filter
   (beam-trace omits :filter; that NPE crashed release on Server thread)."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.combat.platform :as platform]
            [cn.li.mcmod.platform.block-manipulation :as blocks]
            [cn.li.mcmod.platform.world-effects :as world-effects]))

(deftest entity-select-without-filter-does-not-npe-test
  (with-redefs [world-effects/available? (constantly true)
                world-effects/find-entities-in-aabb
                (fn [& _]
                  [{:id "e1" :type "minecraft:zombie"
                    :x 1.0 :y 0.0 :z 0.0
                    :width 0.6 :height 1.8
                    :position {:x 1.0 :y 0.0 :z 0.0}
                    :living? true}])]
    (let [result (platform/entity-select!
                  {:owner "player-1"
                   :world-id "minecraft:overworld"
                   :shape {:type :line
                           :start [0.0 0.0 0.0]
                           :end [8.0 0.0 0.0]
                           :radius 0.5}
                   :projection [:id :type :living?]
                   :limit 8}
                  {})]
      ;; Geometry may drop the stub entity; the regression is that a nil
      ;; :filter used to NPE by shadowing clojure.core/filter.
      (is (vector? result)))))

(deftest beam-trace-without-filter-does-not-npe-test
  (with-redefs [world-effects/available? (constantly true)
                world-effects/find-entities-in-aabb (fn [& _] [])
                blocks/available? (constantly false)]
    (let [result (platform/beam-trace!
                  {:owner "player-1"
                   :world-id "minecraft:overworld"
                   :origin {:vec3 [0.0 1.0 0.0]}
                   :direction {:vec3 [0.0 0.0 1.0]}
                   :length 16.0
                   :radius 0.5
                   :entity-limit 8
                   :block-limit 16}
                  {})]
      (is (= [] (:entities result)))
      (is (= [] (:blocks result))))))
