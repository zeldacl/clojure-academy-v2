(ns cn.li.ac.ability.effects.effect-interpreter-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.effects.interpreter :as interpreter]
            [cn.li.ac.ability.effects.network-handler :as net]
            [cn.li.ac.ability.effects.persistence-handler :as persist]
            [cn.li.ac.ability.effects.platform-handler :as platform]
            [cn.li.ac.ability.registry.event :as evt]))

(deftest execute-effects-dispatches-to-correct-handlers-test
  (let [calls (atom [])]
    (with-redefs [net/execute-network-send! (fn [effect]
                                              (swap! calls conj [:network effect])
                                              nil)
                  platform/execute-platform-call! (fn [effect]
                                                    (swap! calls conj [:platform effect])
                                                    nil)
                  persist/execute-persist-state! (fn [effect]
                                                   (swap! calls conj [:persist effect])
                                                   nil)]
      (interpreter/execute-effects!
        [{:effect/type :network-send :player-uuid "p1"}
         {:effect/type :platform-call :fn-ref :demo/fn :args []}
         {:effect/type :persist-state :player-uuid "p1"}
         {:effect/type :unknown/type}])
      (is (= [[:network {:effect/type :network-send :player-uuid "p1"}]
              [:platform {:effect/type :platform-call :fn-ref :demo/fn :args []}]
              [:persist {:effect/type :persist-state :player-uuid "p1"}]]
             @calls)))))

(deftest execute-reducer-result-fires-events-before-effects-test
  (let [calls (atom [])]
    (with-redefs [evt/fire-ability-event! (fn [event]
                                            (swap! calls conj [:event event])
                                            nil)
                  interpreter/execute-effects! (fn [effects]
                                                 (swap! calls conj [:effects effects])
                                                 nil)]
      (interpreter/execute-reducer-result!
        {:events [{:event/type :ability/test-a}
                  {:event/type :ability/test-b}]
         :effects [{:effect/type :network-send :player-uuid "p1"}]})
      (is (= [[:event {:event/type :ability/test-a}]
              [:event {:event/type :ability/test-b}]
              [:effects [{:effect/type :network-send :player-uuid "p1"}]]]
             @calls)))))
