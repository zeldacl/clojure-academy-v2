(ns cn.li.fabric1201.client.runtime-bridge
  "CLIENT-ONLY Fabric adapter for client runtime ticking."
  (:require [cn.li.mc1201.client.input.mode-switch :as mode-switch]
            [cn.li.mc1201.client.effects.particle :as particle]
            [cn.li.mc1201.client.effects.sound :as sound]
            [cn.li.fabric1201.client.overlay-renderer :as overlay-renderer]
            [cn.li.mc1201.client.overlay.state :as overlay-state]
            [cn.li.mcmod.hooks.core :as power-runtime]
            [cn.li.mcmod.util.log :as log])
  (:import [net.fabricmc.fabric.api.client.event.lifecycle.v1 ClientTickEvents ClientTickEvents$EndTick]
           [net.minecraft.client Minecraft]
           [org.lwjgl.glfw GLFW]))

(defonce ^:private tick-listener-registered? (atom false))
(defonce ^:private raw-v-state (atom (mode-switch/initial-state)))
(defonce ^:private raw-n-state (atom {:was-down false}))

(defn- get-player-uuid []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when-let [player (.player mc)]
      (str (.getUUID player)))))

(defn- current-screen-open? []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (some? (.-screen mc))))

(defn- poll-v-key-down? []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (let [window (.getWindow (.getWindow mc))]
      (= GLFW/GLFW_PRESS (GLFW/glfwGetKey window GLFW/GLFW_KEY_V)))))

(defn- poll-key-down?
  [key-code]
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (let [window (.getWindow (.getWindow mc))]
      (= GLFW/GLFW_PRESS (GLFW/glfwGetKey window key-code)))))

(defn- skill-key-down?
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
    :skill-tree (boolean (poll-key-down? GLFW/GLFW_KEY_GRAVE_ACCENT))
    :preset-editor (boolean (poll-key-down? GLFW/GLFW_KEY_G))
    false))

(defn on-slot-key-down! [player-uuid key-idx]
  (power-runtime/client-on-slot-key-down! player-uuid key-idx))

(defn on-slot-key-tick! [player-uuid key-idx]
  (power-runtime/client-on-slot-key-tick! player-uuid key-idx))

(defn on-slot-key-up! [player-uuid key-idx]
  (power-runtime/client-on-slot-key-up! player-uuid key-idx))

(defn on-movement-key-down! [player-uuid movement-key]
  (power-runtime/client-on-movement-key-down! player-uuid movement-key))

(defn on-movement-key-tick! [player-uuid movement-key]
  (power-runtime/client-on-movement-key-tick! player-uuid movement-key))

(defn on-movement-key-up! [player-uuid movement-key]
  (power-runtime/client-on-movement-key-up! player-uuid movement-key))

(defn- tick-mode-switch! []
  (let [now (System/nanoTime)
        is-down (boolean (poll-v-key-down?))]
    (mode-switch/handle-button-state!
      raw-v-state
      is-down
      {:now-ns now
       :screen-open? (current-screen-open?)
       :on-down #(overlay-renderer/on-mode-switch-key-state! true)
       :on-up #(overlay-renderer/on-mode-switch-key-state! false)
       :on-short-up
       (fn []
         (when-let [uuid (get-player-uuid)]
          (let [cur-activated (power-runtime/runtime-activated? uuid)]
             (overlay-state/set-client-activated! (not cur-activated))
             (power-runtime/client-trigger-mode-switch! uuid))))})))

(defn- tick-preset-switch! []
  (let [is-down (boolean (poll-key-down? GLFW/GLFW_KEY_N))
        was-down (boolean (:was-down @raw-n-state))]
    (when (and (not was-down) is-down)
      (when-let [uuid (get-player-uuid)]
        (power-runtime/client-trigger-preset-switch! uuid)))
    (swap! raw-n-state assoc :was-down is-down)))

(defn- tick-ability-keys! []
  (power-runtime/client-tick-keys!
    (fn [key-id]
      (case (first key-id)
        :skill (skill-key-down? (second key-id))
        :movement (movement-key-down? (second key-id))
        :gui (gui-key-down? (second key-id))
        false))
    get-player-uuid))

(defn tick-client!
  []
  (tick-mode-switch!)
  (tick-preset-switch!)
  (tick-ability-keys!)
  (particle/tick-particles!)
  (sound/tick-sounds!)
  (power-runtime/client-tick!))

(defn init!
  []
  (power-runtime/client-register-push-handlers!)
  (when (compare-and-set! tick-listener-registered? false true)
    (.register ClientTickEvents/END_CLIENT_TICK
               (reify ClientTickEvents$EndTick
                 (onEndTick [_ _client]
                   (tick-client!)))))
  (log/info "Fabric client runtime bridge initialized"))
