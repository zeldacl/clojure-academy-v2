(ns cn.li.forge1201.runtime.damage-interception
  "Forge implementation of IDamageInterception protocol.

  Intercepts LivingHurtEvent to allow runtime effects to modify incoming damage."
  (:require [cn.li.mc1201.runtime.damage-interception-core :as core]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.eventbus.api EventPriority]
           [net.minecraftforge.event.entity.living LivingAttackEvent]
           [net.minecraftforge.event.entity.living LivingHurtEvent]))

(defn- on-living-attack
  "Handle LivingAttackEvent - perform side-effect-free precheck and cancel when reflection should preempt knockback."
  [^LivingAttackEvent event]
  (try
    (when-let [{:keys [player-id original-damage allow?]}
               (core/attack-precheck-result (.getEntity event)
                                            (.getSource event)
                                            (.getAmount event))]
      (when-not allow?
        (.setCanceled event true)
        (log/debug "Attack pre-canceled:" player-id "damage:" original-damage)))
    (catch Exception e
      (log/warn "Attack interception precheck failed:" (ex-message e)))))

(defn- on-living-hurt
  "Handle LivingHurtEvent - intercept damage and call registered handlers."
  [^LivingHurtEvent event]
  (try
    (when-let [{:keys [original-damage next-damage changed?]}
               (core/damage-process-result (.getEntity event)
                                           (.getSource event)
                                           (.getAmount event))]
      (when changed?
        (.setAmount event (float next-damage))
        (log/debug "Damage modified:" original-damage "->" next-damage)))
    (catch Exception e
      (log/warn "Damage interception failed:" (ex-message e)))))

(defn install-damage-interception! []
  ;; Install shared protocol implementation
  (core/install-damage-interception!)

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
