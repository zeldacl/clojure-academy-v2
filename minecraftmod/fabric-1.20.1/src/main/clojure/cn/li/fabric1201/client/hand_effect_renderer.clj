(ns cn.li.fabric1201.client.hand-effect-renderer
  "CLIENT-ONLY Fabric hand-effect adapter.

  Fabric does not expose a Forge-style RenderHandEvent in this project path,
  so this adapter currently applies shared hand-effect ticking and camera pitch
  deltas, while first-person hand transform rendering remains Forge-specific."
  (:require [cn.li.mc1201.client.effects.hand :as hand]
            [cn.li.mcmod.util.log :as log])
  (:import [net.fabricmc.fabric.api.client.event.lifecycle.v1 ClientTickEvents ClientTickEvents$EndTick]
           [net.minecraft.client Minecraft]))

(defonce ^:private tick-listener-registered? (atom false))

(defn- on-client-tick []
  (hand/tick-hand-effects!)
  (when-let [mc (Minecraft/getInstance)]
    (when-let [player (.player mc)]
      (hand/apply-camera-pitch-deltas! player))))

(defn init!
  []
  (when (compare-and-set! tick-listener-registered? false true)
    (.register ClientTickEvents/END_CLIENT_TICK
               (reify ClientTickEvents$EndTick
                 (onEndTick [_ _client]
                   (on-client-tick)))))
  (log/info "Fabric hand effect renderer initialized"))
