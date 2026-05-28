(ns cn.li.fabric1201.client.overlay-renderer
  "CLIENT-ONLY Fabric overlay event adapter; rendering core lives in mc1201 shared layer."
  (:require [cn.li.mc1201.client.overlay.renderer :as shared-overlay]
            [cn.li.mcmod.util.log :as log])
  (:import [net.fabricmc.fabric.api.client.rendering.v1 HudRenderCallback]))

(def ^:private hud-listener-guard-lock
  (Object.))

(def ^:private ^:dynamic *hud-listener-registered?*
  false)
(defn on-mode-switch-key-state!
  ([is-down]
   (shared-overlay/on-mode-switch-key-state! is-down))
  ([owner is-down]
   (shared-overlay/on-mode-switch-key-state! owner is-down)))

(defn init!
  []
  (when-not (var-get #'*hud-listener-registered?*)
    (locking hud-listener-guard-lock
      (when-not (var-get #'*hud-listener-registered?*)
        (.register HudRenderCallback/EVENT
                   (reify HudRenderCallback
                     (onHudRender [_ graphics _tick-delta]
                       (shared-overlay/render-overlay! graphics))))
        (alter-var-root #'*hud-listener-registered?* (constantly true)))))
  (log/info "Fabric overlay renderer initialized"))
