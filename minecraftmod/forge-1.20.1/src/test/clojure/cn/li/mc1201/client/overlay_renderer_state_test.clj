(ns cn.li.mc1201.client.overlay-renderer-state-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.mc1201.client.overlay.renderer :as renderer]))

(def ^:private owner-a {:client-session-id [:client :session-a]
                        :player-uuid "player-a"})
(def ^:private owner-b {:client-session-id [:client :session-b]
                        :player-uuid "player-b"})

(defn- render-owner-key [owner]
  [(:client-session-id owner) (:player-uuid owner)])

(use-fixtures :each
  (fn [f]
    (renderer/reset-overlay-render-state-for-test!)
    (try
      (f)
      (finally
        (renderer/reset-overlay-render-state-for-test!)))))

(deftest overlay-render-state-isolated-by-owner-test
  (renderer/on-mode-switch-key-state! owner-a true)
  (renderer/on-mode-switch-key-state! owner-b true)
  (let [snapshot (renderer/overlay-render-state-snapshot)]
    (is (true? (get-in snapshot [:mode-switch-key-down? (render-owner-key owner-a)])))
    (is (true? (get-in snapshot [:mode-switch-key-down? (render-owner-key owner-b)])))
    (is (true? (get-in snapshot [:showing-numbers? (render-owner-key owner-a)])))
    (is (true? (get-in snapshot [:showing-numbers? (render-owner-key owner-b)])))
    (is (pos-int? (get-in snapshot [:last-show-value-change-ms (render-owner-key owner-a)])))
    (is (pos-int? (get-in snapshot [:last-show-value-change-ms (render-owner-key owner-b)])))))

(deftest clear-overlay-render-state-removes-only-target-owner-test
  (renderer/on-mode-switch-key-state! owner-a true)
  (renderer/on-mode-switch-key-state! owner-b true)
  (renderer/clear-overlay-render-state! owner-a)
  (let [snapshot (renderer/overlay-render-state-snapshot)]
    (is (nil? (get-in snapshot [:mode-switch-key-down? (render-owner-key owner-a)])))
    (is (nil? (get-in snapshot [:showing-numbers? (render-owner-key owner-a)])))
    (is (nil? (get-in snapshot [:last-show-value-change-ms (render-owner-key owner-a)])))
    (is (true? (get-in snapshot [:mode-switch-key-down? (render-owner-key owner-b)])))))

(deftest clear-overlay-render-session-removes-only-target-session-test
  (renderer/on-mode-switch-key-state! owner-a true)
  (renderer/on-mode-switch-key-state! owner-b true)
  (renderer/clear-overlay-render-session! [:client :session-a])
  (let [snapshot (renderer/overlay-render-state-snapshot)]
    (is (nil? (get-in snapshot [:mode-switch-key-down? (render-owner-key owner-a)])))
    (is (true? (get-in snapshot [:mode-switch-key-down? (render-owner-key owner-b)])))))