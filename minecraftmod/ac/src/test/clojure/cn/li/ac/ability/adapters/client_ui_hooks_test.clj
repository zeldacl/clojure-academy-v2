(ns cn.li.ac.ability.adapters.client-ui-hooks-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.adapters.client-ui-hooks :as client-ui-hooks]
            [cn.li.ac.ability.client.hud :as hud]
            [cn.li.ac.ability.client.keybinds :as client-keybinds]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.server.service.context-mgr :as ctx-mgr]
            [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.mcmod.hooks.catalog :as catalog]
            [cn.li.mcmod.network.client :as net-client]))

(defn- reset-ui-state! [f]
  (reset! @#'cn.li.ac.ability.adapters.client-ui-hooks/vm-wave-circles [])
  (reset! @#'cn.li.ac.ability.adapters.client-ui-hooks/vm-wave-last-spawn-ms 0)
  (f)
  (reset! @#'cn.li.ac.ability.adapters.client-ui-hooks/vm-wave-circles [])
  (reset! @#'cn.li.ac.ability.adapters.client-ui-hooks/vm-wave-last-spawn-ms 0))

(use-fixtures :each reset-ui-state!)

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

(deftest hud-render-data-hidden-when-not-activated-test
  (let [model {:cp {:cur 50.0 :max 100.0}
               :overload {:cur 10.0 :max 100.0 :fine true}
               :active-slots []
               :activated false}]
    (is (nil? (hud/build-hud-render-data model 320 180 {})))))

(deftest build-client-overlay-plan-falls-back-when-activated-override-nil-test
  (with-redefs [ps/get-player-state (fn [_]
                                      {:resource-data {:activated true
                                                       :cur-cp 80.0
                                                       :max-cp 100.0
                                                       :cur-overload 0.0
                                                       :max-overload 100.0}
                                       :cooldown-data {}
                                       :preset-data {}})
                client-keybinds/get-activate-hint (fn [_] nil)
                client-keybinds/get-preset-switch-state (fn [] nil)]
    (let [plan (client-ui-hooks/build-client-overlay-plan
                "p1" 320 180 {:activated-override nil :now-ms 1000})]
      (is (seq (:elements plan))))))

(deftest build-client-overlay-plan-renders-reflection-crosshair-and-vm-wave-test
  (with-redefs [ps/get-player-state (fn [_]
                                      {:resource-data {:activated true
                                                       :cur-cp 80.0
                                                       :max-cp 100.0
                                                       :cur-overload 0.0
                                                       :max-overload 100.0}
                                       :cooldown-data {}
                                       :preset-data {}})
                ctx/get-all-contexts (fn []
                                       {"ctx-reflection" {:player-uuid "p1"
                                                           :skill-state {:toggle {:vec-reflection {:active true}
                                                                                  :vec-deviation {:active true}}}}})
                client-keybinds/get-activate-hint (fn [_] nil)
                client-keybinds/get-preset-switch-state (fn [] nil)]
    (let [plan (client-ui-hooks/build-client-overlay-plan
                "p1" 320 180 {:now-ms 1000})
          kinds (mapv :kind (:elements plan))]
      (is (= 1 (count (filter #{:vec-reflection-crosshair} kinds))))
      (is (some #{:blit-texture} kinds)))))