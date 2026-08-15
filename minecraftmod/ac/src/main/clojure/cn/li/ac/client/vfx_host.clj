(ns cn.li.ac.client.vfx-host
  "Direct AC composition seam for VFX Core.

   This namespace exposes the opaque VFX Frame ABI without coupling it to
   Presentation.  Installation is explicit and idempotent at client startup."
  (:require [cn.li.ac.client.effect-controller :as vfx]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(defn install! []
  (client-bridge/merge-client-bridge! {:vfx-host-api vfx/vfx-host-api})
  (vfx/vfx-host-api))

(defn reload-resources! [generation]
  (vfx/reload-resources! generation))
