(ns cn.li.mcmod.client.content-actions
  "Client content action hooks via Framework function map.

   Content actions stored at [:platform :content-actions].
   Client tick hooks stored at [:service :client-tick-hooks]."
  (:require [cn.li.mcmod.framework :as fw]))

(defn install-client-content-actions!
  [actions _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :content-actions] actions)) nil)

(defn content-actions-available?
  []
  (boolean (get-in @(fw/fw-atom) [:platform :content-actions])))

(defn- action-op [k & args]
  (when-let [f (get-in @(fw/fw-atom) [:platform :content-actions k])]
    (apply f args)))

;; ============================================================================
;; Client tick hooks
;; ============================================================================

(defn register-client-tick-hook!
  "Register a zero-arg fn to run on every client tick."
  [f]
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom update-in [:service :client-tick-hooks] (fn [v] (conj (or v []) f)))))

(defn run-client-tick-hooks!
  "Run all registered client tick hooks. Called by platform client tick handler."
  []
  (doseq [f (get-in @(fw/fw-atom) [:service :client-tick-hooks] [])]
    (try (f) (catch Throwable _ nil))))

(defn reset-client-content-actions-for-test!
  []
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom assoc-in [:platform :content-actions] nil)
    (swap! fw-atom assoc-in [:service :client-tick-hooks] []))
  nil)
