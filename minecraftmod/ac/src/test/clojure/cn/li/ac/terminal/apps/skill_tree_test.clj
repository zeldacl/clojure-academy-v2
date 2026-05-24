(ns cn.li.ac.terminal.apps.skill-tree-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.terminal.apps.skill-tree :as skill-tree]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(deftest open-skill-tree-gui-forwards-learn-context-test
  (testing "explicit learn-context is forwarded to the platform bridge"
    (let [calls (atom [])]
      (with-redefs [uuid/player-uuid (fn [_] "player-uuid-1")
                    entity/player-get-name (fn [_] "Tester")
                    client-bridge/open-skill-tree-screen!
                    (fn [player-uuid learn-context]
                      (swap! calls conj {:player-uuid player-uuid
                                         :learn-context learn-context}))]
        (skill-tree/open-skill-tree-gui :player-1 {:developer-type :portable})
        (skill-tree/open-skill-tree-gui :player-2)
        (is (= [{:player-uuid "player-uuid-1"
                 :learn-context {:developer-type :portable}}
                {:player-uuid "player-uuid-1"
                 :learn-context nil}]
               @calls))))))