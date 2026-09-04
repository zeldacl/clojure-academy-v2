(ns cn.li.ac.client.vfx-host
  "Direct AC composition seam for VFX Core.

   This namespace exposes the opaque VFX Frame ABI without coupling it to
   Presentation.  Installation is explicit and idempotent at client startup."
  (:require [cn.li.ability.client-vfx-v2 :as vfx]))

(defn install! []
  ;; AC is a standalone neutral module, so the platform-neutral ABI is linked
  ;; at this one bootstrap boundary. The returned host function is cached by
  ;; cn.li.platform.neutral.vfx; no namespace or bridge lookup occurs in the
  ;; subsequent tick/render paths.
  ;;
  ;; VFX cutover: publishes the new engine's host API (cn.li.ability.
  ;; client-vfx-v2) -- the old engine's own vfx-host-api is no longer
  ;; installed from here.
  (let [api (vfx/vfx-host-api)]
    ((requiring-resolve
       'cn.li.platform.neutral.vfx/install-host!)
     api)
    api))

(defn reload-resources! [generation]
  (vfx/reload-resources! generation))
