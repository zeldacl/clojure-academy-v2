(ns cn.li.fabric262.client.level-effect-renderer
  "26.2 compatibility seam; WorldRenderContext callbacks were removed from
   Fabric API and are intentionally disabled until the new extraction hook is
   wired.")

(require '[cn.li.platform.neutral.presentation :as presentation])

(defn init! []
  (presentation/ensure-registered!)
  nil)
