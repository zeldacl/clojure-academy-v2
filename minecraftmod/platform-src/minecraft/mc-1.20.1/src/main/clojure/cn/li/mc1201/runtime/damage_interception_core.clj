(ns cn.li.mc1201.runtime.damage-interception-core
  "Shared Minecraft-side damage interception helpers (no loader API imports)."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.framework.platform :as platform]
            [cn.li.mcmod.hooks.core :as damage-hooks])
  (:import [net.minecraft.server.level ServerPlayer]
           [net.minecraft.world.damagesource DamageSource]
           [net.minecraft.world.entity Entity]))

(defn make-damage-interception
  []
  {:register-damage-handler! (fn [handler-id handler-fn priority]
                               (damage-hooks/register-damage-handler! handler-id handler-fn priority))
   :unregister-damage-handler! (fn [handler-id]
                                 (damage-hooks/unregister-damage-handler! handler-id))
   :get-active-handlers (fn []
                          (damage-hooks/get-active-damage-handlers))})

(defn install-damage-interception!
  []
  (when-let [fw-atom (fw/fw-atom)]
    (platform/install-adapter! fw-atom :damage-interception (make-damage-interception)))
  nil)

(defn should-allow-attack?
  [player-id attacker-id original-damage damage-source]
  (not (damage-hooks/should-cancel-attack-interception?
         player-id attacker-id original-damage damage-source)))

(defn- attacker-id
  [^DamageSource damage-source]
  (when-let [^Entity attacker (.getEntity damage-source)]
    (str (.getUUID attacker))))

(defn- with-damaged-player-owner
  "Bind the damaged player's server owner for the duration of f.

  Hurt events fire on the server thread OUTSIDE any player action context —
  content damage handlers (vec-reflection's attack precheck, etc.) resolve
  the victim's player-state via :player-state-owner/:client-session-id, so
  without this binding they throw 'requires bound session-id' and the
  handler silently degrades. The server session id matches the one
  lifecycle-core's server-owner builds ([:server identityHashCode])."
  [^ServerPlayer player f]
  (damage-hooks/with-client-ctx-fn
    {:player-owner {:server-session-id [:server (System/identityHashCode (.getServer player))]
                    :player-uuid (str (.getUUID player))}}
    f))

(defn attack-precheck-result
  "Return shared attack precheck result for player damage, or nil when the
  damaged entity is not a server player. Platform event layers decide how to
  apply the result to their native event/callback object."
  [entity damage-source amount]
  (when (instance? ServerPlayer entity)
    (let [^ServerPlayer player entity
          player-id (str (.getUUID player))
          original-damage (double amount)
          attacker-id (attacker-id damage-source)
          allow? (with-damaged-player-owner
                   player
                   #(should-allow-attack?
                      player-id attacker-id original-damage damage-source))]
      (when-not allow?
        (with-damaged-player-owner
          player
          #(damage-hooks/run-attack-precheck-side-effects!
             player-id attacker-id original-damage damage-source)))
      {:player-id player-id
       :attacker-id attacker-id
       :original-damage original-damage
       :allow? allow?})))

(defn process-damage
  [player-id attacker-id original-damage damage-source]
  (damage-hooks/process-damage-interception
    player-id attacker-id original-damage damage-source))

(defn damage-process-result
  "Return shared mutable-damage result for player damage, or nil when the
  damaged entity is not a server player. Forge applies the returned damage to
  LivingHurtEvent; Fabric 1.20.1 has no equivalent mutable amount callback."
  [entity damage-source amount]
  (when (instance? ServerPlayer entity)
    (let [^ServerPlayer player entity
          player-id (str (.getUUID player))
          original-damage (double amount)
          attacker-id (attacker-id damage-source)
          next-damage (with-damaged-player-owner
                        player
                        #(process-damage
                           player-id attacker-id original-damage damage-source))]
      {:player-id player-id
       :attacker-id attacker-id
       :original-damage original-damage
       :next-damage next-damage
       :changed? (not= next-damage original-damage)})))
