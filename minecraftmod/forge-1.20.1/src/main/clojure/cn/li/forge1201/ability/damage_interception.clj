(ns cn.li.forge1201.ability.damage-interception
  "Forge implementation of IDamageInterception protocol.

  Intercepts LivingHurtEvent to allow skills to modify incoming damage."
  (:require [cn.li.mcmod.platform.damage-interception :as pdi]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.eventbus.api EventPriority]
           [net.minecraftforge.event.entity.living LivingHurtEvent]
           [net.minecraft.server.level ServerPlayer]
           [net.minecraft.world.damagesource DamageSource]))

(set! *warn-on-reflection* true)

(def ^:private damage-handlers
  "Atom holding registered damage handlers.
  Structure: {handler-id {:fn handler-fn :priority int}}"
  (atom {}))

(defn- get-sorted-handlers
  "Get handlers sorted by priority (lower = earlier)."
  []
  (->> @damage-handlers
       (sort-by (fn [[_id data]] (:priority data)))
       (map (fn [[id data]] [id (:fn data)]))))

(defn- on-living-hurt
  "Handle LivingHurtEvent - intercept damage and call registered handlers."
  [^LivingHurtEvent event]
  (try
    (let [entity (.getEntity event)]
      (when (instance? ServerPlayer entity)
        (let [^ServerPlayer player entity
              player-id (str (.getUUID player))
              original-damage (.getAmount event)
              damage-source (.getSource event)
              attacker (.getEntity damage-source)
              attacker-id (when attacker (str (.getUUID attacker)))
              handlers (get-sorted-handlers)]

          ;; Call each handler in priority order
          (loop [remaining-handlers handlers
                 current-damage original-damage]
            (if (empty? remaining-handlers)
              ;; 所有 handler 处理完毕
              (when (not= current-damage original-damage)
                (.setAmount event (float current-damage))
                (log/debug "Damage modified:" original-damage "->" current-damage))
          
              ;; 处理下一个 handler
              (let [[handler-id handler-fn] (first remaining-handlers)
                    ;; 1. 将 try 缩小到仅包裹不稳定的函数调用
                    next-damage (try
                                  (let [result (handler-fn player-id attacker-id current-damage damage-source)]
                                    (if (vector? result)
                                      (let [[new-damage _metadata] result]
                                        (if (number? new-damage)
                                          new-damage
                                          (do (log/warn "Handler" handler-id "returned invalid damage:" new-damage)
                                              current-damage)))
                                      (do (log/warn "Handler" handler-id "returned invalid result:" result)
                                          current-damage)))
                                  (catch Exception e
                                    (log/warn "Handler" handler-id "failed:" (ex-message e))
                                    current-damage))]
                ;; 2. recur 现在位于 try 块之外，符合编译器要求
                (recur (rest remaining-handlers) next-damage))))

                    )))
    (catch Exception e
      (log/warn "Damage interception failed:" (ex-message e)))))

(defn- register-damage-handler-impl! [handler-id handler-fn priority]
  (try
    (swap! damage-handlers assoc handler-id {:fn handler-fn :priority priority})
    (log/debug "Registered damage handler:" handler-id "priority:" priority)
    true
    (catch Exception e
      (log/warn "Failed to register damage handler:" (ex-message e))
      false)))

(defn- unregister-damage-handler-impl! [handler-id]
  (try
    (swap! damage-handlers dissoc handler-id)
    (log/debug "Unregistered damage handler:" handler-id)
    true
    (catch Exception e
      (log/warn "Failed to unregister damage handler:" (ex-message e))
      false)))

(defn- get-active-handlers-impl []
  (keys @damage-handlers))

(defn forge-damage-interception []
  (reify pdi/IDamageInterception
    (register-damage-handler! [_ handler-id handler-fn priority]
      (register-damage-handler-impl! handler-id handler-fn priority))
    (unregister-damage-handler! [_ handler-id]
      (unregister-damage-handler-impl! handler-id))
    (get-active-handlers [_]
      (get-active-handlers-impl))))

(defn install-damage-interception! []
  ;; Install protocol implementation
  (alter-var-root #'pdi/*damage-interception*
                  (constantly (forge-damage-interception)))

  ;; Register event listener
  (.addListener (MinecraftForge/EVENT_BUS)
                EventPriority/HIGH  ; High priority to intercept early
                false
                LivingHurtEvent
                (reify java.util.function.Consumer
                  (accept [_ evt] (on-living-hurt evt))))

  (log/info "Forge damage interception installed"))
