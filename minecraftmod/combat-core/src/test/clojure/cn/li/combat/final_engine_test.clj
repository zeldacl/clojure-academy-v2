(ns cn.li.combat.final-engine-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.combat.final-compiler :as compiler]
            [cn.li.combat.final-engine :as engine]
            [cn.li.mcmod.runtime.host :as host]))
(deftest compiler-lowers-query-after-mutation-to-barrier-test
  (is (map? (compiler/compile-program
             {:component :flow/sequence
              :steps [{:component :combat/damage :args {:amount 1}}
                      {:component :target/raycast :bind :hit}]}))))
(deftest engine-preflight-before-state-commit-test
  (let [commits (atom []) host (host/create {:queries {} :actions {:combat/damage (fn [_ _ _] true)}}) engine (engine/create-engine {:host host :state-provider (fn [_] {:resources {:mana 3}}) :commit-state! #(swap! commits conj %)}) compiled (compiler/compile-program {:component :flow/sequence :steps [{:component :resource/try-spend :resource :mana :amount 1 :bind :ok} {:component :combat/damage :args {:amount 2}}]}) result (engine/execute! engine compiled {:owner :alice :world "w" :tick 1 :seed 9 :input {}})]
    (is (= :accepted (:status result)))
    (is (= 1 (count @commits)))
    (is (= 2.0 (get-in result [:txn 0 :state :resources :mana])))))
