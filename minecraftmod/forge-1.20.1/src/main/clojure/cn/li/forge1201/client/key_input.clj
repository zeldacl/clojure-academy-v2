(ns cn.li.forge1201.client.key-input
  "DEPRECATED: Minimal shim for legacy code that imports key_input.
   
   Previous implementation contained AC business logic mixed with platform code.
   This has been refactored:
   - AC keybinding config → ac/input_ids.clj
   - Platform event handling → keyboard_event_handler.clj + key_mapping_adapter.clj
   - GLFW polling → mc1201/glfw_polling_core.clj
   - Vanilla suppression → mc1201/vanilla_input_control_core.clj
   
   Kept only for backward compatibility with existing tests."
  (:require [cn.li.mcmod.util.log :as log]))

;; ===== Minimal Legacy Support (for existing tests only) =====

(defn create-key-input-runtime
  "Legacy test support - creates a runtime container for tests"
  ([] (create-key-input-runtime {}))
  ([initial-state]
   {:state (atom (merge {:slot-keys []
                         :screen-keys {}
                         :raw-v-state {}
                         :key-scheme :alternative
                         :override-active? {}}
                        initial-state))}))

(def ^:private _key-input-runtime (delay (create-key-input-runtime)))

(def ^:dynamic *key-input-runtime* nil)

(defn current-key-input-runtime []
  (or *key-input-runtime*
      @_key-input-runtime))

(defmacro with-key-input-runtime [runtime & body]
  `(binding [*key-input-runtime* ~runtime]
     ~@body))

(defn call-with-key-input-runtime [runtime f]
  (binding [*key-input-runtime* runtime]
    (f)))

(defn key-input-runtime-state-atom []
  (:state (current-key-input-runtime)))

(defn key-input-runtime-state-snapshot []
  @(key-input-runtime-state-atom))

(defn update-key-input-runtime! [f & args]
  (apply swap! (key-input-runtime-state-atom) f args))

(defn set-key-scheme! [scheme]
  (update-key-input-runtime! assoc :key-scheme scheme)
  (log/debug "Key scheme set" {:scheme scheme}))

(defn get-key-scheme []
  (:key-scheme (key-input-runtime-state-snapshot)))

(defn get-slot-keys []
  (:slot-keys (key-input-runtime-state-snapshot)))

(defn get-screen-keys []
  (vals (:screen-keys (key-input-runtime-state-snapshot))))

(defn input-state-snapshot []
  (let [{:keys [raw-v-state override-active?]} (key-input-runtime-state-snapshot)]
    {:raw-v-state raw-v-state
     :override-active? override-active?}))

(defn reset-input-state-for-test!
  ([] (reset-input-state-for-test! {}))
  ([{:keys [raw-v-state-map override-active-map key-scheme-value slot-keys-list screen-keys-map]
     :or {raw-v-state-map {}
          override-active-map {}
          key-scheme-value :alternative
          slot-keys-list []
          screen-keys-map {}}}]
   (update-key-input-runtime! merge
                              {:raw-v-state raw-v-state-map
                               :override-active? override-active-map
                               :key-scheme key-scheme-value
                               :slot-keys slot-keys-list
                               :screen-keys screen-keys-map})
   nil))

(defn clear-owner-input-state! [_owner]
  ;; Legacy test function - does nothing now
  nil)

(defn clear-client-input-session! [_session-id]
  ;; Legacy test function - does nothing now
  nil)

(defn register-keybinds!
  "Legacy stub - actual keybind registration now happens via AC + platform adapters"
  []
  (log/debug "register-keybinds! called (legacy stub)")
  nil)

(defn tick-input!
  "Legacy stub - input handling now happens via AC protocol + platform handlers"
  []
  (log/debug "tick-input! called (legacy stub)")
  nil)

(defn init! []
  ;; DEPRECATED: All keyboard input handling has been refactored to AC + platform adapters.
  ;; This function is kept for backward compatibility but does nothing.
  ;; See: cn.li.forge1201.client.keyboard_event_handler for new implementation.
  (log/info "Client key input initialized (legacy - no-op)"))
