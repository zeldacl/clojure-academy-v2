(ns cn.li.fabric1201.runtime.damage-interception
  "Fabric implementation of IDamageInterception protocol.

  Uses ServerLivingEntityEvents/ALLOW_DAMAGE for pre-check cancellation parity.
  Fabric 1.20.1 API does not expose a mutable hurt-amount event equivalent to
  Forge LivingHurtEvent, so damage amount rewriting is not applied here."
  (:require [cn.li.mcmod.platform.damage-interception :as pdi]
            [cn.li.mcmod.platform.power-runtime :as power-runtime]
            [cn.li.mcmod.util.log :as log])
  (:import [net.fabricmc.fabric.api.entity.event.v1 ServerLivingEntityEvents$AllowDamage]
           [net.minecraft.server.level ServerPlayer]))

(defonce ^:private installed? (atom false))

(defn- on-allow-damage
  "Handle ALLOW_DAMAGE - perform side-effect-free precheck and cancel when
  reflection should preempt incoming attack effects."
  [entity damage-source amount]
  (try
    (if (instance? ServerPlayer entity)
      (let [^ServerPlayer player entity
            player-id (str (.getUUID player))
            original-damage (double amount)
            attacker (.getEntity damage-source)
            attacker-id (when attacker (str (.getUUID attacker)))
            cancel? (power-runtime/should-cancel-attack-interception?
                      player-id attacker-id original-damage damage-source)]
        (if cancel?
          false
          true))
      true)
    (catch Exception e
      (log/warn "Fabric damage interception precheck failed:" (ex-message e))
      true)))

(defn fabric-damage-interception []
  (reify pdi/IDamageInterception
    (register-damage-handler! [_ handler-id handler-fn priority]
      (power-runtime/register-damage-handler! handler-id handler-fn priority))
    (unregister-damage-handler! [_ handler-id]
      (power-runtime/unregister-damage-handler! handler-id))
    (get-active-handlers [_]
      (power-runtime/get-active-damage-handlers))))

(defn install-damage-interception! []
  (if-not (compare-and-set! installed? false true)
    (log/info "Fabric damage interception already installed, skipping")
    (do
      ;; Install protocol implementation
      (alter-var-root #'pdi/*damage-interception*
                      (constantly (fabric-damage-interception)))

      ;; Register ALLOW_DAMAGE listener (pre-check cancel path).
      (.register net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents/ALLOW_DAMAGE
                 (reify ServerLivingEntityEvents$AllowDamage
                   (allowDamage [_ entity damage-source amount]
                     (on-allow-damage entity damage-source amount))))

      (log/info "Fabric damage interception installed (ALLOW_DAMAGE precheck path)"))))
