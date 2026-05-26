(ns cn.li.ac.ability.server.service.delayed-projectiles-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.server.service.delayed-projectiles :as dp]
            [cn.li.mcmod.platform.entity-damage]))

(defn- reset-pending-tasks-fixture [f]
  (dp/reset-pending-tasks-for-test!)
  (try
    (f)
    (finally
      (dp/reset-pending-tasks-for-test!))))

(use-fixtures :each reset-pending-tasks-fixture)

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
      (is (= "e1" @hit))
      (is (nil? (get (dp/pending-tasks-snapshot) "p1"))))))

    (deftest pending-tasks-are-player-keyed-and-clearable-test
      (dp/schedule-task! "p1" 2 {:kind :unknown :payload 1})
      (dp/schedule-task! "p2" 2 {:kind :unknown :payload 2})
      (is (= #{"p1" "p2"} (set (keys (dp/pending-tasks-snapshot)))))
      (dp/clear-player-tasks! "p1")
      (is (nil? (get (dp/pending-tasks-snapshot) "p1")))
      (is (= 1 (count (get (dp/pending-tasks-snapshot) "p2")))))
