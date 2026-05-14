(ns cn.li.ac.test.support.player-state
  (:require [cn.li.ac.ability.service.player-state :as ps]))

(defn clean-player-states-fixture
  [f]
  (reset! ps/player-states {})
  (f)
  (reset! ps/player-states {}))

(defn seed-player-state!
  [uuid state]
  (ps/set-player-state! uuid state))
