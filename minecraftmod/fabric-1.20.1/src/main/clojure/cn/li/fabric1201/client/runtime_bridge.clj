(ns cn.li.fabric1201.client.runtime-bridge
  "CLIENT-ONLY Fabric adapter for client runtime ticking."
  (:require [cn.li.mc1201.client.input.mode-switch :as mode-switch]
            [cn.li.mc1201.client.effects.particle :as particle]
            [cn.li.mc1201.client.effects.sound :as sound]
            [cn.li.fabric1201.client.overlay-renderer :as overlay-renderer]
            [cn.li.fabric1201.client.overlay-state :as overlay-state]
            [cn.li.mcmod.platform.power-runtime :as power-runtime]
            [cn.li.mcmod.util.log :as log])
  (:import [net.fabricmc.fabric.api.client.event.lifecycle.v1 ClientTickEvents ClientTickEvents$EndTick]
           [net.minecraft.client Minecraft]
           [org.lwjgl.glfw GLFW]))

(defonce ^:private tick-listener-registered? (atom false))
(defonce ^:private raw-v-state (atom (mode-switch/initial-state)))

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
           (let [cur-activated (boolean (get-in (power-runtime/get-player-state uuid)
                                                [:resource-data :activated]))]
             (overlay-state/set-client-activated! (not cur-activated))
             (power-runtime/client-trigger-mode-switch! uuid))))})))

(defn tick-client!
  []
  (tick-mode-switch!)
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
