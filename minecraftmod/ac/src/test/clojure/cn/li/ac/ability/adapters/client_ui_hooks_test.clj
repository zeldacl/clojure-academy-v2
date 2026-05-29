(ns cn.li.ac.ability.adapters.client-ui-hooks-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.adapters.client-ui-hooks :as client-ui-hooks]
            [cn.li.ac.ability.client.effects.particles :as particles]
            [cn.li.ac.ability.client.effects.sounds :as sounds]
            [cn.li.ac.ability.client.hud :as hud]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.keybinds :as client-keybinds]
            [cn.li.ac.ability.client.managed-screens :as managed-screens]
            [cn.li.ac.ability.client.screens.location-teleport :as location-teleport-screen]
            [cn.li.ac.ability.client.screens.preset-editor :as preset-editor-screen]
            [cn.li.ac.ability.client.screens.skill-tree :as skill-tree-screen]
            [cn.li.ac.content.ability.electromaster.current-charging-fx :as current-charging-fx]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.config.gameplay :as gameplay]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.server.service.context-mgr :as ctx-mgr]
            [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.ac.test.support.player-state :as ps-fix]
            [cn.li.ac.ability.messages :as catalog]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.client :as net-client]))

(defn- with-fresh-ui-runtimes
  [f]
  (client-ui-hooks/call-with-client-ui-runtime
    (client-ui-hooks/create-client-ui-runtime)
    (fn []
      (client-keybinds/call-with-client-keybind-runtime
        (client-keybinds/create-client-keybind-runtime)
        (fn []
          (managed-screens/call-with-managed-screen-runtime
            (managed-screens/create-managed-screen-runtime)
            f))))))

(defn- reset-ui-state! [f]
  (with-fresh-ui-runtimes
    (fn []
      (particles/call-with-particle-queue-runtime
        (particles/create-particle-queue-runtime)
        (fn []
          (sounds/call-with-sound-queue-runtime
            (sounds/create-sound-queue-runtime)
            (fn []
              (hand-effects/call-with-camera-pitch-runtime
                (hand-effects/create-camera-pitch-runtime)
                (fn []
                  (ps-fix/with-test-player-state-owner
                    (fn []
                      (client-ui-hooks/reset-client-ui-state-for-test!)
                      (client-keybinds/reset-client-keybind-state-for-test!)
                      (particles/reset-particle-queue-for-test!)
                      (sounds/reset-sound-queue-for-test!)
                      (hand-effects/reset-hand-effect-registry-for-test!)
                      (ps/reset-player-states-for-test!)
                      (ctx/reset-contexts-for-test!)
                      (try
                        (binding [client-keybinds/*client-session-id* :test-session]
                          (f))
                        (finally
                          (client-ui-hooks/reset-client-ui-state-for-test!)
                          (client-keybinds/reset-client-keybind-state-for-test!)
                          (particles/reset-particle-queue-for-test!)
                          (sounds/reset-sound-queue-for-test!)
                          (hand-effects/reset-hand-effect-registry-for-test!)
                          (ctx/reset-contexts-for-test!)
                          (ps/reset-player-states-for-test!))))))))))))))

(use-fixtures :each reset-ui-state!)

(defn- with-client-player-state-owner
  [player-uuid f]
  (binding [client-keybinds/*client-session-id* :test-session
            runtime-hooks/*client-session-id* :test-session
            runtime-hooks/*player-state-owner* {:client-session-id :test-session
                                                :player-uuid player-uuid}]
    (f)))

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
          catalog/MSG-CTX-KEEPALIVE
              catalog/MSG-SLOT-KEY-UP]
             (mapv :msg-id @sent)))
      (is (= [{:ctx-id "ctx-client-1" :skill-id :railgun :key-idx 0}
          {:ctx-id "ctx-client-1"}
              {:ctx-id "ctx-client-1" :key-idx 0}]
             (mapv :payload @sent))))))

      (deftest client-slot-key-tick-without-active-context-sends-no-message-test
        (let [sent (atom [])
         activated (atom [])
         hooks (client-ui-hooks/runtime-client-ui-hooks)]
          (with-redefs [client-keybinds/get-skill-id-for-slot-public (fn [_ _] :railgun)
              ctx-mgr/activate-context! (fn [player-uuid skill-id]
                      (swap! activated conj {:player-uuid player-uuid
                              :skill-id skill-id})
                      {:id "ctx-should-not-exist"})
              net-client/send-to-server (fn
                      ([msg-id payload]
                       (swap! sent conj {:msg-id msg-id :payload payload}))
                      ([msg-id payload _callback]
                       (swap! sent conj {:msg-id msg-id :payload payload})))]
            ((:client-on-slot-key-tick! hooks) "p1" 0)
            (is (empty? @activated))
            (is (empty? @sent)))))

(deftest client-slot-key-abort-sends-abort-message-and-clears-context-test
  (let [sent (atom [])
        terminated (atom [])
        hooks (client-ui-hooks/runtime-client-ui-hooks)]
    (with-redefs [client-keybinds/get-skill-id-for-slot-public (fn [_ _] :railgun)
                  ctx-mgr/activate-context! (fn [_ _] {:id "ctx-client-abort"})
                  net-client/send-to-server
                  (fn
                    ([msg-id payload]
                     (swap! sent conj {:msg-id msg-id :payload payload}))
                    ([msg-id payload _callback]
                     (swap! sent conj {:msg-id msg-id :payload payload})))
                  ctx/terminate-context! (fn [ctx-id terminate-fn]
                                           (swap! terminated conj [ctx-id terminate-fn])
                                           nil)]
      ((:client-on-slot-key-down! hooks) "p1" 0)
      ((:client-on-slot-key-abort! hooks) "p1" 0)
      (is (= [catalog/MSG-SLOT-KEY-DOWN
              catalog/MSG-SLOT-KEY-ABORT]
             (mapv :msg-id @sent)))
      (is (= [{:ctx-id "ctx-client-abort" :skill-id :railgun :key-idx 0}
              {:ctx-id "ctx-client-abort" :key-idx 0}]
             (mapv :payload @sent)))
      (is (= [["ctx-client-abort" nil]] @terminated))
      (is (empty? (:slot-context-ids (client-ui-hooks/client-ui-state-snapshot "p1")))))))

(deftest client-abort-all-sends-abort-messages-for-active-slot-contexts-test
  (let [sent (atom [])
        terminated (atom [])
        hooks (client-ui-hooks/runtime-client-ui-hooks)]
    (with-redefs [client-keybinds/get-skill-id-for-slot-public (fn [_ _] :railgun)
                  ctx-mgr/activate-context! (fn [_ _] {:id "ctx-client-abort-all"})
                  net-client/send-to-server
                  (fn
                    ([msg-id payload]
                     (swap! sent conj {:msg-id msg-id :payload payload}))
                    ([msg-id payload _callback]
                     (swap! sent conj {:msg-id msg-id :payload payload})))
                  ctx/terminate-context! (fn [ctx-id terminate-fn]
                                           (swap! terminated conj [ctx-id terminate-fn])
                                           nil)]
      ((:client-on-slot-key-down! hooks) "p1" 0)
      ((:client-abort-all! hooks))
      (is (= [catalog/MSG-SLOT-KEY-DOWN
              catalog/MSG-SLOT-KEY-ABORT]
             (mapv :msg-id @sent)))
      (is (= [["ctx-client-abort-all" nil]] @terminated))
      (is (empty? (:slot-context-ids (client-ui-hooks/client-ui-state-snapshot "p1")))))))

(deftest slot-context-state-isolated-by-client-session-test
  (let [ctx-counter (atom 0)
        hooks (client-ui-hooks/runtime-client-ui-hooks)]
    (with-redefs [client-keybinds/get-skill-id-for-slot-public (fn [_ _] :railgun)
                  ctx-mgr/activate-context! (fn [_ _]
                                              {:id (str "ctx-" (swap! ctx-counter inc))})
                  net-client/send-to-server (fn [& _] nil)]
      (binding [client-keybinds/*client-session-id* :session-a]
        ((:client-on-slot-key-down! hooks) "p1" 0))
      (binding [client-keybinds/*client-session-id* :session-b]
        ((:client-on-slot-key-down! hooks) "p1" 0))
      (is (= {[:session-a "p1" 0] "ctx-1"}
             (:slot-context-ids (client-ui-hooks/client-ui-state-snapshot
                                 {:client-session-id :session-a
                                  :player-uuid "p1"}))))
      (is (= {[:session-b "p1" 0] "ctx-2"}
             (:slot-context-ids (client-ui-hooks/client-ui-state-snapshot
                                 {:client-session-id :session-b
                    :player-uuid "p1"})))))))

(deftest client-ui-owner-requires-explicit-session-and-player-test
  (binding [client-keybinds/*client-session-id* nil
            runtime-hooks/*client-session-id* nil]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Client UI owner requires :client-session-id"
                          (client-ui-hooks/client-ui-state-snapshot "p1"))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Client UI owner requires :player-uuid"
                        (client-ui-hooks/client-ui-state-snapshot {:client-session-id :session-a}))))

(deftest client-ui-runtime-isolation-test
  (let [owner {:client-session-id :test-session
               :player-uuid "p1"}
        runtime-b (client-ui-hooks/create-client-ui-runtime)]
    (client-ui-hooks/set-slot-context-for-test! owner 0 "ctx-a")
    (client-ui-hooks/call-with-client-ui-runtime
      runtime-b
      (fn []
        (client-ui-hooks/set-slot-context-for-test! owner 0 "ctx-b")
        (is (= {[:test-session "p1" 0] "ctx-b"}
               (:slot-context-ids (client-ui-hooks/client-ui-state-snapshot owner))))))
    (is (= {[:test-session "p1" 0] "ctx-a"}
           (:slot-context-ids (client-ui-hooks/client-ui-state-snapshot owner))))))

(deftest client-open-managed-screen-validates-payload-owner-test
  (let [hooks (client-ui-hooks/runtime-client-ui-hooks)]
    (binding [client-keybinds/*client-session-id* :session-a]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"requires :player-uuid"
                            ((:client-open-managed-screen! hooks) :ac/skill-tree {:learn-context :node-a})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"session does not match current client session"
                            ((:client-open-managed-screen! hooks) :ac/skill-tree {:player-uuid "p1"
                                                                                  :client-session-id :session-b}))))))

(deftest client-clear-owner-state-clears-owned-runtime-state-test
  (let [hooks (client-ui-hooks/runtime-client-ui-hooks)
        owner {:client-session-id :session-a
               :player-uuid "p1"}
        context-owner {:logical-side :client
                       :session-id [:session-a "p1"]
                       :player-uuid "p1"}
        screen-calls (atom [])
        keybind-clears (atom [])]
    (binding [ctx/*context-owner* context-owner
              runtime-hooks/*player-state-owner* owner]
      (ctx/register-context! {:id "ctx-cleanup"
                              :player-uuid "p1"
                              :logical-side :client
                              :session-id [:session-a "p1"]})
      (ps/set-player-state! "p1" {:resource-data {:activated true}}))
    (client-ui-hooks/set-slot-context-for-test! owner 0 "ctx-cleanup")
    (client-ui-hooks/seed-vm-wave-state-for-test! owner [{:radius 1.0}] 42)
    (particles/queue-particle-effect! owner {:type :particle :particle-type :spark})
    (sounds/queue-sound-effect! owner {:type :sound :sound-id "minecraft:test"})
    (hand-effects/add-camera-pitch-delta! owner 1.5)
    (with-redefs [skill-tree-screen/close-screen! (fn [screen-owner]
                                                    (swap! screen-calls conj [:skill-tree screen-owner])
                                                    nil)
                  preset-editor-screen/close-screen! (fn [screen-owner]
                                                       (swap! screen-calls conj [:preset-editor screen-owner])
                                                       nil)
                  location-teleport-screen/close-screen! (fn [screen-owner]
                                                           (swap! screen-calls conj [:location-teleport screen-owner])
                                                           nil)
                  client-keybinds/clear-client-keybind-state! (fn [clear-owner]
                                                                (swap! keybind-clears conj clear-owner)
                                                                nil)]
      ((:client-clear-owner-state! hooks) owner))
    (is (= [[:skill-tree owner]
            [:preset-editor owner]
            [:location-teleport owner]]
           @screen-calls))
    (is (= [owner] @keybind-clears))
    (is (= {:vm-wave-circles []
            :vm-wave-last-spawn-ms 0
            :slot-context-ids {}
            :slot-key-tick-ms {}
            :charge-coin-state nil}
           (client-ui-hooks/client-ui-state-snapshot owner)))
    (is (empty? (particles/particle-queue-snapshot (:client-session-id owner))))
    (is (empty? (sounds/sound-queue-snapshot (:client-session-id owner))))
    (is (empty? (hand-effects/drain-camera-pitch-deltas! owner)))
    (binding [runtime-hooks/*player-state-owner* owner]
      (is (nil? (ps/get-player-state "p1"))))
    (binding [ctx/*context-owner* context-owner]
      (is (nil? (ctx/get-context "ctx-cleanup"))))))

(deftest hud-render-data-hidden-when-not-activated-test
  (let [model {:cp {:cur 50.0 :max 100.0}
               :overload {:cur 10.0 :max 100.0 :fine true}
               :active-slots []
               :activated false}]
    (is (nil? (hud/build-hud-render-data model 320 180 {})))))

(deftest client-slot-wheel-sends-ctx-channel-only-for-penetrate-with-active-context-test
  (let [sent (atom [])
        hooks (client-ui-hooks/runtime-client-ui-hooks)]
    (with-redefs [client-keybinds/get-skill-id-for-slot-public
                  (fn [_ key-idx]
                    (case key-idx
                      0 :penetrate-teleport
                      1 :railgun
                      nil))
                  ctx-mgr/activate-context!
                  (fn [_ _] {:id "ctx-penetrate"})
                  gameplay/use-mouse-wheel-enabled? (fn [] true)
                  net-client/send-to-server
                  (fn
                    ([msg-id payload]
                     (swap! sent conj {:msg-id msg-id :payload payload}))
                    ([msg-id payload _callback]
                     (swap! sent conj {:msg-id msg-id :payload payload})))]
      ((:client-on-slot-key-down! hooks) "p1" 0)
      ((:client-on-slot-wheel! hooks) "p1" 0 2.0)
      ((:client-on-slot-wheel! hooks) "p1" 1 2.0)
      (with-redefs [gameplay/use-mouse-wheel-enabled? (fn [] false)]
        ((:client-on-slot-wheel! hooks) "p1" 0 1.0))
      (is (= [{:msg-id catalog/MSG-SLOT-KEY-DOWN
               :payload {:ctx-id "ctx-penetrate" :skill-id :penetrate-teleport :key-idx 0}}
              {:msg-id catalog/MSG-CTX-CHANNEL
               :payload {:ctx-id "ctx-penetrate"
                         :channel :penetrate-tp/set-distance
                         :payload {:delta 2.0}}}]
             @sent)))))


(deftest client-terminated-push-clears-slot-context-and-terminates-local-context-test
  (let [handlers (atom {})
        terminated (atom [])]
    (client-ui-hooks/set-slot-context-for-test! "p1" 0 "ctx-dead")
    (with-redefs [net-client/register-push-handler! (fn [msg-id handler-fn]
                                                      (swap! handlers assoc msg-id handler-fn)
                                                      nil)
                  ctx/terminate-context! (fn [ctx-id terminate-fn]
                                           (swap! terminated conj [ctx-id terminate-fn])
                                           nil)]
      (client-ui-hooks/register-client-push-handlers!)
      ((get @handlers catalog/MSG-CTX-TERMINATED) {:ctx-id "ctx-dead"})
      (is (empty? (:slot-context-ids (client-ui-hooks/client-ui-state-snapshot "p1"))))
      (is (= [["ctx-dead" nil]] @terminated)))))

(deftest sync-preset-push-rebuilds-default-key-group-test
  (let [handlers (atom {})
        rebuilt (atom [])]
    (with-redefs [net-client/register-push-handler! (fn [msg-id handler-fn]
                                                      (swap! handlers assoc msg-id handler-fn)
                                                      nil)
                  client-keybinds/update-default-group! (fn [player-uuid]
                                                         (swap! rebuilt conj player-uuid)
                                                         nil)]
      (client-ui-hooks/register-client-push-handlers!)
      ((get @handlers catalog/MSG-SYNC-PRESET) {:uuid "p1"
                                                :preset-data {:active-preset 0
                                                              :presets []}})
      (is (= ["p1"] @rebuilt)))))

(deftest sync-runtime-push-clears-keybind-state-and-aborts-active-slot-contexts-test
  (let [handlers (atom {})
        sent (atom [])
        terminated (atom [])
        cleared (atom [])
        cleared-groups (atom [])]
    (with-client-player-state-owner
      "p1"
      #(ps/set-player-state! "p1" {:ability-data {:category-id :electromaster
                                                   :learned-skills [:railgun]}}))
    (client-ui-hooks/set-slot-context-for-test! "p1" 0 "ctx-runtime-reset")
    (with-redefs [net-client/register-push-handler! (fn [msg-id handler-fn]
                                                      (swap! handlers assoc msg-id handler-fn)
                                                      nil)
                  net-client/send-to-server (fn [msg-id payload]
                                              (swap! sent conj {:msg-id msg-id :payload payload})
                                              nil)
                  ctx/terminate-context! (fn [ctx-id terminate-fn]
                                           (swap! terminated conj [ctx-id terminate-fn])
                                           nil)
                  client-keybinds/clear-client-keybind-state! (fn [owner]
                                                                (swap! cleared conj owner)
                                                                nil)
                  client-keybinds/clear-key-group! (fn [group]
                                                     (swap! cleared-groups conj group)
                                                     nil)]
      (client-ui-hooks/register-client-push-handlers!)
      ((get @handlers catalog/MSG-SYNC-RUNTIME) {:uuid "p1"
                                                 :ability-data {:category-id :meltdowner
                                                                :learned-skills []}})
      (is (= [{:msg-id catalog/MSG-SLOT-KEY-ABORT
               :payload {:ctx-id "ctx-runtime-reset" :key-idx 0}}]
             @sent))
      (is (= [["ctx-runtime-reset" nil]] @terminated))
      (is (= ["p1"] @cleared))
      (is (= [:default] @cleared-groups))
      (is (empty? (:slot-context-ids (client-ui-hooks/client-ui-state-snapshot "p1")))))))

(deftest sync-resource-push-disables-held-input-and-clears-keybind-state-test
  (let [handlers (atom {})
        sent (atom [])
        terminated (atom [])
        cleared (atom [])]
    (with-client-player-state-owner
      "p1"
      #(ps/set-player-state! "p1" {:resource-data {:activated true
                                                    :overload-fine true
                                                    :interferences #{}}}))
    (client-ui-hooks/set-slot-context-for-test! "p1" 0 "ctx-resource-reset")
    (with-redefs [net-client/register-push-handler! (fn [msg-id handler-fn]
                                                      (swap! handlers assoc msg-id handler-fn)
                                                      nil)
                  net-client/send-to-server (fn [msg-id payload]
                                              (swap! sent conj {:msg-id msg-id :payload payload})
                                              nil)
                  ctx/terminate-context! (fn [ctx-id terminate-fn]
                                           (swap! terminated conj [ctx-id terminate-fn])
                                           nil)
                  client-keybinds/clear-client-keybind-state! (fn [owner]
                                                                (swap! cleared conj owner)
                                                                nil)]
      (client-ui-hooks/register-client-push-handlers!)
      ((get @handlers catalog/MSG-SYNC-RESOURCE) {:uuid "p1"
                                                  :resource-data {:activated false
                                                                  :overload-fine true
                                                                  :interferences #{}}})
      (is (= [{:msg-id catalog/MSG-SLOT-KEY-ABORT
               :payload {:ctx-id "ctx-resource-reset" :key-idx 0}}]
             @sent))
      (is (= [["ctx-resource-reset" nil]] @terminated))
      (is (= ["p1"] @cleared))
      (is (empty? (:slot-context-ids (client-ui-hooks/client-ui-state-snapshot "p1")))))))

(deftest charge-coin-hooks-notify-and-visual-state-test
  (let [hooks (client-ui-hooks/runtime-client-ui-hooks)]
    (with-redefs [skill-config/tunable-int (fn [skill-id field-id]
                                                               (case [skill-id field-id]
                                                                 [:railgun :qte.coin-window-ms] 1000
                                                                 [:railgun :charge.item-charge-ticks] 20
                                                                 1))
                  skill-config/tunable-double (fn [skill-id field-id]
                                                                  (case [skill-id field-id]
                                                                    [:railgun :qte.coin-active-threshold] 0.6
                                                                    0.0))
                  ctx/get-all-contexts-for-player (fn [& _] [])]
      ((:client-notify-visual-event! hooks) :ac/charge-coin-throw {:player-uuid "p1"})
      (let [visual ((:client-visual-state hooks) :ac/charge-coin {:player-uuid "p1"})]
        (is (true? (:active? visual)))
        (is (false? (:coin-active? visual)))
        (is (number? (:coin-progress visual)))
        (is (number? (:charge-ratio visual)))))))

(deftest charge-coin-state-isolated-by-client-session-test
  (let [hooks (client-ui-hooks/runtime-client-ui-hooks)]
    (with-redefs [skill-config/tunable-int (fn [skill-id field-id]
                                             (case [skill-id field-id]
                                               [:railgun :qte.coin-window-ms] 1000
                                               [:railgun :charge.item-charge-ticks] 20
                                               1))
                  skill-config/tunable-double (fn [_ _] 0.6)
                  ctx/get-all-contexts-for-player (fn [& _] [])]
      (binding [client-keybinds/*client-session-id* :session-a]
        ((:client-notify-visual-event! hooks) :ac/charge-coin-throw {:player-uuid "p1"}))
      (is (some? (:charge-coin-state (client-ui-hooks/client-ui-state-snapshot
                                      {:client-session-id :session-a :player-uuid "p1"}))))
      (is (nil? (:charge-coin-state (client-ui-hooks/client-ui-state-snapshot
                                     {:client-session-id :session-b :player-uuid "p1"})))))))

(deftest charge-coin-visual-state-prefers-item-charge-context-test
  (let [hooks (client-ui-hooks/runtime-client-ui-hooks)]
    (with-redefs [skill-config/tunable-int (fn [skill-id field-id]
                                                               (case [skill-id field-id]
                                                                 [:railgun :qte.coin-window-ms] 1000
                                                                 [:railgun :charge.item-charge-ticks] 20
                                                                 1))
                  skill-config/tunable-double (fn [skill-id field-id]
                                                                  (case [skill-id field-id]
                                                                    [:railgun :qte.coin-active-threshold] 0.6
                                                                    0.0))
                  ctx/get-all-contexts-for-player (fn [& _]
                                                    [{:skill-id :railgun
                                                      :skill-state {:mode :item-charge :charge-ticks 10}}])]
      (let [visual ((:client-visual-state hooks) :ac/charge-coin {:player-uuid "p1"})]
        (is (true? (:active? visual)))
        (is (= 10 (:charge-ticks visual)))
        (is (false? (:coin-active? visual)))
        (is (= 0.0 (:coin-progress visual)))
        (is (= 0.5 (:charge-ratio visual)))))))

(deftest body-intensify-charge-visual-state-default-and-active-test
  (let [hooks (client-ui-hooks/runtime-client-ui-hooks)]
    (with-redefs [skill-config/tunable-int (fn [skill-id field-id]
                                              (case [skill-id field-id]
                                                [:body-intensify :charge.max-time] 40
                                                1))
                  ctx/get-all-contexts-for-player (fn [& _] [])]
      (let [visual ((:client-visual-state hooks) :ac/body-intensify-charge {:player-uuid "p1"})]
        (is (false? (:active? visual)))
        (is (= 0 (:charge-ticks visual)))
        (is (= 0.0 (:charge-ratio visual)))))
    (with-redefs [skill-config/tunable-int (fn [skill-id field-id]
                                              (case [skill-id field-id]
                                                [:body-intensify :charge.max-time] 40
                                                1))
                  ctx/get-all-contexts-for-player (fn [& _]
                                                    [{:skill-id :body-intensify
                                                      :skill-state {:hold-ticks 20}}])]
      (let [visual ((:client-visual-state hooks) :ac/body-intensify-charge {:player-uuid "p1"})]
        (is (true? (:active? visual)))
        (is (= 20 (:charge-ticks visual)))
        (is (= 0.5 (:charge-ratio visual)))))))

(deftest current-charging-visual-state-default-and-active-test
  (let [hooks (client-ui-hooks/runtime-client-ui-hooks)]
    (with-redefs [current-charging-fx/current-state
                  (fn [_] {:active? false
                           :blending? false
                           :is-item false
                           :good? false
                           :charge-ticks 0
                           :charge-ratio 0.0})]
      (let [visual ((:client-visual-state hooks) :ac/current-charging {:player-uuid "p1"})]
        (is (false? (:active? visual)))
        (is (false? (:is-item visual)))
        (is (false? (:good? visual)))
        (is (= 0 (:charge-ticks visual)))
        (is (= 0.0 (:charge-ratio visual)))))
    (with-redefs [current-charging-fx/current-state
          (fn [_] {:active? true
             :blending? false
             :is-item true
             :good? true
             :charge-ticks 30
             :charge-ratio 0.75})]
      (let [visual ((:client-visual-state hooks) :ac/current-charging {:player-uuid "p1"})]
        (is (true? (:active? visual)))
        (is (true? (:is-item visual)))
        (is (true? (:good? visual)))
        (is (= 30 (:charge-ticks visual)))
        (is (= 0.75 (:charge-ratio visual)))))))

(deftest current-charging-overlay-elements-render-hud-state-test
  (with-redefs [current-charging-fx/current-state
                (fn [_] {:active? true
                         :blending? false
                         :is-item false
                         :good? true
                         :charge-ticks 20
                         :charge-ratio 0.5})]
    (let [elements ((var-get #'cn.li.ac.ability.adapters.client-ui-hooks/current-charging-overlay-elements)
                    "p1" 320 180)
          kinds (mapv :kind elements)]
      (is (some #{:fullscreen-fill} kinds))
      (is (some #{:text} kinds))
      (is (>= (count (filter #{:fill} kinds)) 3)))))

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
                client-keybinds/get-preset-switch-state (fn [_] nil)]
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
                client-keybinds/get-preset-switch-state (fn [_] nil)]
    ;; Pre-seed a wave circle born 100ms ago so alpha>0 at now-ms=1000
    (client-ui-hooks/seed-vm-wave-state-for-test!
     "p1"
     [{:x 160.0 :y 90.0 :born-ms 900 :life-ms 600
       :start-size 10.0 :end-size 50.0 :seed 0.0}])
    (let [plan (client-ui-hooks/build-client-overlay-plan
                "p1" 320 180 {:now-ms 1000})
          kinds (mapv :kind (:elements plan))]
      (is (= 1 (count (filter #{:content-crosshair} kinds))))
      (is (some #{:blit-texture} kinds)))))

(deftest movement-key-hooks-route-to-flashing-channel-test
  (let [sent (atom [])
        hooks (client-ui-hooks/runtime-client-ui-hooks)]
    (with-redefs [client-keybinds/get-skill-id-for-slot-public (fn [_ _] :flashing)
                  ctx-mgr/activate-context! (fn [_ _] {:id "ctx-flashing"})
                  ctx/get-context (fn [ctx-id]
                                    (when (= ctx-id "ctx-flashing")
                                      {:id "ctx-flashing" :skill-id :flashing}))
                  net-client/send-to-server
                  (fn
                    ([msg-id payload]
                     (swap! sent conj {:msg-id msg-id :payload payload}))
                    ([msg-id payload _callback]
                     (swap! sent conj {:msg-id msg-id :payload payload})))]
      ((:client-on-slot-key-down! hooks) "p1" 0)
      ((:client-on-movement-key-down! hooks) "p1" :forward)
      ((:client-on-movement-key-tick! hooks) "p1" :forward)
      ((:client-on-movement-key-up! hooks) "p1" :forward)
      (is (= [catalog/MSG-SLOT-KEY-DOWN
              catalog/MSG-CTX-CHANNEL
              catalog/MSG-CTX-CHANNEL
              catalog/MSG-CTX-CHANNEL]
             (mapv :msg-id @sent)))
      (is (= [{:ctx-id "ctx-flashing" :skill-id :flashing :key-idx 0}
              {:ctx-id "ctx-flashing" :channel :flashing/move-down :payload {:key :forward}}
              {:ctx-id "ctx-flashing" :channel :flashing/move-tick :payload {:key :forward}}
              {:ctx-id "ctx-flashing" :channel :flashing/move-up :payload {:key :forward}}]
             (mapv :payload @sent))))))