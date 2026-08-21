(ns cn.li.ac.ability.server.service.delayed-projectiles-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.combat.deferred :as delayed]))

(deftest generic-beam-scheduler-keeps-bounded-deadline-test
  (delayed/clear-all!)
  (delayed/schedule!
   {:owner "owner"
    :world-id "world"
    :origin {:x 0.0 :y 64.0 :z 0.0}
    :destination {:x 0.0 :y 64.0 :z 15.0}
    :damage 4.0
    :damage-type :magic
    :delay-ticks 3})
  (is (= 1 (count (delayed/pending "owner"))))
  (is (= 3 (:ticks-left (first (delayed/pending "owner")))))
  (delayed/clear-all!))

(deftest generic-beam-scheduler-clamps-zero-delay-test
  (delayed/clear-all!)
  (delayed/schedule!
   {:owner "owner" :origin {} :destination {} :damage 1.0 :delay-ticks 0})
  (is (= 1 (:ticks-left (first (delayed/pending "owner")))))
  (delayed/clear-all!))
