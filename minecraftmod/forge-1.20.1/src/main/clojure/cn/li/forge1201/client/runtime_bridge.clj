(ns cn.li.forge1201.client.runtime-bridge
  "CLIENT-ONLY Forge adapter for runtime hooks."
  (:require [cn.li.mc1201.client.effects.particle :as particle]
            [cn.li.mc1201.client.effects.sound :as sound]
            [cn.li.mc1201.client.session-cleanup :as session-cleanup]
            [cn.li.mc1201.client.session :as client-session]
            [cn.li.mc1201.client.overlay.state :as overlay-state]
            [cn.li.mcmod.hooks.core :as power-runtime]
             [cn.li.mcmod.runtime.install :as install]
             [cn.li.mcmod.util.log :as log]
             [cn.li.mc1201.client.player-state-core :as player-state]
             [cn.li.mc1201.client.font.msdf-tick :as msdf-tick]
             [cn.li.mcmod.spi.key-scheme-provider :as key-provider])
  (:import [cn.li.mc1201.client.effect ScriptedEffectSpawner]
            [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.event TickEvent$ClientTickEvent TickEvent$Phase]
           [net.minecraftforge.eventbus.api EventPriority]
           [net.minecraft.client Minecraft]))

(defn active-contexts []
  (power-runtime/client-active-contexts))

(defn latest-sync [player-uuid]
  (power-runtime/client-latest-sync player-uuid))

(defn local-player-item-id []
  (player-state/local-player-item-id))

(defn local-player-pos [] (player-state/local-player-pos))
(defn local-player-eye-pos [] (player-state/local-player-eye-pos))
(defn local-player-look-end [distance] (player-state/local-player-look-end distance))

(defn clear-client-activated-overlay! []
  (if-let [owner (client-session/current-local-player-owner)]
    (overlay-state/clear-client-activated! owner)
    (when-let [session-id (client-session/client-session-id)]
      (overlay-state/clear-client-overlay-session! session-id))))

(defn spawn-local-scripted-effect! [effect-id]
  (ScriptedEffectSpawner/spawnLocal effect-id))

(defn remove-local-scripted-effect! [entity-uuid]
  (ScriptedEffectSpawner/removeLocalByUuid entity-uuid))

(defn slot-visual-state [player-uuid key-idx]
  (client-session/with-current-client-session #(power-runtime/client-slot-visual-state player-uuid key-idx)))

(defn on-slot-key-down! [player-uuid key-idx]
  (client-session/with-current-client-session #(power-runtime/client-on-slot-key-down! player-uuid key-idx)))

(defn on-slot-key-tick! [player-uuid key-idx]
  (client-session/with-current-client-session #(power-runtime/client-on-slot-key-tick! player-uuid key-idx)))

(defn on-slot-key-up! [player-uuid key-idx]
  (client-session/with-current-client-session #(power-runtime/client-on-slot-key-up! player-uuid key-idx)))

(defn on-slot-key-abort! [player-uuid key-idx]
  (client-session/with-current-client-session #(power-runtime/client-on-slot-key-abort! player-uuid key-idx)))

(defn on-movement-key-down! [player-uuid movement-key]
  (client-session/with-current-client-session #(power-runtime/client-on-movement-key-down! player-uuid movement-key)))

(defn on-movement-key-tick! [player-uuid movement-key]
  (client-session/with-current-client-session #(power-runtime/client-on-movement-key-tick! player-uuid movement-key)))

(defn on-movement-key-up! [player-uuid movement-key]
  (client-session/with-current-client-session #(power-runtime/client-on-movement-key-up! player-uuid movement-key)))

(defn abort-all! []
  (client-session/with-current-client-session #(power-runtime/client-abort-all!)))

;; ===== Key State Function for keybinds/tick-keys! =====
;; Maps logical key queries to GLFW key codes and queries keyboard state.

(def ^:private slot-glfw-keys [90 88 67 86])   ;; Z, X, C, V → skill slots 0-3
(def ^:private movement-glfw-keys {:forward 87  ;; W
                                   :back 83     ;; S
                                   :left 65     ;; A
                                   :right 68})  ;; D
(def ^:private screen-glfw-keys {:primary 78    ;; N → skill tree
                                 :secondary 77}) ;; M → preset editor

(defn- glfw-key-state-fn
  "key-state-fn callback for keybinds/tick-keys!. Takes [:slot idx], [:movement kw],
   or [:screen kw] and returns boolean key state from GLFW."
  [[kind sub-key]]
  (let [key-code (case kind
                   :slot (nth slot-glfw-keys sub-key nil)
                   :movement (get movement-glfw-keys sub-key)
                   :screen (get screen-glfw-keys sub-key)
                   nil)]
    (when key-code
      (try
        (key-provider/query-key-down? :original key-code)
        (catch Throwable _ false)))))

(defn- get-player-uuid-str
  "Get current player UUID as string for keybinds context."
  []
  (try
    (when-let [^Minecraft mc (Minecraft/getInstance)]
      (when-let [player (.player mc)]
        (str (.getUUID player))))
    (catch Throwable _ nil)))

(defn tick-client! []
  (session-cleanup/tick-connection-change! {})
  (particle/tick-particles!)
  (sound/tick-sounds!)
  (msdf-tick/client-tick!)
  ;; Per-frame key polling for skill slot keys (Z/X/C/V held) + movement keys + GUI keys
  ;; Needs client session ctx: keybinds owner resolution reads client-session-id.
  (client-session/with-current-client-session
    #(power-runtime/client-tick-keys! glfw-key-state-fn get-player-uuid-str))
  (client-session/with-current-client-session #(power-runtime/client-tick!)))

(defn- on-client-tick [^TickEvent$ClientTickEvent evt]
  (when (= TickEvent$Phase/END (.phase evt))
    (tick-client!)))

(defn init! []
  (power-runtime/client-register-push-handlers!)
  (install/process-once! ::tick-listener-registered
    #(.addListener (MinecraftForge/EVENT_BUS)
                   EventPriority/NORMAL false TickEvent$ClientTickEvent
                   (reify java.util.function.Consumer
                     (accept [_ evt] (on-client-tick evt)))))
  (log/info "Client runtime bridge initialized"))