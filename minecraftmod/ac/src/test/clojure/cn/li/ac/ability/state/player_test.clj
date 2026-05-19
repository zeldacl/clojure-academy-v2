(ns cn.li.ac.ability.state.player-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.test.support.player-state :as ps-fix]
            [cn.li.ac.ability.service.player-state :as ps]))

(use-fixtures :each ps-fix/clean-player-states-fixture)

(deftest fresh-state-shape-test
  (let [s (ps/fresh-state)]
    (is (map? (:ability-data s)))
    (is (map? (:resource-data s)))
    (is (map? (:cooldown-data s)))
    (is (map? (:preset-data s)))
    (is (map? (:develop-data s)))
    (is (map? (:terminal-data s)))
    (is (false? (:dirty? s)))))

(deftest get-or-create-player-state-test
  (let [a (ps/get-or-create-player-state! "u1")
        b (ps/get-or-create-player-state! "u1")]
    (is (identical? a b))))

(deftest update-ability-data-marks-dirty-test
  (ps/get-or-create-player-state! "u2")
  (is (false? (ps/dirty? "u2")))
  (ps/update-ability-data! "u2" assoc :category-id :x)
  (is (true? (ps/dirty? "u2")))
  (ps/mark-clean! "u2")
  (is (false? (ps/dirty? "u2"))))

(deftest server-tick-player-smoke-test
  (ps/get-or-create-player-state! "u3")
  (let [r (ps/server-tick-player! "u3" nil)]
    (is (map? r))
    (is (vector? (:events r)))))

(deftest remove-player-state-test
  (ps/set-player-state! "u4" (ps/fresh-state))
  (is (some? (ps/get-player-state "u4")))
  (ps/remove-player-state! "u4")
  (is (nil? (ps/get-player-state "u4"))))

(deftest persisted-state-edn-roundtrip-keeps-core-data-test
  (let [state (-> (ps/fresh-state)
                  (assoc-in [:ability-data :category-id] :electromaster)
                  (update-in [:ability-data :learned-skills] conj :railgun)
                  (assoc-in [:ability-data :skill-exps :railgun] 0.75)
                  (assoc-in [:resource-data :cur-cp] 42.0)
                  (assoc-in [:cooldown-data [:railgun :main]] 20)
                  (assoc-in [:preset-data :slots [0 0]] [:electromaster :railgun])
                  (assoc-in [:develop-data :state] :developing)
                  (assoc-in [:terminal-data :terminal-installed?] true)
                  (update-in [:terminal-data :installed-apps] conj :skill-tree)
                  (assoc :dirty? true))
        persisted (dissoc state :dirty?)
        decoded (edn/read-string (pr-str persisted))]
    (is (= persisted decoded))
    (is (false? (contains? decoded :dirty?)))
    (is (= #{:ability-data :resource-data :cooldown-data :preset-data :develop-data :terminal-data}
           (set (keys decoded))))
    (is (= [:electromaster :railgun]
           (get-in decoded [:preset-data :slots [0 0]])))
    (is (= #{:skill-tree}
           (get-in decoded [:terminal-data :installed-apps])))))
