(ns cn.li.mc1201.client.overlay-state-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.mc1201.client.overlay.state :as overlay-state]))

(def ^:private owner-a {:client-session-id [:client :session-a]
                        :player-uuid "player-a"})
(def ^:private owner-b {:client-session-id [:client :session-a]
                        :player-uuid "player-b"})
(def ^:private owner-c {:client-session-id [:client :session-b]
                        :player-uuid "player-a"})

(use-fixtures :each
  (fn [f]
    (overlay-state/call-with-overlay-state-runtime
      (overlay-state/create-overlay-state-runtime)
      (fn []
        (f)))))

(deftest client-activated-overlay-isolated-by-owner-test
  (overlay-state/set-client-activated! owner-a true)
  (overlay-state/set-client-activated! owner-b false)
  (is (true? (overlay-state/get-client-activated owner-a)))
  (is (false? (overlay-state/get-client-activated owner-b)))
  (is (nil? (overlay-state/get-client-activated owner-c))))

(deftest clear-client-overlay-session-removes-only-target-session-test
  (overlay-state/set-client-activated! owner-a true)
  (overlay-state/set-client-activated! owner-b true)
  (overlay-state/set-client-activated! owner-c true)
  (overlay-state/clear-client-overlay-session! [:client :session-a])
  (is (nil? (overlay-state/get-client-activated owner-a)))
  (is (nil? (overlay-state/get-client-activated owner-b)))
  (is (true? (overlay-state/get-client-activated owner-c))))

(deftest overlay-state-runtime-isolation-test
  (let [runtime-b (overlay-state/create-overlay-state-runtime)]
    (overlay-state/set-client-activated! owner-a true)
    (overlay-state/call-with-overlay-state-runtime
      runtime-b
      (fn []
        (overlay-state/set-client-activated! owner-a false)
        (is (false? (overlay-state/get-client-activated owner-a)))))
    (is (true? (overlay-state/get-client-activated owner-a)))))