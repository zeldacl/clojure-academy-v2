(ns cn.li.forge1201.runtime.interop
  "Forge thin adapter for runtime interop bridge.
  Delegates all MC logic to mc1201 interop-core."
  (:require [cn.li.mcmod.platform.runtime-interop :as runtime-interop]
            [cn.li.mcmod.platform.ability-interop :as legacy-interop]
            [cn.li.mc1201.runtime.interop-core :as ic]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.server ServerLifecycleHooks]))

(defn- get-server []
  (ServerLifecycleHooks/getCurrentServer))

(defn forge-runtime-interop []
  (reify runtime-interop/IRuntimeInterop
    (get-player-view [_ player-uuid]
      (ic/get-player-view (get-server) player-uuid))
    (get-player-main-hand-item [_ player-uuid]
      (ic/get-player-main-hand-item (get-server) player-uuid))
    (get-block-entity-at [_ world-id x y z]
      (ic/get-block-entity-at (get-server) world-id x y z))))

(defn install-runtime-interop! []
  (let [impl (forge-runtime-interop)]
    (alter-var-root #'runtime-interop/*runtime-interop* (constantly impl))
    ;; Backward compatibility for older AC call sites still reading legacy var.
    (alter-var-root #'legacy-interop/*ability-interop* (constantly impl)))
  (log/info "Forge runtime interop installed"))