(ns cn.li.fabricbase.owner
  "Shared Fabric owner binding for server-side callbacks.

  Fabric targets use the same logical owner contract; only client-session
  resolution remains version-specific."
  (:import [net.minecraft.server.level ServerPlayer]))

(defn server-owner
  [^ServerPlayer player]
  (let [server (.getServer player)]
    {:server-session-id (when server
                          [:server (System/identityHashCode server)])
     :player-uuid (str (.getUUID player))}))
