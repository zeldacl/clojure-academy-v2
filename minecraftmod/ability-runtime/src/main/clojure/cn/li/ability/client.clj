 (ns cn.li.ability.client
  "Client-side runtime shell. Network callbacks enqueue packets; the client
   tick/render seam applies them and produces one Presentation FramePacket."
  (:require [cn.li.ability.limits :as limits]))

(defn create-runtime
  [{:keys [bundle network-ports render-ports world-epoch limits]}]
  (when-not (map? bundle)
    (throw (ex-info "client runtime requires a compiled bundle" {})))
  (when-not (map? network-ports)
    (throw (ex-info "client runtime requires network ports" {})))
  (when-not (map? render-ports)
    (throw (ex-info "client runtime requires render ports" {})))
  {:side :client
   :bundle bundle
   :network-ports network-ports
   :render-ports render-ports
   :world-epoch (long (or world-epoch 0))
   :limits (limits/validate limits)
   :packet-queue clojure.lang.PersistentQueue/EMPTY})
