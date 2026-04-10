(ns cn.li.ac.ability.client.keybinds
  "Key binding state tracking and event handling (AC layer - no Minecraft imports)."
  (:require [cn.li.ac.ability.client.ability-runtime :as runtime]
            [cn.li.ac.client.platform-bridge :as client-bridge]))

;; Dynamic var for getting player UUID (set by forge layer)
(def ^:dynamic *get-player-uuid-fn* nil)

;; State tracking for key transitions
(defonce ^:private key-states
  (atom {:skill-keys [false false false false]
         :gui-keys {:skill-tree false
                    :preset-editor false}}))

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
      (cond
        ;; Key pressed (transition from up to down)
        (and (not was-down) is-down)
        (runtime/on-slot-key-down! player-uuid key-idx)

        ;; Key held (still down)
        (and was-down is-down)
        (runtime/on-slot-key-tick! player-uuid key-idx)

        ;; Key released (transition from down to up)
        (and was-down (not is-down))
        (runtime/on-slot-key-up! player-uuid key-idx)))

    ;; Update state
    (swap! key-states assoc-in [:skill-keys key-idx] is-down)))

(defn on-gui-key-event
  "Handle GUI key state change. Opens screens on key press."
  [gui-type is-down]
  (let [was-down (get-in @key-states [:gui-keys gui-type])]
    ;; Only trigger on key press (not held or released)
    (when (and (not was-down) is-down)
      (when-let [player-uuid (get-client-player-uuid)]
        (case gui-type
          :skill-tree
          (client-bridge/open-skill-tree-screen! player-uuid)

          :preset-editor
          (client-bridge/open-preset-editor-screen! player-uuid)

          nil)))

    ;; Update state
    (swap! key-states assoc-in [:gui-keys gui-type] is-down)))

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
