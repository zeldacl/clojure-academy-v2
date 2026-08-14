(ns cn.li.fabric262.client.overlay-renderer
  "26.2 compatibility seam; the removed HudRenderCallback is replaced by the
   vanilla GUI extraction pipeline when the overlay renderer is ported.")

(require '[cn.li.platform.neutral.presentation :as presentation]
         '[cn.li.mc262.presentation.backend :as presentation-backend])

(defn on-mode-switch-key-state! [_ & _] nil)
(defn init! []
  (presentation/register-backend! (presentation-backend/create))
  (presentation/ensure-registered!)
  nil)
