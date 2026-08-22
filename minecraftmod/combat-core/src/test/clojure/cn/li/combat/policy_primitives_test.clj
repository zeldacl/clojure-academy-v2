(ns cn.li.combat.policy-primitives-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.node.descriptor :as node]
            [cn.li.node.runtime :as runtime]
            [cn.li.combat.policy-primitives :as policy-primitives]))

(use-fixtures :each
  (fn [f]
    (node/reset-for-test!)
    (policy-primitives/install!)
    (f)
    (node/reset-for-test!)))

(def ^:private expected-ids
  #{:owner/patch :session/patch :effect/vfx :domain/event
    :cost/spend :score/mark :cooldown/start :session/read :session/write
    :guard/value-in :data/random-item})

(deftest every-expected-id-registers-as-primitive-test
  (doseq [id expected-ids]
    (is (some? (node/descriptor id)) (str id " must be registered"))
    (is (= :primitive (:layer (node/descriptor id))))))

(deftest owner-patch-emits-raw-action-test
  (let [seen (atom nil)
        result (runtime/invoke-primitive!
                :owner/patch {:entries [{:path [:resources :cp] :mode :increment :value -5.0}]}
                {:emit-action! (fn [action] (reset! seen action))})]
    (is (= {:type :owner-patch :entries [{:path [:resources :cp] :mode :increment :value -5.0}]} @seen))
    (is (= {} result))))

(deftest effect-vfx-emits-signal-with-defaults-test
  (let [seen (atom nil)]
    (runtime/invoke-primitive!
     :effect/vfx {:effect-id :beam :operation :spawn :payload {:x 1.0}}
     {:emit-vfx! (fn [signal] (reset! seen signal))})
    (is (= :beam (:effect-id @seen)))
    (is (= :spawn (:operation @seen)))
    (is (nil? (:instance-key @seen)))))

(deftest domain-event-merges-payload-test
  (let [seen (atom nil)]
    (runtime/invoke-primitive!
     :domain/event {:event-type :ignite :payload {:power 3.0}}
     {:emit-event! (fn [event] (reset! seen event))})
    (is (= :ignite (:type @seen)))
    (is (= 3.0 (get-in @seen [:payload :power])))))

(deftest cost-spend-affordable-deducts-and-emits-patch-test
  (let [resources* (atom {:cp 10.0})
        seen (atom nil)
        result (runtime/invoke-primitive!
                :cost/spend {:budget {:resources {:cp 4.0}}}
                {:resources* resources* :emit-action! (fn [action] (reset! seen action))})]
    (is (false? (:insufficient? result)))
    (is (= 6.0 (:cp @resources*)))
    (is (= [{:path [:resources :cp] :mode :increment :value -4.0}] (:entries @seen)))))

(deftest cost-spend-insufficient-without-partial-spends-nothing-test
  (let [resources* (atom {:cp 1.0})
        seen (atom nil)
        result (runtime/invoke-primitive!
                :cost/spend {:budget {:resources {:cp 4.0}}}
                {:resources* resources* :emit-action! (fn [action] (reset! seen action))})]
    (is (true? (:insufficient? result)))
    (is (= 1.0 (:cp @resources*)) "no partial spend without :partial? true")
    (is (nil? @seen) "no patch emitted when nothing was spent")))

(deftest cost-spend-partial-spends-what-is-available-test
  (let [resources* (atom {:cp 1.0})
        result (runtime/invoke-primitive!
                :cost/spend {:budget {:resources {:cp 4.0}} :partial? true}
                {:resources* resources* :emit-action! (fn [_] nil)})]
    (is (true? (:insufficient? result)))
    (is (= 0.0 (:cp @resources*)))))

(deftest score-mark-computes-weighted-amount-test
  (let [seen (atom nil)]
    (runtime/invoke-primitive!
     :score/mark {:progression {:per-mark 2.0} :weight 3.0}
     {:ability-id :railgun :emit-action! (fn [action] (reset! seen action))})
    (is (= [{:path [:ability-data :skill-exps :railgun] :mode :increment :value 6.0}] (:entries @seen)))))

(deftest cooldown-start-emits-assign-patch-test
  (let [seen (atom nil)]
    (runtime/invoke-primitive!
     :cooldown/start {:name :main :cooldown {:ticks 40}}
     {:ability-id :railgun :emit-action! (fn [action] (reset! seen action))})
    (is (= [{:path [:cooldown-data :railgun :main] :mode :assign :value 40.0}] (:entries @seen)))))

(deftest session-read-returns-declared-key-test
  (is (= 5 (:value (runtime/invoke-primitive! :session/read {:key :charge-ticks}
                                              {:session-state {:charge-ticks 5}})))))

(deftest session-read-missing-key-returns-nil-test
  (is (nil? (:value (runtime/invoke-primitive! :session/read {:key :missing} {:session-state {}})))))

(deftest session-write-emits-assign-patch-test
  (let [seen (atom nil)]
    (runtime/invoke-primitive!
     :session/write {:key :charge-ticks :value 6}
     {:emit-action! (fn [action] (reset! seen action))})
    (is (= {:type :session-patch :entries [{:path [:charge-ticks] :mode :assign :value 6}]} @seen))))

(deftest guard-value-in-pure-membership-test
  (is (true? (:result (runtime/invoke-primitive! :guard/value-in {:value :fire :one-of [:fire :ice]} {}))))
  (is (false? (:result (runtime/invoke-primitive! :guard/value-in {:value :water :one-of [:fire :ice]} {})))))

(deftest random-item-is-deterministic-per-seed-test
  (let [items [:a :b :c :d :e]
        pick (fn [seed] (:item (runtime/invoke-primitive! :data/random-item {:items items} {:seed seed})))]
    (is (= (pick 7) (pick 7)))
    (is (contains? (set items) (pick 7)))))

(deftest random-item-empty-list-returns-nil-test
  (is (nil? (:item (runtime/invoke-primitive! :data/random-item {:items []} {:seed 1})))))
