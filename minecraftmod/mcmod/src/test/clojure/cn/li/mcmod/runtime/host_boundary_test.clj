(ns cn.li.mcmod.runtime.host-boundary-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.mcmod.runtime.host :as host]
            [cn.li.mcmod.runtime.damage-boundary :as damage]
            [cn.li.mcmod.runtime.fixed-channel :as channel]))
(deftest host-preflight-is-atomic-before-apply-test
  (let [applied (atom []) h (host/create {:queries {} :actions {:entity/damage (fn [phase command _] (if (= :preflight phase) (not= :reject (:mode (:args command))) (swap! applied conj (:id command))))}})]
    (is (= {:ok? false :phase :preflight} (select-keys (host/execute! h [{:id :a :capability :entity/damage :args {:mode :ok}} {:id :b :capability :entity/damage :args {:mode :reject}}] {}) [:ok? :phase])))
    (is (empty? @applied))))
(deftest damage-boundary-begin-complete-test
  (let [completed (atom nil)]
    (damage/install! {:begin (fn [event] {:token :hit-1 :amount (* 2 (:base event))}) :complete (fn [result] (reset! completed result))})
    (let [resolution (damage/begin! {:world-id "w" :source "a" :target "b" :base 3 :type :skill})]
      (is (= 6.0 (:amount resolution))) (damage/complete! resolution true 6.0) (is (= true (:applied? @completed))) (is (= :hit-1 (:token @completed))))
    (damage/clear!)))
(deftest input-edge-roundtrip-is-bounded-test
  (let [packet (channel/encode-intent {:seq 12 :control-id 4 :edge :press :choice "ac:fire" :client-tick 99}) decoded (channel/decode-intent packet)]
    (is (= :input-edge (:type decoded))) (is (= "ac:fire" (:choice decoded))) (is (<= (alength packet) 64))))
