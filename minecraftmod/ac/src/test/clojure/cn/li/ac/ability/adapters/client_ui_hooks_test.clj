(ns cn.li.ac.ability.adapters.client-ui-hooks-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.adapters.client-ui-hooks :as client-ui-hooks]
            [cn.li.ac.ability.client.keybinds :as client-keybinds]
            [cn.li.ac.ability.server.service.context-mgr :as ctx-mgr]
            [cn.li.mcmod.hooks.catalog :as catalog]
            [cn.li.mcmod.network.client :as net-client]))

(deftest client-slot-key-hooks-create-context-once-and-send-input-messages-test
  (let [sent (atom [])
        activated (atom [])
        hooks (client-ui-hooks/runtime-client-ui-hooks)]
    (with-redefs [client-keybinds/get-skill-id-for-slot-public
                  (fn [player-uuid key-idx]
                    (when (and (= "p1" player-uuid) (= 0 key-idx))
                      :railgun))
                  ctx-mgr/activate-context!
                  (fn [player-uuid skill-id]
                    (swap! activated conj {:player-uuid player-uuid :skill-id skill-id})
                    {:id "ctx-client-1"})
                  net-client/send-to-server
                  (fn
                    ([msg-id payload]
                     (swap! sent conj {:msg-id msg-id :payload payload}))
                    ([msg-id payload _callback]
                     (swap! sent conj {:msg-id msg-id :payload payload})))]
      ((:client-on-slot-key-down! hooks) "p1" 0)
      ((:client-on-slot-key-tick! hooks) "p1" 0)
      ((:client-on-slot-key-up! hooks) "p1" 0)
      ((:client-abort-all! hooks))
      (is (= [{:player-uuid "p1" :skill-id :railgun}]
             @activated))
      (is (= [catalog/MSG-SLOT-KEY-DOWN
              catalog/MSG-SLOT-KEY-TICK
              catalog/MSG-SLOT-KEY-UP]
             (mapv :msg-id @sent)))
      (is (= [{:ctx-id "ctx-client-1" :skill-id :railgun :key-idx 0}
              {:ctx-id "ctx-client-1" :skill-id :railgun :key-idx 0}
              {:ctx-id "ctx-client-1" :key-idx 0}]
             (mapv :payload @sent))))))