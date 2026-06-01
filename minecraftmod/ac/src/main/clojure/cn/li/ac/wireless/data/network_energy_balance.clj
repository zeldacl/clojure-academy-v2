(ns cn.li.ac.wireless.data.network-energy-balance
	(:require [cn.li.ac.wireless.config :as network-config]
            [cn.li.ac.wireless.core.capability-resolver :as resolver]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.data.network-membership :as membership]
            [cn.li.ac.wireless.data.network-state :as network-state]
            [cn.li.mcmod.util.log :as log])
	(:import [cn.li.acapi.wireless IWirelessMatrix IWirelessNode]))

(defn- resolve-matrix
  [network]
  (let [world (:world (:world-data network))]
    (resolver/resolve-matrix-cap world (:matrix network))))

(defn- node-entry
  [world node-vb]
  (when-let [node (resolver/resolve-node-cap world node-vb)]
    {:vb node-vb
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
                (membership/remove-node! network node-vb)
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
  (when-let [^IWirelessMatrix matrix (resolve-matrix network)]
    (let [entries (collect-active-nodes! network)
          entries (shuffle (filter #(pos? (:max-energy %)) entries))
          max-sum (reduce + 0.0 (map :max-energy entries))]
      (when (and (seq entries) (pos? max-sum))
        (let [matrix-bandwidth (double (.getMatrixBandwidth matrix))
              buffer-max (double (network-config/buffer-max))
              buffer0 (double (network-state/get-buffer network))
              buffer0 (-> buffer0 (max 0.0) (min buffer-max))]
          (let [sum (+ (reduce + 0.0 (map :energy entries)) buffer0)
                percent (min 1.0 (max 0.0 (/ sum max-sum)))]
          (loop [xs entries
                 transfer-left matrix-bandwidth
                 buffer buffer0]
            (if (or (empty? xs) (not (pos? transfer-left)))
              (network-state/set-buffer! network (-> buffer (max 0.0) (min buffer-max)))
              (let [{:keys [node energy max-energy bandwidth]} (first xs)
                    target (* max-energy percent)
                    delta (- target energy)
                    bandwidth (max 0.0 bandwidth)
                    room-in-buffer (- buffer-max buffer)]
                (cond
                  (and (pos? delta) (pos? buffer))
                  (let [give (min delta bandwidth transfer-left buffer)
                        next-energy (+ energy give)]
                    (.setEnergy ^IWirelessNode node (min next-energy max-energy))
                    (recur (rest xs) (- transfer-left give) (- buffer give)))

                  (and (neg? delta) (pos? room-in-buffer))
                  (let [take (min (- delta) bandwidth transfer-left room-in-buffer)
                        next-energy (- energy take)]
                    (.setEnergy ^IWirelessNode node (max 0.0 next-energy))
                    (recur (rest xs) (- transfer-left take) (+ buffer take)))

                  :else
                  (recur (rest xs) transfer-left buffer)))))))))))