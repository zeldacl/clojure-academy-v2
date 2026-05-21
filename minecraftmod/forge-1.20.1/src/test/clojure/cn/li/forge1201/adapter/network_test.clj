(ns cn.li.forge1201.adapter.network-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mc1201.runtime.network-core :as network]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.hooks.catalog :as runtime-catalog]))

(deftest sync-message-payloads-test
  (testing "builds one payload per runtime sync channel"
    (let [uuid "player-uuid"
          payload {:ability-data {:a 1}
                   :resource-data {:r 2}
                   :cooldown-data {:c 3}
                   :preset-data {:p 4}}
          messages (set (#'network/sync-message-payloads uuid payload))]
      (is (= 4 (count messages)))
      (is (contains? messages {:msg-id runtime-catalog/MSG-SYNC-RUNTIME
                               :payload {:uuid uuid :ability-data {:a 1}}}))
      (is (contains? messages {:msg-id runtime-catalog/MSG-SYNC-RESOURCE
                               :payload {:uuid uuid :resource-data {:r 2}}}))
      (is (contains? messages {:msg-id runtime-catalog/MSG-SYNC-COOLDOWN
                               :payload {:uuid uuid :cooldown-data {:c 3}}}))
      (is (contains? messages {:msg-id runtime-catalog/MSG-SYNC-PRESET
                               :payload {:uuid uuid :preset-data {:p 4}}})))))

(deftest except-local-sender-broadcasts-to-nearby-players-test
  (let [sent (atom [])
        sender (network/create-except-local-context-sender
                 (fn [_source-player-uuid _radius] ["near-1" "near-2"])
                 (fn [target-uuid msg-id payload]
                   (swap! sent conj [target-uuid msg-id payload])))]
    (with-redefs [runtime-hooks/get-context-player-uuid (fn [_ctx-id] "source-player")]
      (sender "ctx-1" :fx {:v 7}))
    (is (= [["near-1" runtime-catalog/MSG-CTX-CHANNEL {:ctx-id "ctx-1" :channel :fx :payload {:v 7}}]
            ["near-2" runtime-catalog/MSG-CTX-CHANNEL {:ctx-id "ctx-1" :channel :fx :payload {:v 7}}]]
           @sent))))

(deftest except-local-sender-skips-when-context-owner-missing-test
  (let [sent (atom [])
        sender (network/create-except-local-context-sender
                 (fn [_source-player-uuid _radius] ["near-1"])
                 (fn [target-uuid msg-id payload]
                   (swap! sent conj [target-uuid msg-id payload])))]
    (with-redefs [runtime-hooks/get-context-player-uuid (fn [_ctx-id] nil)]
      (sender "ctx-404" :fx {:v 9}))
    (is (empty? @sent))))