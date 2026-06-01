(ns cn.li.ac.terminal.player-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.terminal.model :as model]
            [cn.li.ac.terminal.player :as player]
            [cn.li.ac.test.support.player-state :as ps-fix]
            [cn.li.mcmod.util.log :as log]))

(defn- each-fixture [f]
  (ps-fix/with-test-player-state-owner
    (fn []
      (store/reset-store!)
      (try
        (with-redefs [log/info (fn [& _])
                      log/warn (fn [& _])]
          (f))
        (finally
          (store/reset-store!))))))

(use-fixtures :each each-fixture)

(deftest session-scoped-terminal-ops-test
  (let [sid ps-fix/test-session-id]
    (store/set-player-state!* sid "p1" (store/fresh-player-state))
    (is (false? (player/terminal-installed? sid "p1")))
    (player/install-terminal! sid "p1")
    (is (true? (player/terminal-installed? sid "p1")))
    (player/install-app! sid "p1" :media-player)
    (is (player/app-installed? sid "p1" :media-player))
    (is (= #{:media-player} (player/installed-apps sid "p1")))
    (player/install-apps! sid "p1" #{:map :notes})
    (is (= #{:media-player :map :notes} (player/installed-apps sid "p1")))
    (player/uninstall-app! sid "p1" :map)
    (is (= #{:media-player :notes} (player/installed-apps sid "p1")))
    (player/uninstall-terminal! sid "p1")
    (is (false? (player/terminal-installed? sid "p1")))
    (is (= #{} (player/installed-apps sid "p1")))))

(deftest ensure-state-test
  (let [sid ps-fix/test-session-id]
    (store/set-player-state!* sid "p2" (-> (store/fresh-player-state)
                                           (dissoc model/state-key)))
    (player/ensure-state! sid "p2")
    (is (= (model/fresh-state)
           (player/state sid "p2")))))
