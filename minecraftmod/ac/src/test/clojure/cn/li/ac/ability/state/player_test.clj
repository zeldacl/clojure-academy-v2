(ns cn.li.ac.ability.state.player-test
  (:require 
            [cn.li.ac.ability.service.player-state-tick :as ps-tick]
[cn.li.ac.ability.service.player-state-accessors :as ps-accessors]
[cn.li.ac.ability.service.player-state-dirty :as ps-dirty]
[cn.li.ac.ability.service.player-state-core :as ps-core]
[clojure.edn :as edn]
            [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.test.support.player-state :as ps-fix]            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(use-fixtures :each ps-fix/clean-player-states-fixture)

(deftest player-state-access-requires-explicit-owner-test
  (binding [runtime-hooks/*player-state-owner* nil]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Player state owner requires"
                          (ps-core/get-player-state "ownerless")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Player state owner requires"
                          (ps-core/set-player-state! "ownerless" (ps-core/fresh-state))))))

(deftest fresh-state-shape-test
  (let [s (ps-core/fresh-state)]
    (is (map? (:ability-data s)))
    (is (map? (:resource-data s)))
    (is (map? (:cooldown-data s)))
    (is (map? (:preset-data s)))
    (is (map? (:develop-data s)))
    (is (map? (:terminal-data s)))
    (is (false? (:dirty? s)))))

(deftest get-or-create-player-state-test
  (let [a (ps-core/get-or-create-player-state! "u1")
        b (ps-core/get-or-create-player-state! "u1")]
    (is (identical? a b))))

(deftest update-ability-data-marks-dirty-test
  (ps-core/get-or-create-player-state! "u2")
  (is (false? (ps-dirty/dirty? "u2")))
  (ps-accessors/update-ability-data! "u2" assoc :category-id :x)
  (is (true? (ps-dirty/dirty? "u2")))
  (ps-dirty/mark-clean! "u2")
  (is (false? (ps-dirty/dirty? "u2"))))

(deftest server-tick-player-smoke-test
  (ps-core/get-or-create-player-state! "u3")
  (let [r (ps-tick/server-tick-player! "u3" nil)]
    (is (map? r))
    (is (vector? (:events r)))))

(deftest remove-player-state-test
  (ps-core/set-player-state! "u4" (ps-core/fresh-state))
  (is (some? (ps-core/get-player-state "u4")))
  (ps-core/remove-player-state! "u4")
  (is (nil? (ps-core/get-player-state "u4"))))

(deftest player-state-isolated-by-dynamic-owner-test
  (binding [runtime-hooks/*player-state-owner* {:server-session-id :session-a}]
    (ps-core/set-player-state! "same-uuid" (assoc (ps-core/fresh-state) :marker :a)))
  (binding [runtime-hooks/*player-state-owner* {:server-session-id :session-b}]
    (ps-core/set-player-state! "same-uuid" (assoc (ps-core/fresh-state) :marker :b)))
  (is (= :a (binding [runtime-hooks/*player-state-owner* {:server-session-id :session-a}]
              (:marker (ps-core/get-player-state "same-uuid")))))
  (is (= :b (binding [runtime-hooks/*player-state-owner* {:server-session-id :session-b}]
              (:marker (ps-core/get-player-state "same-uuid")))))
  (is (= ["same-uuid"]
         (binding [runtime-hooks/*player-state-owner* {:server-session-id :session-a}]
           (vec (ps-core/list-player-uuids))))))

(deftest clear-session-player-states-removes-only-target-session-test
  (binding [runtime-hooks/*player-state-owner* {:server-session-id :session-a}]
    (ps-core/set-player-state! "same-uuid" (assoc (ps-core/fresh-state) :marker :a))
    (ps-core/set-player-state! "only-a" (assoc (ps-core/fresh-state) :marker :only-a)))
  (binding [runtime-hooks/*player-state-owner* {:server-session-id :session-b}]
    (ps-core/set-player-state! "same-uuid" (assoc (ps-core/fresh-state) :marker :b)))
  (ps-core/clear-session-player-states! {:server-session-id :session-a})
  (is (nil? (binding [runtime-hooks/*player-state-owner* {:server-session-id :session-a}]
              (ps-core/get-player-state "same-uuid"))))
  (is (nil? (binding [runtime-hooks/*player-state-owner* {:server-session-id :session-a}]
              (ps-core/get-player-state "only-a"))))
  (is (= :b
         (binding [runtime-hooks/*player-state-owner* {:server-session-id :session-b}]
           (:marker (ps-core/get-player-state "same-uuid"))))))

(deftest persisted-state-edn-roundtrip-keeps-core-data-test
  (let [state (-> (ps-core/fresh-state)
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



