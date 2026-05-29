(ns cn.li.fabric1201.client.runtime-bridge-state-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.mc1201.client.effects.particle]
            [cn.li.mc1201.client.effects.sound]
            [cn.li.mc1201.client.session :as client-session]
            [cn.li.mc1201.client.session-cleanup :as session-cleanup]
            [cn.li.mcmod.hooks.core]
            [cn.li.fabric1201.client.runtime-bridge :as runtime-bridge]))

(def ^:private owner-a {:client-session-id [:client :session-a]
                        :player-uuid "player-a"})
(def ^:private owner-b {:client-session-id [:client :session-b]
                        :player-uuid "player-b"})

(defn- owner-key [owner]
  [(:client-session-id owner) (:player-uuid owner)])

(use-fixtures :each
  (fn [f]
    (runtime-bridge/call-with-input-runtime
      (runtime-bridge/create-input-runtime)
      (fn []
        (f)))))

(deftest clear-owner-input-state-removes-only-target-owner-test
  (runtime-bridge/reset-input-state-for-test!
    {:raw-v-state-map {(owner-key owner-a) {:was-down true :down-at-ns 1}
                       (owner-key owner-b) {:was-down false :down-at-ns nil}}
     :raw-n-state-map {(owner-key owner-a) {:was-down true}
                       (owner-key owner-b) {:was-down false}}})
  (runtime-bridge/clear-owner-input-state! owner-a)
  (let [snapshot (runtime-bridge/input-state-snapshot)]
    (is (nil? (get-in snapshot [:raw-v-state (owner-key owner-a)])))
    (is (nil? (get-in snapshot [:raw-n-state (owner-key owner-a)])))
    (is (= {:was-down false :down-at-ns nil}
           (get-in snapshot [:raw-v-state (owner-key owner-b)])))
    (is (= {:was-down false}
           (get-in snapshot [:raw-n-state (owner-key owner-b)])))))

(deftest clear-client-input-session-removes-only-target-session-test
  (runtime-bridge/reset-input-state-for-test!
    {:raw-v-state-map {(owner-key owner-a) {:was-down true :down-at-ns 1}
                       (owner-key owner-b) {:was-down false :down-at-ns nil}}
     :raw-n-state-map {(owner-key owner-a) {:was-down true}
                       (owner-key owner-b) {:was-down false}}})
  (runtime-bridge/clear-client-input-session! [:client :session-a])
  (let [snapshot (runtime-bridge/input-state-snapshot)]
    (is (nil? (get-in snapshot [:raw-v-state (owner-key owner-a)])))
    (is (nil? (get-in snapshot [:raw-n-state (owner-key owner-a)])))
    (is (= {:was-down false :down-at-ns nil}
           (get-in snapshot [:raw-v-state (owner-key owner-b)])))
    (is (= {:was-down false}
           (get-in snapshot [:raw-n-state (owner-key owner-b)])))))

(deftest input-runtime-isolation-test
  (let [runtime-b (runtime-bridge/create-input-runtime)]
    (runtime-bridge/reset-input-state-for-test!
      {:raw-v-state-map {(owner-key owner-a) {:was-down true :down-at-ns 1}}
       :raw-n-state-map {(owner-key owner-a) {:was-down true}}})
    (runtime-bridge/call-with-input-runtime
      runtime-b
      (fn []
        (runtime-bridge/reset-input-state-for-test!
          {:raw-v-state-map {(owner-key owner-b) {:was-down false :down-at-ns nil}}
           :raw-n-state-map {(owner-key owner-b) {:was-down false}}})
        (let [snapshot (runtime-bridge/input-state-snapshot)]
          (is (= {:was-down false :down-at-ns nil}
                 (get-in snapshot [:raw-v-state (owner-key owner-b)])))
          (is (= {:was-down false}
                 (get-in snapshot [:raw-n-state (owner-key owner-b)]))))))
    (let [snapshot (runtime-bridge/input-state-snapshot)]
      (is (= {:was-down true :down-at-ns 1}
             (get-in snapshot [:raw-v-state (owner-key owner-a)])))
      (is (= {:was-down true}
             (get-in snapshot [:raw-n-state (owner-key owner-a)]))))))

(deftest tick-client-delegates-owner-transition-cleanup-test
  (let [calls (atom [])]
    (with-redefs [session-cleanup/tick-connection-change!
                  (fn [opts]
                    (swap! calls conj [:tick-connection-change opts])
                    nil)
                  client-session/current-local-player-owner (fn [] owner-a)
                  client-session/client-session-id (fn [] [:client :session-a])
                  cn.li.fabric1201.client.runtime-bridge/tick-mode-switch! (fn [] (swap! calls conj [:mode-switch]) nil)
                  cn.li.fabric1201.client.runtime-bridge/tick-cycle-selection! (fn [] (swap! calls conj [:cycle-selection]) nil)
                  cn.li.fabric1201.client.runtime-bridge/tick-content-keys! (fn [] (swap! calls conj [:content-keys]) nil)
                  cn.li.mc1201.client.effects.particle/tick-particles! (fn [] (swap! calls conj [:particles]) nil)
                  cn.li.mc1201.client.effects.sound/tick-sounds! (fn [] (swap! calls conj [:sounds]) nil)
                  client-session/with-current-client-session (fn [f]
                                                               (swap! calls conj [:with-current-client-session])
                                                               (f))
                  cn.li.mcmod.hooks.core/client-tick! (fn [] (swap! calls conj [:client-tick]) nil)]
      (runtime-bridge/tick-client!)
      (is (= :tick-connection-change (ffirst @calls)))
      (is (= runtime-bridge/clear-owner-input-state!
             (get-in (first @calls) [1 :clear-owner-input-state!])))
      (is (not-any? #(= [:clear-client-session [:client :session-a]] %) @calls))
      (is (some #(= [:mode-switch] %) @calls))
      (is (some #(= [:cycle-selection] %) @calls))
      (is (some #(= [:content-keys] %) @calls))
      (is (some #(= [:particles] %) @calls))
      (is (some #(= [:sounds] %) @calls))
      (is (some #(= [:client-tick] %) @calls)))))

(deftest tick-client-clears-client-session-when-owner-missing-test
  (let [calls (atom [])]
    (with-redefs [session-cleanup/tick-connection-change!
                  (fn [opts]
                    (swap! calls conj [:tick-connection-change opts])
                    nil)
                  client-session/current-local-player-owner (fn [] nil)
                  client-session/client-session-id (fn [] [:client :session-a])
                  runtime-bridge/clear-client-input-session! (fn [session-id]
                                                               (swap! calls conj [:clear-client-session session-id])
                                                               nil)
                  cn.li.fabric1201.client.runtime-bridge/tick-mode-switch! (fn [] (swap! calls conj [:mode-switch]) nil)
                  cn.li.fabric1201.client.runtime-bridge/tick-cycle-selection! (fn [] (swap! calls conj [:cycle-selection]) nil)
                  cn.li.fabric1201.client.runtime-bridge/tick-content-keys! (fn [] (swap! calls conj [:content-keys]) nil)
                  cn.li.mc1201.client.effects.particle/tick-particles! (fn [] (swap! calls conj [:particles]) nil)
                  cn.li.mc1201.client.effects.sound/tick-sounds! (fn [] (swap! calls conj [:sounds]) nil)
                  client-session/with-current-client-session (fn [f]
                                                               (swap! calls conj [:with-current-client-session])
                                                               (f))
                  cn.li.mcmod.hooks.core/client-tick! (fn [] (swap! calls conj [:client-tick]) nil)]
      (runtime-bridge/tick-client!)
      (is (= runtime-bridge/clear-owner-input-state!
             (get-in (first @calls) [1 :clear-owner-input-state!])))
      (is (some #(= [:clear-client-session [:client :session-a]] %) @calls)))))