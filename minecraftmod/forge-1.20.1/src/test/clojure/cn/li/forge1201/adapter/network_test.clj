(ns cn.li.forge1201.adapter.network-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mc1201.runtime.network-core :as network]
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