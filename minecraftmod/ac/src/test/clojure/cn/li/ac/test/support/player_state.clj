(ns cn.li.ac.test.support.player-state
  (:require [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(def test-player-state-owner {:server-session-id :test-session})

(defn with-test-player-state-owner
  [f]
  (binding [runtime-hooks/*player-state-owner* test-player-state-owner]
    (f)))

(defn clean-player-states-fixture
  [f]
  (with-test-player-state-owner
    (fn []
      (ps/reset-player-states-for-test!)
      (try
        (f)
        (finally
          (ps/reset-player-states-for-test!))))))

(defn seed-player-state!
  [uuid state]
  (ps/set-player-state! uuid state))
