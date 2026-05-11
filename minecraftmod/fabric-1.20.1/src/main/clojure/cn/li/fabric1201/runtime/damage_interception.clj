(ns cn.li.fabric1201.runtime.damage-interception
  "Fabric implementation of IDamageInterception protocol.

  Uses ServerLivingEntityEvents/ALLOW_DAMAGE for pre-check cancellation parity.
  Fabric 1.20.1 API does not expose a mutable hurt-amount event equivalent to
  Forge LivingHurtEvent, so damage amount rewriting is not applied here."
  (:require [cn.li.mc1201.runtime.damage-interception-core :as core]
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
            allow? (core/should-allow-attack?
                     player-id attacker-id original-damage damage-source)]
        (boolean allow?))
      true)
    (catch Exception e
      (log/warn "Fabric damage interception precheck failed:" (ex-message e))
      true)))

(defn install-damage-interception! []
  (if-not (compare-and-set! installed? false true)
    (log/info "Fabric damage interception already installed, skipping")
    (do
      ;; Install shared protocol implementation
      (core/install-damage-interception!)

      ;; Register ALLOW_DAMAGE listener (pre-check cancel path).
      (.register net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents/ALLOW_DAMAGE
                 (reify ServerLivingEntityEvents$AllowDamage
                   (allowDamage [_ entity damage-source amount]
                     (on-allow-damage entity damage-source amount))))

      (log/info "Fabric damage interception installed (ALLOW_DAMAGE precheck path)"))))
