(ns cn.li.fabric1201.client.runtime-bridge
  "CLIENT-ONLY Fabric adapter for client runtime ticking."
  (:require [cn.li.mc1201.client.input.mode-switch :as mode-switch]
            [cn.li.mc1201.client.effects.particle :as particle]
            [cn.li.mc1201.client.effects.sound :as sound]
            [cn.li.mc1201.client.session-cleanup :as session-cleanup]
            [cn.li.mc1201.client.session :as client-session]
            [cn.li.fabric1201.client.overlay-renderer :as overlay-renderer]
            [cn.li.mc1201.client.overlay.state :as overlay-state]
            [cn.li.mcmod.hooks.core :as power-runtime]
            [cn.li.mcmod.util.log :as log])
  (:import [net.fabricmc.fabric.api.client.event.lifecycle.v1 ClientTickEvents ClientTickEvents$EndTick]
           [net.minecraft.client Minecraft]
           [org.lwjgl.glfw GLFW]))

(defonce ^:private tick-listener-registered? (atom false))
(defonce ^:private raw-v-state (atom {}))
(defonce ^:private raw-n-state (atom {}))
(def ^:private toggle-primary-state-input-id :content/toggle-primary-state)
(def ^:private cycle-selection-input-id :content/cycle-selection)

(defn- client-session-id []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    [:client (System/identityHashCode mc)]))

(defn- with-client-session [f]
  (binding [power-runtime/*client-session-id* (client-session-id)]
    (f)))

(defn- client-owner-key
  [owner]
  (client-session/owner-key owner))

(defn input-state-snapshot
  []
  {:raw-v-state @raw-v-state
   :raw-n-state @raw-n-state})

(defn reset-input-state-for-test!
  ([]
   (reset-input-state-for-test! {}))
  ([{:keys [raw-v-state-map raw-n-state-map]
     :or {raw-v-state-map {}
          raw-n-state-map {}}}]
   (reset! raw-v-state raw-v-state-map)
   (reset! raw-n-state raw-n-state-map)
   nil))

(defn clear-owner-input-state!
  [owner]
  (let [owner-key (client-owner-key owner)]
    (swap! raw-v-state dissoc owner-key)
    (swap! raw-n-state dissoc owner-key))
  nil)

(defn clear-client-input-session!
  [client-session-id]
  (let [clear-session! (fn [state-atom]
                         (swap! state-atom
                                (fn [states]
                                  (into {}
                                        (remove (fn [[[entry-session-id _player-uuid] _value]]
                                                  (= client-session-id entry-session-id)))
                                        states))))]
    (clear-session! raw-v-state)
    (clear-session! raw-n-state))
  nil)

(defn- owner-mode-switch-state
  [owner]
  (get @raw-v-state (client-owner-key owner) (mode-switch/initial-state)))

(defn- owner-cycle-state
  [owner]
  (get @raw-n-state (client-owner-key owner) {:was-down false}))

(defn- update-owner-mode-switch-state!
  [owner is-down opts]
  (let [state-atom (atom (owner-mode-switch-state owner))]
    (mode-switch/handle-button-state! state-atom is-down opts)
    (swap! raw-v-state assoc (client-owner-key owner) @state-atom)
    nil))

(defn- get-player-uuid []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when-let [player (.player mc)]
      (str (.getUUID player)))))

(defn- current-screen-open? []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (some? (.-screen mc))))

(defn- emit-keyboard-input!
  [input-id player-uuid event]
  (power-runtime/emit-client-input! input-id
                                    {:player-uuid player-uuid}
                                    {:source :keyboard
                                     :event event}))

(defn- poll-v-key-down? []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (let [window (.getWindow (.getWindow mc))]
      (= GLFW/GLFW_PRESS (GLFW/glfwGetKey window GLFW/GLFW_KEY_V)))))

(defn- poll-key-down?
  [key-code]
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (let [window (.getWindow (.getWindow mc))]
      (= GLFW/GLFW_PRESS (GLFW/glfwGetKey window key-code)))))

(defn- slot-key-down?
  [key-idx]
  ;; Keep parity with Forge alternative scheme: Z/X/C/B
  (case (int key-idx)
    0 (boolean (poll-key-down? GLFW/GLFW_KEY_Z))
    1 (boolean (poll-key-down? GLFW/GLFW_KEY_X))
    2 (boolean (poll-key-down? GLFW/GLFW_KEY_C))
    3 (boolean (poll-key-down? GLFW/GLFW_KEY_B))
    false))

(defn- movement-key-down?
  [movement-key]
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (let [opts (.options mc)]
      (case movement-key
        :forward (.isDown (.keyUp opts))
        :back (.isDown (.keyDown opts))
        :left (.isDown (.keyLeft opts))
        :right (.isDown (.keyRight opts))
        false))))

(defn- gui-key-down?
  [gui-key]
  (case gui-key
    :primary (boolean (poll-key-down? GLFW/GLFW_KEY_GRAVE_ACCENT))
    :secondary (boolean (poll-key-down? GLFW/GLFW_KEY_G))
    false))

(defn on-slot-key-down! [player-uuid key-idx]
  (with-client-session #(power-runtime/client-on-slot-key-down! player-uuid key-idx)))

(defn on-slot-key-tick! [player-uuid key-idx]
  (with-client-session #(power-runtime/client-on-slot-key-tick! player-uuid key-idx)))

(defn on-slot-key-up! [player-uuid key-idx]
  (with-client-session #(power-runtime/client-on-slot-key-up! player-uuid key-idx)))

(defn on-slot-key-abort! [player-uuid key-idx]
  (with-client-session #(power-runtime/client-on-slot-key-abort! player-uuid key-idx)))

(defn on-movement-key-down! [player-uuid movement-key]
  (with-client-session #(power-runtime/client-on-movement-key-down! player-uuid movement-key)))

(defn on-movement-key-tick! [player-uuid movement-key]
  (with-client-session #(power-runtime/client-on-movement-key-tick! player-uuid movement-key)))

(defn on-movement-key-up! [player-uuid movement-key]
  (with-client-session #(power-runtime/client-on-movement-key-up! player-uuid movement-key)))

(defn- tick-mode-switch! []
  (let [now (System/nanoTime)
        is-down (boolean (poll-v-key-down?))]
    (when-let [owner (client-session/current-local-player-owner)]
      (update-owner-mode-switch-state!
        owner
        is-down
        {:now-ns now
         :screen-open? (current-screen-open?)
         :on-down #(overlay-renderer/on-mode-switch-key-state! owner true)
         :on-up #(overlay-renderer/on-mode-switch-key-state! owner false)
         :on-short-up
         (fn []
           (let [uuid (:player-uuid owner)
                 cur-activated (power-runtime/runtime-activated? uuid)]
             (overlay-state/set-client-activated! owner (not cur-activated))
             (emit-keyboard-input! toggle-primary-state-input-id uuid :short-press)))}))))

(defn- tick-cycle-selection! []
  (when-let [owner (client-session/current-local-player-owner)]
    (let [is-down (boolean (poll-key-down? GLFW/GLFW_KEY_N))
          owner-key (client-owner-key owner)
          was-down (boolean (:was-down (owner-cycle-state owner)))]
      (when (and (not was-down) is-down)
        (when-let [uuid (get-player-uuid)]
          (emit-keyboard-input! cycle-selection-input-id uuid :press)))
      (swap! raw-n-state assoc owner-key {:was-down is-down}))))

(defn- tick-content-keys! []
  (with-client-session
    #(power-runtime/client-tick-keys!
       (fn [key-id]
         (case (first key-id)
           :slot (slot-key-down? (second key-id))
           :movement (movement-key-down? (second key-id))
           :screen (gui-key-down? (second key-id))
           false))
       get-player-uuid)))

(defn tick-client!
  []
  (session-cleanup/tick-connection-change!
   {:clear-owner-input-state! clear-owner-input-state!})
  (when-not (client-session/current-local-player-owner)
    (when-let [session-id (client-session/client-session-id)]
      (clear-client-input-session! session-id)))
  (tick-mode-switch!)
  (tick-cycle-selection!)
  (tick-content-keys!)
  (particle/tick-particles!)
  (sound/tick-sounds!)
  (with-client-session #(power-runtime/client-tick!)))

(defn init!
  []
  (power-runtime/client-register-push-handlers!)
  (when (compare-and-set! tick-listener-registered? false true)
    (.register ClientTickEvents/END_CLIENT_TICK
               (reify ClientTickEvents$EndTick
                 (onEndTick [_ _client]
                   (tick-client!)))))
  (log/info "Fabric client runtime bridge initialized"))
