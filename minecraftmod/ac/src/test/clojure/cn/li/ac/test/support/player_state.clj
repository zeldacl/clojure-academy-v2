(ns cn.li.ac.test.support.player-state
  (:require 
            [cn.li.ac.ability.service.runtime-store :as store]
[cn.li.mcmod.hooks.core :as runtime-hooks]))

(def test-player-state-owner {:server-session-id :test-session})

(def test-session-id
  (:server-session-id test-player-state-owner))

(defn with-test-player-state-owner
  [f]
  (binding [runtime-hooks/*player-state-owner* test-player-state-owner]
    (f)))

(defn clean-player-states-fixture
  [f]
  (with-test-player-state-owner
    (fn []
      (store/reset-store!)
      (try
        (f)
        (finally
          (store/reset-store!))))))

(defn seed-player-state!
  [uuid state]
  (store/set-player-state!* test-session-id uuid state))


