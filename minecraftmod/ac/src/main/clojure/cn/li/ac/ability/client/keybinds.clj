(ns cn.li.ac.ability.client.keybinds
  "Key binding state tracking and event handling (AC layer - no Minecraft imports)."
  (:require [cn.li.ac.ability.client.runtime :as runtime]
            [cn.li.ac.ability.client.api :as api]
            [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.util.log :as log]))

;; Dynamic var for getting player UUID (set by forge layer)
(def ^:dynamic *get-player-uuid-fn* nil)

;; State tracking for key transitions
(defonce ^:private key-states
  (atom {:skill-keys [false false false false]
         :gui-keys {:skill-tree false
                    :preset-editor false}}))

(defn- activated?
  [player-uuid]
  (boolean (get-in (ps/get-player-state player-uuid) [:resource-data :activated])))

(defn- get-client-player-uuid
  "Get current client player UUID. Must be provided by forge layer."
  []
  (when *get-player-uuid-fn*
    (*get-player-uuid-fn*)))

(defn on-skill-key-event
  "Handle skill key state change. Called by forge layer with key index and current state."
  [key-idx is-down]
  (let [was-down (get-in @key-states [:skill-keys key-idx])]
    (when-let [player-uuid (get-client-player-uuid)]
      (if (activated? player-uuid)
        (cond
          ;; Key pressed (transition from up to down)
          (and (not was-down) is-down)
          (runtime/on-slot-key-down! player-uuid key-idx)

          ;; Key held (still down)
          (and was-down is-down)
          (runtime/on-slot-key-tick! player-uuid key-idx)

          ;; Key released (transition from down to up)
          (and was-down (not is-down))
          (runtime/on-slot-key-up! player-uuid key-idx))
        ;; Ability mode disabled: abort local held state cleanly.
        (when was-down
          (runtime/on-slot-key-up! player-uuid key-idx))))

    ;; Update state
    (swap! key-states assoc-in [:skill-keys key-idx] is-down)))


(defn on-gui-key-event
  "Handle GUI key state change. Opens screens or toggles mode on key press."
  [gui-type is-down]
  (let [was-down (get-in @key-states [:gui-keys gui-type])]
    (when-let [player-uuid (get-client-player-uuid)]
      ;; GUI keys trigger on key press only.
      (when (and (not was-down) is-down)
        (case gui-type
          :skill-tree
          (client-bridge/open-skill-tree-screen! player-uuid)

          :preset-editor
          (client-bridge/open-preset-editor-screen! player-uuid)

          nil)))

    ;; Update state
    (swap! key-states assoc-in [:gui-keys gui-type] is-down)))

(defn trigger-mode-switch!
  "Toggle ability activation mode once. Intended to be called by platform key handling
  after it has determined this was a valid short press."
  ([]
   (trigger-mode-switch! nil))
  ([player-uuid]
   (when-let [player-uuid (or player-uuid (get-client-player-uuid))]
    (let [state (ps/get-player-state player-uuid)
        current (boolean (get-in state [:resource-data :activated]))
        next-state (not current)]
      (log/info "[V-TRACE][AC][CLIENT][TOGGLE]"
          {:uuid (str player-uuid)
           :current current
           :next next-state})
      (api/req-set-activated! next-state nil)))))

(defn tick-keys!
  "Main tick function called by forge layer. key-state-fn returns boolean for each key."
  [key-state-fn]
  ;; Poll skill keys (Z, X, C, V)
  (doseq [idx (range 4)]
    (on-skill-key-event idx (key-state-fn [:skill idx])))

  ;; Poll GUI keys
  (on-gui-key-event :skill-tree (key-state-fn [:gui :skill-tree]))
  (on-gui-key-event :preset-editor (key-state-fn [:gui :preset-editor])))

(defn reset-all-keys!
  "Reset all key states. Called on disconnect or dimension change."
  []
  (reset! key-states {:skill-keys [false false false false]
                      :gui-keys {:skill-tree false
                                :preset-editor false}}))
