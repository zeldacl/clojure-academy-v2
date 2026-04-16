(ns cn.li.forge1201.runtime.damage-interception
  "Forge implementation of IDamageInterception protocol.

  Intercepts LivingHurtEvent to allow skills to modify incoming damage."
  (:require [cn.li.mcmod.platform.damage-interception :as pdi]
            [cn.li.mcmod.platform.ability-lifecycle :as ability-runtime]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.eventbus.api EventPriority]
           [net.minecraftforge.event.entity.living LivingAttackEvent]
           [net.minecraftforge.event.entity.living LivingHurtEvent]
           [net.minecraft.server.level ServerPlayer]
           [net.minecraft.world.damagesource DamageSource]))

(defn- on-living-attack
  "Handle LivingAttackEvent - perform side-effect-free precheck and cancel when reflection should preempt knockback."
  [^LivingAttackEvent event]
  (try
    (let [entity (.getEntity event)]
      (when (instance? ServerPlayer entity)
        (let [^ServerPlayer player entity
              player-id (str (.getUUID player))
              original-damage (double (.getAmount event))
              damage-source (.getSource event)
              attacker (.getEntity damage-source)
              attacker-id (when attacker (str (.getUUID attacker)))
              cancel? (ability-runtime/should-cancel-attack-interception?
                        player-id attacker-id original-damage damage-source)]
          (when cancel?
            (.setCanceled event true)
            (log/debug "Attack pre-canceled:" player-id "damage:" original-damage)))))
    (catch Exception e
      (log/warn "Attack interception precheck failed:" (ex-message e)))))

(defn- on-living-hurt
  "Handle LivingHurtEvent - intercept damage and call registered handlers."
  [^LivingHurtEvent event]
  (try
    (let [entity (.getEntity event)]
      (when (instance? ServerPlayer entity)
        (let [^ServerPlayer player entity
              player-id (str (.getUUID player))
              original-damage (double (.getAmount event))
              damage-source (.getSource event)
              attacker (.getEntity damage-source)
              attacker-id (when attacker (str (.getUUID attacker)))
              next-damage (ability-runtime/process-damage-interception
                            player-id attacker-id original-damage damage-source)]
          (when (not= next-damage original-damage)
            (.setAmount event (float next-damage))
            (log/debug "Damage modified:" original-damage "->" next-damage)))))
    (catch Exception e
      (log/warn "Damage interception failed:" (ex-message e)))))

(defn forge-damage-interception []
  (reify pdi/IDamageInterception
    (register-damage-handler! [_ handler-id handler-fn priority]
      (ability-runtime/register-damage-handler! handler-id handler-fn priority))
    (unregister-damage-handler! [_ handler-id]
      (ability-runtime/unregister-damage-handler! handler-id))
    (get-active-handlers [_]
      (ability-runtime/get-active-damage-handlers))))

(defn install-damage-interception! []
  ;; Install protocol implementation
  (alter-var-root #'pdi/*damage-interception*
                  (constantly (forge-damage-interception)))

  ;; Register event listener
  (.addListener (MinecraftForge/EVENT_BUS)
                EventPriority/HIGH  ; High priority to intercept early
                false
                LivingAttackEvent
                (reify java.util.function.Consumer
                  (accept [_ evt] (on-living-attack evt))))

  (.addListener (MinecraftForge/EVENT_BUS)
                EventPriority/HIGH  ; High priority to intercept early
                false
                LivingHurtEvent
                (reify java.util.function.Consumer
                  (accept [_ evt] (on-living-hurt evt))))

  (log/info "Forge damage interception installed"))
