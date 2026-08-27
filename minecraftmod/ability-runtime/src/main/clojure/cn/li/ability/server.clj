(ns cn.li.ability.server
  "Server-side runtime shell.  The implementation intentionally owns no
   global state; callers create one instance per logical server runtime.")

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
  [{:keys [bundle minecraft-ports server-epoch limits]}]
  (when-not (map? bundle)
    (throw (ex-info "server runtime requires a compiled bundle" {})))
  (when-not (map? minecraft-ports)
    (throw (ex-info "server runtime requires Minecraft ports" {})))
  {:side :server
   :bundle bundle
   :minecraft-ports minecraft-ports
   :server-epoch (long (or server-epoch 0))
   :limits (validate-limits limits)
   ;; WorldShard and PlayerRuntime instances are installed by the bootstrap
   ;; on the server tick thread.  They are intentionally instance-local.
   :world-shards {}})
