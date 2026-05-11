(ns cn.li.fabric1201.runtime.interop
  "Fabric thin adapter for IRuntimeInterop protocol.
  Delegates to mc1201 interop-core using Fabric server-context."
  (:require [cn.li.mcmod.platform.runtime-interop :as runtime-interop]
            [cn.li.mcmod.platform.ability-interop :as legacy-interop]
            [cn.li.mc1201.runtime.interop-core :as ic]
            [cn.li.fabric1201.runtime.server-context :as server-ctx]
            [cn.li.mcmod.util.log :as log]))

(defn fabric-runtime-interop []
  (reify runtime-interop/IRuntimeInterop
    (get-player-view [_ player-uuid]
      (ic/get-player-view (server-ctx/get-server) player-uuid))
    (get-player-main-hand-item [_ player-uuid]
      (ic/get-player-main-hand-item (server-ctx/get-server) player-uuid))
    (get-block-entity-at [_ world-id x y z]
      (ic/get-block-entity-at (server-ctx/get-server) world-id x y z))))

(defn install-runtime-interop! []
  (let [impl (fabric-runtime-interop)]
    (alter-var-root #'runtime-interop/*runtime-interop* (constantly impl))
    (alter-var-root #'legacy-interop/*ability-interop* (constantly impl)))
  (log/info "Fabric runtime interop installed"))
