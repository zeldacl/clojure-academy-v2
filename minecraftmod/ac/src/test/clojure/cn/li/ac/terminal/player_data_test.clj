(ns cn.li.ac.terminal.player-data-test
  (:require 
            [cn.li.ac.ability.service.player-state-core :as ps-core]
[clojure.test :refer [deftest is use-fixtures]]            [cn.li.ac.test.support.player-state :as ps-fix]
            [cn.li.ac.terminal.player-data :as td]
            [cn.li.mcmod.util.log :as log]))

(defn- each-fixture [f]
  (ps-fix/with-test-player-state-owner
    (fn []
      (ps-core/reset-player-states-for-test!)
      (try
        (with-redefs [log/info (fn [& _])
                      log/warn (fn [& _])]
          (f))
        (finally
          (ps-core/reset-player-states-for-test!))))))

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
  (ps-core/set-player-state! "p1" (ps-core/fresh-state))
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
  (ps-core/set-player-state! "p2" (-> (ps-core/fresh-state)
                                 (dissoc :terminal-data)))
  (td/ensure-terminal-data! "p2")
  (is (= (td/fresh-terminal-data)
         (:terminal-data (ps-core/get-player-state "p2")))))


