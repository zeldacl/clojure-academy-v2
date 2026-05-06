(ns cn.li.ac.terminal.media-backend-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.terminal.apps.media-backend :as mb]))

(deftest volume-and-track-selection-test
  (is (= 1.0 (mb/set-volume! 2.0)))
  (is (= 0.0 (mb/set-volume! -1.0)))
  (is (= :sisters-noise (:id (mb/select-track! 0))))
  (is (contains? #{:only-my-railgun :level5-judgelight :sisters-noise}
                 (:id (mb/next-track!)))))

(deftest pause-toggle-state-test
  (with-redefs [cn.li.ac.ability.client.effects.sounds/queue-sound-effect! (fn [_] nil)]
    (is (= :playing (mb/toggle-pause!)))
    (is (= :paused (mb/toggle-pause!)))))
