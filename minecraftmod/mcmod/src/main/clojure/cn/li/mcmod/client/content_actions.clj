(ns cn.li.mcmod.client.content-actions
  "Platform-neutral client content action hooks."
  (:require [cn.li.mcmod.platform.runtime :as prt]))

(def ^:private ^:dynamic *content-actions* nil)

(defn install-client-content-actions!
  [actions label]
  (prt/install-impl! #'*content-actions* actions (or label "client-content-actions")))

(defn content-actions-available?
  []
  (prt/impl-available? #'*content-actions*))

(defn- action-op [k & args]
  (when-let [ops (prt/impl-current #'*content-actions*)]
    (when-let [f (get ops k)]
      (apply f args))))

;; ============================================================================
;; Client tick hooks — content modules register callbacks here; platform
;; adapters call run-client-tick-hooks! on each client tick.
;; ============================================================================

(defonce ^:private client-tick-hooks* (atom []))

(defn register-client-tick-hook!
  "Register a zero-arg fn to run on every client tick. Called during content init."
  [f]
  (swap! client-tick-hooks* conj f))

(defn run-client-tick-hooks!
  "Run all registered client tick hooks. Called by platform client tick handler."
  []
  (doseq [f @client-tick-hooks*]
    (try (f) (catch Throwable _ nil))))

(defn reset-client-content-actions-for-test!
  []
  (alter-var-root #'*content-actions* (constantly nil))
  (reset! client-tick-hooks* [])
  nil)
