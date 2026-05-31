(ns cn.li.ac.terminal.player-data-test
  (:require 
            [cn.li.ac.ability.service.runtime-store :as store]
[clojure.test :refer [deftest is use-fixtures]]            [cn.li.ac.test.support.player-state :as ps-fix]
            [cn.li.ac.terminal.player-data :as td]
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

(deftest fresh-terminal-data-and-nbt-roundtrip-test
  (let [t (td/fresh-terminal-data)
        nbt (td/terminal-data->nbt t)
        back (td/nbt->terminal-data nbt)]
    (is (false? (:terminal-installed? t)))
    (is (= #{} (:installed-apps t)))
    (is (= t back)))
  (let [t {:terminal-installed? true :installed-apps #{:app-a :app-b}}
        back (td/nbt->terminal-data (td/terminal-data->nbt t))]
    (is (= t back))))

(deftest terminal-ops-on-player-state-test
  (store/set-player-state!* ps-fix/test-session-id "p1" (store/fresh-player-state))
  (is (false? (td/terminal-installed? "p1")))
  (td/install-terminal! "p1")
  (is (true? (td/terminal-installed? "p1")))
  (td/install-app! "p1" :media)
  (is (td/app-installed? "p1" :media))
  (is (= #{:media} (td/get-installed-apps "p1")))
  (td/install-multiple-apps! "p1" #{:map :notes})
  (is (= #{:media :map :notes} (td/get-installed-apps "p1")))
  (td/uninstall-app! "p1" :map)
  (is (= #{:media :notes} (td/get-installed-apps "p1")))
  (td/uninstall-terminal! "p1")
  (is (false? (td/terminal-installed? "p1")))
  (is (= #{} (td/get-installed-apps "p1"))))

(deftest ensure-terminal-data-test
  (store/set-player-state!* ps-fix/test-session-id "p2" (-> (store/fresh-player-state)
                                 (dissoc :terminal-data)))
  (td/ensure-terminal-data! "p2")
  (is (= (td/fresh-terminal-data)
         (:terminal-data (store/get-player-state* ps-fix/test-session-id "p2")))))


