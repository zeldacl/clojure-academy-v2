(ns cn.li.forge1201.adapter.network-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.mc1201.runtime.network-core :as network]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.hooks.messages :as messages]))

(def ^:private test-message-ids
  {:ctx-channel "ability:ctx/channel"
   :sync-runtime "ability:sync/ability-data"
   :sync-resource "ability:sync/resource-data"
   :sync-cooldown "ability:sync/cooldown-data"
   :sync-preset "ability:sync/preset-data"})

(defn- message-fixture [f]
  (messages/clear-messages!)
  (messages/register-messages! test-message-ids)
  (f)
  (messages/clear-messages!))

(use-fixtures :each message-fixture)

(deftest sync-message-payloads-test
  (testing "builds one payload per runtime sync channel"
    (let [uuid "player-uuid"
          payload {:ability-data {:a 1}
                   :resource-data {:r 2}
                   :cooldown-data {:c 3}
                   :preset-data {:p 4}}
          messages (set (#'network/sync-message-payloads uuid payload))]
      (is (= 4 (count messages)))
      (is (contains? messages {:msg-id (:sync-runtime test-message-ids)
                               :payload {:uuid uuid :ability-data {:a 1}}}))
      (is (contains? messages {:msg-id (:sync-resource test-message-ids)
                               :payload {:uuid uuid :resource-data {:r 2}}}))
      (is (contains? messages {:msg-id (:sync-cooldown test-message-ids)
                               :payload {:uuid uuid :cooldown-data {:c 3}}}))
      (is (contains? messages {:msg-id (:sync-preset test-message-ids)
                               :payload {:uuid uuid :preset-data {:p 4}}})))))

(deftest except-local-sender-broadcasts-to-nearby-players-test
  (let [sent (atom [])
        sender (network/create-except-local-context-sender
                 (fn [_source-player-uuid _radius] ["near-1" "near-2"])
                 (fn [target-uuid msg-id payload]
                   (swap! sent conj [target-uuid msg-id payload])))]
    (with-redefs [runtime-hooks/get-context-player-uuid (fn [_ctx-id] "source-player")]
      (sender "ctx-1" :fx {:v 7}))
          (is (= [["near-1" (:ctx-channel test-message-ids) {:ctx-id "ctx-1" :channel :fx :payload {:v 7}}]
            ["near-2" (:ctx-channel test-message-ids) {:ctx-id "ctx-1" :channel :fx :payload {:v 7}}]]
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