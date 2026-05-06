(ns cn.li.ac.ability.server.service.delayed-projectiles-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.server.service.delayed-projectiles :as dp]))

(deftest mdball-near-expire-delay-test
  (is (= 48 (dp/mdball-near-expire-delay)))
  (is (= 1 (dp/mdball-near-expire-delay 1)))
  (is (= 8 (dp/mdball-near-expire-delay 10))))

(deftest schedule-and-run-tick-test
  (let [hit (atom nil)]
    (with-redefs [cn.li.mcmod.platform.entity-damage/*entity-damage* :damage
                  cn.li.mcmod.platform.entity-damage/apply-direct-damage! (fn [& _] true)]
      (dp/schedule-electron-missile-hit!
       {:player-id "p1"
        :delay-ticks 1
        :world-id "w"
        :target-uuid "e1"
        :damage 3.0
        :on-hit! (fn [uuid] (reset! hit uuid))})
      (dp/tick-player! "p1")
      (is (= "e1" @hit)))))
