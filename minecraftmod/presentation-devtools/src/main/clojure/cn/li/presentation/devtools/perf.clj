(ns cn.li.presentation.devtools.perf
  "Allocation-free-at-read performance panel model. Rendering/UI code reports
   samples; the panel computes bounded rolling summaries.")

(defn create
  ([] (create 240))
  ([capacity] {:capacity (int capacity) :samples (atom [])}))

(defn record! [panel sample]
  (let [sample (merge {:cpu-ms 0.0 :alloc-bytes 0 :draw-calls 0
                       :mailbox-drops 0 :dirty #{}} sample)
        capacity (:capacity panel)]
    (swap! (:samples panel)
           (fn [samples]
             (let [next (conj samples sample)]
               (if (> (count next) capacity)
                 (subvec (vec next) (- (count next) capacity))
                 (vec next)))))
    nil))

(defn- percentile [values p]
  (if (seq values)
    (let [sorted (vec (sort values))
          index (min (dec (count sorted))
                     (max 0 (int (Math/ceil (* p (dec (count sorted)))))))]
      (double (nth sorted index)))
    0.0))

(defn summary [panel]
  (let [samples @(:samples panel)]
    {:frames (count samples)
     :cpu-p50-ms (percentile (map :cpu-ms samples) 0.50)
     :cpu-p95-ms (percentile (map :cpu-ms samples) 0.95)
     :alloc-bytes-per-frame (long (if (seq samples)
                                    (/ (double (reduce + 0 (map :alloc-bytes samples)))
                                       (count samples))
                                    0))
     :draw-calls-p95 (percentile (map :draw-calls samples) 0.95)
     :mailbox-drops (long (reduce + 0 (map :mailbox-drops samples)))
     :dirty-causes (frequencies (mapcat :dirty samples))}))

(defn clear! [panel]
  (reset! (:samples panel) [])
  nil)
