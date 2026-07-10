(ns cn.li.forge1201.client.hand-effect-renderer
  "CLIENT-ONLY first-person hand effect renderer for runtime animations." 
  (:require [cn.li.mc1201.client.effects.hand :as hand]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.forge1201.shim ForgeClientHelper]
           [net.minecraft.client Minecraft]
           [net.minecraftforge.client.event RenderHandEvent]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.event TickEvent$ClientTickEvent TickEvent$Phase]
           [net.minecraftforge.eventbus.api EventPriority]))

(def ^:private listener-guard-lock
  (Object.))

(def ^:private ^:dynamic *tick-listener-registered?*
  false)

(def ^:private ^:dynamic *render-listener-registered?*
  false)

(defn- on-client-tick [^TickEvent$ClientTickEvent evt]
  (when (= TickEvent$Phase/END (.phase evt))
    (hand/tick-hand-effects!)
    (when-let [mc (Minecraft/getInstance)]
      (when-let [player (.player mc)]
        (hand/apply-camera-pitch-deltas! player)))))

(defn- on-render-hand [^RenderHandEvent evt]
  (try
    (when-let [{:keys [tx ty tz rot-x rot-y rot-z]} (hand/current-hand-transform)]
      (when (ForgeClientHelper/renderTransformedMainHand
              evt
              (float tx)
              (float ty)
              (float tz)
              (float rot-x)
              (float rot-y)
              (float rot-z))
        (.setCanceled evt true)))
    (catch Exception e
      (log/error "DirectedShock hand render failed" e)
      (log/stacktrace "DirectedShock hand render failed" e))))

(defn init! []
  (when-not (var-get #'*tick-listener-registered?*)
    (locking listener-guard-lock
      (when-not (var-get #'*tick-listener-registered?*)
        (.addListener (MinecraftForge/EVENT_BUS)
                      EventPriority/NORMAL false TickEvent$ClientTickEvent
                      (reify java.util.function.Consumer
                        (accept [_ evt] (on-client-tick evt))))
        (alter-var-root #'*tick-listener-registered?* (constantly true)))))
  (when-not (var-get #'*render-listener-registered?*)
    (locking listener-guard-lock
      (when-not (var-get #'*render-listener-registered?*)
        (.addListener (MinecraftForge/EVENT_BUS)
                      EventPriority/NORMAL false RenderHandEvent
                      (reify java.util.function.Consumer
                        (accept [_ evt] (on-render-hand evt))))
        (alter-var-root #'*render-listener-registered?* (constantly true)))))
  (log/info "Hand effect renderer initialized"))