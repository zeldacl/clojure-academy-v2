(ns cn.li.ability.client
  "Client-side runtime shell.  Network callbacks enqueue packets; the client
   tick/render seam applies them and produces one Presentation FramePacket.")

(def ^:private default-limits
  {:max-connections 64
   :max-active-sessions-per-player 16
   :max-pending-intents-per-player 128
   :max-scheduled-actions-per-player 256
   :max-persistent-vfx-per-player 128
   :max-vfx-instances-per-world 4096
   :max-visible-vfx-client 2048
   :max-render-commands-per-frame 8192
   :max-tombstones-per-world 8192
   :tombstone-ttl-ticks 600
   :max-vfx-parameter-slots 64
   :max-packet-bytes 32768})

(defn- validate-limits [overrides]
  (let [resolved (merge default-limits (or overrides {}))]
    (doseq [[key value] resolved]
      (when-not (and (keyword? key) (integer? value) (pos? value))
        (throw (ex-info "runtime limit must be a positive integer"
                        {:key key :value value}))))
    resolved))

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
   :limits (validate-limits limits)
   :packet-queue clojure.lang.PersistentQueue/EMPTY})
