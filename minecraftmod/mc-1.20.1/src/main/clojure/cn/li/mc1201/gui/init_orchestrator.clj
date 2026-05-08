(ns cn.li.mc1201.gui.init-orchestrator
  "Shared helper functions for platform GUI init flows."
  (:require [cn.li.mcmod.util.log :as log]))

(defn phase-start!
  [platform-label phase-label]
  (log/info (str "=== Initializing " platform-label " GUI System (" phase-label ") ===")))

(defn phase-done!
  [platform-label phase-label]
  (log/info (str "=== " platform-label " GUI System (" phase-label ") Initialized ===")))

(defn safe-init!
  [error-prefix init-fn]
  (try
    (init-fn)
    true
    (catch Exception e
      (log/error error-prefix (.getMessage e))
      (.printStackTrace e)
      false)))

(defn verify-checks!
  [title checks]
  (log/info title)
  (doseq [[check-name result] checks]
    (log/info "  " check-name ":" (if result "✓" "✗")))
  (let [all-passed? (every? true? (vals checks))]
    (if all-passed?
      (log/info "All GUI system checks passed!")
      (log/error "Some GUI system checks failed!"))
    all-passed?))
