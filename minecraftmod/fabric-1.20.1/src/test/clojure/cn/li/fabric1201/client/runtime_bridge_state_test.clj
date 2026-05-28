(ns cn.li.fabric1201.client.runtime-bridge-state-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
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