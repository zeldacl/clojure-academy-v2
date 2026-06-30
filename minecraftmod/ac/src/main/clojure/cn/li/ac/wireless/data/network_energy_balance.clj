(ns cn.li.ac.wireless.data.network-energy-balance
	(:require [cn.li.ac.wireless.config :as network-config]
            [cn.li.ac.wireless.domain.transfer :as transfer]
            [cn.li.ac.wireless.core.capability-resolver :as resolver]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.data.entity-commit :as entity-commit]
            [cn.li.ac.wireless.service.commands :as commands]
            [cn.li.ac.wireless.data.network-state :as network-state]
            [cn.li.ac.wireless.runtime.effects :as effects]
            [cn.li.mcmod.util.log :as log])
	(:import [cn.li.acapi.wireless IWirelessMatrix IWirelessNode]))

(defn- resolve-matrix
  [network]
  (let [world (:world (:world-data network))]
    (resolver/resolve-matrix-cap world (:matrix network))))

(defn- node-entry
  [world node-vb]
  (when-let [node (resolver/resolve-node-cap world node-vb)]
    {:id node-vb
     :vb node-vb
     :node ^IWirelessNode node
     :energy (double (.getEnergy ^IWirelessNode node))
     :max-energy (double (.getMaxEnergy ^IWirelessNode node))
     :bandwidth (double (.getBandwidth ^IWirelessNode node))}))

(defn- collect-active-nodes!
  "Return active node entries and cleanup destroyed nodes that are in loaded chunks."
  [network]
  (let [world (:world (:world-data network))
        node-vbs (network-state/get-nodes network)]
    (reduce
      (fn [acc node-vb]
        (if (vb/is-chunk-loaded? node-vb world)
          (if-let [entry (node-entry world node-vb)]
            (conj acc entry)
            (do
              (try
                (commands/unlink-node-from-network! network node-vb)
                (catch Exception e
                  (log/warn "Failed to unlink missing node during balance" (vb/vblock-to-string node-vb) (ex-message e))))
              acc))
          acc))
      []
      node-vbs)))

(defn balance-energy!
  "Balance energy across linked wireless nodes using a buffer-based model.
  - Total transfer is limited by matrix bandwidth per balance tick.
  - Per-node adjustment is limited by node bandwidth.
  - Buffer is clamped to [0, buffer-max]."
  [network]
  (let [network (entity-commit/resolve-network (:world-data network) network)]
    (when-let [^IWirelessMatrix matrix (resolve-matrix network)]
    (let [entries (collect-active-nodes! network)
          entries (shuffle (filter #(pos? (:max-energy %)) entries))
          max-sum (reduce + 0.0 (map #(get % :max-energy) entries))]
      (when (and (seq entries) (pos? max-sum))
        (let [matrix-bandwidth (double (.getMatrixBandwidth matrix))
              buffer-max (double (network-config/buffer-max))
              buffer0 (double (network-state/get-buffer network))
              buffer0 (transfer/clamp buffer0 0.0 buffer-max)
              plan (transfer/balance-plan entries matrix-bandwidth buffer0 buffer-max)]
          (effects/apply-node-energy-plan! entries (:energies plan))
          (network-state/set-buffer! network (:buffer plan))))))))