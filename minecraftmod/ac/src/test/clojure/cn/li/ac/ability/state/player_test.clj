(ns cn.li.ac.ability.state.player-test
  (:require 
            [cn.li.ac.ability.service.state-tick :as ps-tick]
[cn.li.ac.ability.service.state-accessors :as ps-accessors]
[cn.li.ac.ability.service.runtime-store :as store]
[cn.li.ac.ability.registry.event :as evt]
[clojure.edn :as edn]
            [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.test.support.player-state :as ps-fix]            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(use-fixtures :each
  (fn [f]
    (ps-fix/with-test-player-state-owner
      (fn []
        (store/reset-store!)
        (evt/install-event-subscriber-runtime!
          (evt/create-event-subscriber-runtime))
        (try
          (f)
          (finally
            (store/reset-store!)
            (evt/install-event-subscriber-runtime!
              (evt/create-event-subscriber-runtime))))))))

(deftest player-state-access-requires-explicit-owner-test
  (binding [runtime-hooks/*player-state-owner* nil]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"requires bound session-id"
                          (ps-accessors/get-ability-data "ownerless")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"requires bound session-id"
                          (ps-accessors/update-ability-data! "ownerless" assoc :category-id :x)))))

(deftest fresh-state-shape-test
  (let [s (store/fresh-player-state)]
    (is (map? (:ability-data s)))
    (is (map? (:resource-data s)))
    (is (map? (:cooldown-data s)))
    (is (map? (:preset-data s)))
    (is (map? (:develop-data s)))
    (is (map? (:terminal-data s)))
    (is (false? (:dirty? s)))))

(deftest get-or-create-player-state-test
  (let [a (store/get-or-create-player-state! ps-fix/test-session-id "u1")
        b (store/get-or-create-player-state! ps-fix/test-session-id "u1")]
    (is (identical? a b))))

(deftest update-ability-data-marks-dirty-test
  (store/get-or-create-player-state! ps-fix/test-session-id "u2")
  (is (false? (:dirty? (store/get-player-state* ps-fix/test-session-id "u2"))))
  (ps-accessors/update-ability-data! "u2" assoc :category-id :x)
  (is (true? (:dirty? (store/get-player-state* ps-fix/test-session-id "u2"))))
  (store/clear-dirty! (store/get-store) ps-fix/test-session-id "u2")
  (is (false? (:dirty? (store/get-player-state* ps-fix/test-session-id "u2")))))

(deftest update-ability-data-uses-bound-owner-session-test
  (store/get-or-create-player-state! :accessor-session "u2")
  (binding [runtime-hooks/*player-state-owner* {:session-id :accessor-session}]
    (ps-accessors/update-ability-data! "u2" assoc :category-id :vecmanip)
    (is (= :vecmanip
           (get-in (store/get-player-state* :accessor-session "u2") [:ability-data :category-id])))))

(deftest server-tick-player-smoke-test
  (store/get-or-create-player-state! ps-fix/test-session-id "u3")
  (let [r (ps-tick/server-tick-player! "u3" nil)]
    (is (map? r))
    (is (vector? (:events r)))))

(deftest server-tick-player-uses-bound-owner-session-test
  (store/get-or-create-player-state! :tick-session "u3")
  (binding [runtime-hooks/*player-state-owner* {:session-id :tick-session}]
    (let [r (ps-tick/server-tick-player! "u3" nil)]
      (is (map? r))
      (is (vector? (:events r))))))

(deftest remove-player-state-test
  (store/set-player-state!* ps-fix/test-session-id "u4" (store/fresh-player-state))
  (is (some? (store/get-player-state* ps-fix/test-session-id "u4")))
  (store/remove-player-state!* ps-fix/test-session-id "u4")
  (is (nil? (store/get-player-state* ps-fix/test-session-id "u4"))))

(deftest player-state-isolated-by-session-id-test
  (store/set-player-state!* :session-a "same-uuid" (assoc (store/fresh-player-state) :marker :a))
  (store/set-player-state!* :session-b "same-uuid" (assoc (store/fresh-player-state) :marker :b))
  (is (= :a (:marker (store/get-player-state* :session-a "same-uuid"))))
  (is (= :b (:marker (store/get-player-state* :session-b "same-uuid"))))
  (is (= ["same-uuid"] (vec (store/list-players (store/get-store) :session-a)))))

(deftest clear-session-player-states-removes-only-target-session-test
  (store/set-player-state!* :session-a "same-uuid" (assoc (store/fresh-player-state) :marker :a))
  (store/set-player-state!* :session-a "only-a" (assoc (store/fresh-player-state) :marker :only-a))
  (store/set-player-state!* :session-b "same-uuid" (assoc (store/fresh-player-state) :marker :b))
  (store/remove-session! (store/get-store) :session-a)
  (is (nil? (store/get-player-state* :session-a "same-uuid")))
  (is (nil? (store/get-player-state* :session-a "only-a")))
  (is (= :b (:marker (store/get-player-state* :session-b "same-uuid")))))

(deftest persisted-state-edn-roundtrip-keeps-core-data-test
  (let [state (-> (store/fresh-player-state)
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
    (is (= #{:ability-data :resource-data :cooldown-data :preset-data :develop-data :terminal-data :context-registry}
           (set (keys decoded))))
    (is (= [:electromaster :railgun]
           (get-in decoded [:preset-data :slots [0 0]])))
    (is (= #{:skill-tree}
           (get-in decoded [:terminal-data :installed-apps])))))



