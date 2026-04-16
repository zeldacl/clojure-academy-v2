(ns cn.li.forge1201.client.hand-effect-renderer
  "CLIENT-ONLY first-person hand effect renderer for ability animations." 
  (:require [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.forge1201.shim ForgeClientHelper]
           [net.minecraft.client Minecraft]
           [net.minecraftforge.client.event RenderHandEvent]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.event TickEvent$ClientTickEvent TickEvent$Phase]
           [net.minecraftforge.eventbus.api EventPriority]))

(defonce ^:private tick-listener-registered? (atom false))
(defonce ^:private render-listener-registered? (atom false))

(defn- on-client-tick [^TickEvent$ClientTickEvent evt]
  (when (= TickEvent$Phase/END (.phase evt))
    (hand-effects/tick-hand-effects!)
    (when-let [mc (Minecraft/getInstance)]
      (when-let [player (.player mc)]
        (doseq [delta (hand-effects/consume-camera-pitch-deltas!)]
          (.setXRot player (+ (.getXRot player) (float delta))))))))

(defn- on-render-hand [^RenderHandEvent evt]
  (try
    (when-let [{:keys [tx ty tz rot-x rot-y rot-z]} (hand-effects/current-directed-shock-transform)]
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
      (log/error "DirectedShock hand render failed" e))))

(defn init! []
  (when (compare-and-set! tick-listener-registered? false true)
    (.addListener (MinecraftForge/EVENT_BUS)
                  EventPriority/NORMAL false TickEvent$ClientTickEvent
                  (reify java.util.function.Consumer
                    (accept [_ evt] (on-client-tick evt)))))
  (when (compare-and-set! render-listener-registered? false true)
    (.addListener (MinecraftForge/EVENT_BUS)
                  EventPriority/NORMAL false RenderHandEvent
                  (reify java.util.function.Consumer
                    (accept [_ evt] (on-render-hand evt)))))
  (log/info "Hand effect renderer initialized"))