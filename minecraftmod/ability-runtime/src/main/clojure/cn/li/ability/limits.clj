(ns cn.li.ability.limits
  "Bounded runtime configuration for the medium-50 server profile.")

(def medium-50
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

(defn validate
  [limits]
  (let [limits (merge medium-50 (or limits {}))]
    (doseq [[key value] limits]
      (when (and (keyword? key) (not (and (integer? value) (pos? value))))
        (throw (ex-info "runtime limit must be a positive integer"
                        {:key key :value value}))))
    limits))

