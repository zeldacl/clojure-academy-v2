(ns cn.li.presentation.core.frame
  (:import [cn.li.presentation.core FramePacket RenderPass RenderStage]))

(def dirty
  {:structure 1 :measure 2 :layout 4 :paint 8 :transform 16 :semantics 32})

(def stages (vec (RenderStage/values)))

(defn mailbox []
  {:latest (atom nil)
   :previous (atom nil)
   :published (atom 0)
   :consumed (atom 0)})
(defn publish! [mailbox frame]
  (when-not frame (throw (NullPointerException. "frame")))
  (let [old @(:latest mailbox)]
    (reset! (:latest mailbox) frame)
    (when old (reset! (:previous mailbox) old)))
  (swap! (:published mailbox) inc)
  mailbox)
(defn poll-latest! [mailbox]
  (let [frame @(:latest mailbox)]
    (reset! (:latest mailbox) nil)
    (reset! (:previous mailbox) nil)
    (when frame (swap! (:consumed mailbox) inc))
    frame))

(defn frame-graph
  ([] (into {}
            (map-indexed (fn [idx stage]
                           [stage (if-let [next-stage (nth (vec (rest stages)) idx nil)]
                                    [next-stage]
                                    [])])
                         stages)))
  ([edges] edges))

(defn validate-graph!
  "Validate a stage graph before it is used by extraction or tooling."
  [edges]
  (let [known (set stages)
        pairs (for [[source targets] edges target (or targets [])] [source target])]
    (doseq [[source target] pairs]
      (when-not (contains? known source)
        (throw (ex-info "unknown frame graph source" {:stage source})))
      (when-not (contains? known target)
        (throw (ex-info "unknown frame graph target" {:stage target})))
      (when (= source target)
        (throw (ex-info "frame graph self-cycle" {:stage source}))))
    edges))

(defn order [edges]
  (validate-graph! edges)
  (let [nodes (set stages)
        incoming (reduce (fn [m [_ targets]]
                           (reduce #(update %1 %2 (fnil inc 0)) m targets))
                         (zipmap nodes (repeat 0)) edges)
        ready (into clojure.lang.PersistentQueue/EMPTY
                    (filter #(zero? (get incoming %)) stages))]
    (loop [queue ready in incoming result []]
      (if (empty? queue)
        (if (= (count result) (count nodes)) (vec result)
            (throw (IllegalStateException. "frame graph contains a cycle")))
        (let [stage (peek queue)
              queue (pop queue)
              [in queue] (reduce (fn [[in q] target]
                                  (let [next (dec (get in target))
                                        in (assoc in target next)]
                                    [in (if (zero? next) (conj q target) q)]))
                                [in queue] (get edges stage []))]
          (recur queue in (conj result stage)))))))

(defn packet [frame-id passes]
  (FramePacket. frame-id (mapv #(RenderPass. (first %) (vec (second %))) passes)))
