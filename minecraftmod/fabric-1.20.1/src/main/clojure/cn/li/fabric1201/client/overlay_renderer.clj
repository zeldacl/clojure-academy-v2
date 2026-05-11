(ns cn.li.fabric1201.client.overlay-renderer
  "CLIENT-ONLY Fabric overlay event adapter; rendering core lives in mc1201 shared layer."
  (:require [cn.li.mc1201.client.overlay.renderer :as shared-overlay]
            [cn.li.mcmod.util.log :as log])
  (:import [net.fabricmc.fabric.api.client.rendering.v1 HudRenderCallback]))

(defonce ^:private hud-listener-registered? (atom false))
(defn on-mode-switch-key-state! [is-down]
  (shared-overlay/on-mode-switch-key-state! is-down))

(defn init!
  []
  (when (compare-and-set! hud-listener-registered? false true)
    (.register HudRenderCallback/EVENT
               (reify HudRenderCallback
                 (onHudRender [_ graphics _tick-delta]
                   (shared-overlay/render-overlay! graphics)))))
  (log/info "Fabric overlay renderer initialized"))
