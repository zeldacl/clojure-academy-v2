(ns cn.li.fabric1201.client.runtime-bridge
  "DEPRECATED: Minimal shim for legacy code that imports runtime_bridge.
   
   Previous implementation contained AC business logic mixed with platform code.
   This has been refactored:
   - AC keybinding config → ac/input_ids.clj
   - Fabric keyboard polling → keyboard_init.clj + mc1201/glfw_polling_core.clj
   - Vanilla suppression → mc1201/vanilla_input_control_core.clj
   
   Kept only for backward compatibility with existing tests."
  (:require [cn.li.mcmod.util.log :as log]))

;; ===== Minimal Legacy Support (for existing tests only) =====

(defn create-input-runtime
  "Legacy test support - creates a runtime container for tests"
  ([] (create-input-runtime {}))
  ([initial-state]
   {:state (atom (merge {:raw-v-state {}
                         :raw-n-state {}}
                        initial-state))}))

(defonce ^:private installed-input-runtime
  (create-input-runtime))

(def ^:dynamic *input-runtime*
  installed-input-runtime)

(defn current-input-runtime []
  *input-runtime*)

(defmacro with-input-runtime [runtime & body]
  `(binding [*input-runtime* ~runtime]
     ~@body))

(defn call-with-input-runtime [runtime f]
  (binding [*input-runtime* runtime]
    (f)))

(defn input-runtime-state-atom []
  (:state (current-input-runtime)))

(defn input-runtime-state-snapshot []
  @(input-runtime-state-atom))

(defn update-input-runtime! [f & args]
  (apply swap! (input-runtime-state-atom) f args))

(defn input-state-snapshot []
  (let [{:keys [raw-v-state raw-n-state]} (input-runtime-state-snapshot)]
    {:raw-v-state raw-v-state
     :raw-n-state raw-n-state}))

(defn reset-input-state-for-test!
  ([] (reset-input-state-for-test! {}))
  ([{:keys [raw-v-state-map raw-n-state-map]
     :or {raw-v-state-map {}
          raw-n-state-map {}}}]
   (update-input-runtime! merge
                         {:raw-v-state raw-v-state-map
                          :raw-n-state raw-n-state-map})
   nil))

(defn clear-owner-input-state! [_owner]
  ;; Legacy test function - does nothing now
  nil)

(defn clear-client-input-session! [_session-id]
  ;; Legacy test function - does nothing now
  nil)

;; ===== Legacy Client Slot Handler Stubs =====
;; These were used to route keyboard input to AC before the refactor.
;; Now AC receives input directly through the protocol.

(defn on-slot-key-down! [player-uuid key-idx]
  (log/debug "on-slot-key-down! stub" {:uuid player-uuid :idx key-idx}))

(defn on-slot-key-tick! [player-uuid key-idx]
  (log/debug "on-slot-key-tick! stub" {:uuid player-uuid :idx key-idx}))

(defn on-slot-key-up! [player-uuid key-idx]
  (log/debug "on-slot-key-up! stub" {:uuid player-uuid :idx key-idx}))

(defn on-slot-key-abort! [player-uuid key-idx]
  (log/debug "on-slot-key-abort! stub" {:uuid player-uuid :idx key-idx}))

(defn on-movement-key-down! [player-uuid movement-key]
  (log/debug "on-movement-key-down! stub" {:uuid player-uuid :key movement-key}))

(defn on-movement-key-tick! [player-uuid movement-key]
  (log/debug "on-movement-key-tick! stub" {:uuid player-uuid :key movement-key}))

(defn on-movement-key-up! [player-uuid movement-key]
  (log/debug "on-movement-key-up! stub" {:uuid player-uuid :key movement-key}))

(defn tick-client!
  "Legacy stub - all client ticking is now handled via AC protocol + platform handlers"
  []
  nil)

(defn init!
  []
  ;; DEPRECATED: All initialization has been moved to keyboard_init.clj
  ;; This function is kept for backward compatibility but does nothing.
  (log/info "Client runtime bridge initialized (legacy - no-op)"))
