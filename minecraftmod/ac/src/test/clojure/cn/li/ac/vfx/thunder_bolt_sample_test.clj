(ns cn.li.ac.vfx.thunder-bolt-sample-test
  "Locks the V4 Thunder Bolt graph to main's three-bolt plus AOE-chain shape."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [cn.li.vfx.scene :as scene]))

(defn- load-thunder-bolt-vfx []
  (edn/read-string
   (slurp (io/resource "ac/vfx-v4/arc-strike-transient.edn"))))

(deftest thunder-bolt-v4-renders-main-and-chain-arcs-test
  (let [program (scene/compile-v4-document! (load-thunder-bolt-vfx))
        ops (scene/sample!
             program
             {:capabilities
              {:start {:x 0.0 :y 64.0 :z 0.0}
               :end {:x 0.0 :y 64.0 :z 20.0}
               :pattern :strong
               :arc-life-ticks 20
               :duration-ticks 20
               :hand-origin? true
               :aoe-origin {:x 0.0 :y 64.0 :z 20.0}
               :aoe-points [{:position {:x 2.0 :y 64.0 :z 20.0}
                             :eye-height 1.8}]
               :sound-id "academy:em.arc_strong"
               :sound-volume 0.6
               :sound-pitch 1.0
               :sound-position {:x 0.0 :y 64.0 :z 0.0}
               :age 0.0
               :progress 0.0
               :seed 17}})
        arcs (filter #(= :arc (:kind %)) ops)]
    (is (= 2 (count arcs)))
    (is (= {:pattern :strong :bolt-count 3 :hand-origin? true}
           (select-keys (first arcs) [:pattern :bolt-count :hand-origin?])))
    (is (= {:pattern :aoe
            :end-points [{:position {:x 2.0 :y 64.0 :z 20.0}
                          :eye-height 1.8}]}
           (select-keys (second arcs) [:pattern :end-points])))
    (is (= 1 (count (filter #(= :audio-one-shot (:kind %)) ops))))))
