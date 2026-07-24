(ns cn.li.forge1201.client.runtime-bridge
  "CLIENT-ONLY Forge adapter for runtime hooks."
  (:require [cn.li.mc1201.client.effects.particle :as particle]
            [cn.li.mc1201.client.effects.sound :as sound]
            [cn.li.mc1201.client.session-cleanup :as session-cleanup]
            [cn.li.mc1201.client.session :as client-session]
            [cn.li.mc1201.client.overlay.state :as overlay-state]
            [cn.li.mc1201.glfw-polling-core :as glfw-polling]
            [cn.li.mcmod.hooks.core :as power-runtime]
             [cn.li.mcmod.runtime.install :as install]
             [cn.li.mcmod.util.log :as log]
             [cn.li.mc1201.client.player-state-core :as player-state]
             [cn.li.mc1201.client.font.msdf-tick :as msdf-tick])
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

(defn spawn-scripted-effect-at-player!
  "Spawn a scripted effect anchored to `owner-uuid` (any currently-loaded
  player, not just the local one) — the string UUID of the spawned entity is
  returned so the caller can despawn it later via
  remove-local-scripted-effect!. Used for effects that must appear at a
  skill's caster for every nearby viewer, not just the caster's own screen."
  [effect-id owner-uuid]
  (ScriptedEffectSpawner/spawnAtPlayerWithUuid effect-id owner-uuid))

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
;; slot/movement/screen key maps + glfw-key-state-fn/no-key-down-fn now live
;; in cn.li.mc1201.glfw-polling-core, shared with Fabric's keyboard_init.clj —
;; a key remap only needs to change one place.

(defn- screen-open? []
  (some? (.screen (Minecraft/getInstance))))

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
  ;; While any Screen is open, raw GLFW polling must read all keys as released —
  ;; vanilla KeyMappings are suppressed by the screen, and so must we be.
  (client-session/with-current-client-session
    #(power-runtime/client-tick-keys!
       (if (screen-open?) glfw-polling/no-key-down-fn glfw-polling/glfw-key-state-fn)
       get-player-uuid-str))
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