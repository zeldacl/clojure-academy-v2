(ns cn.li.ability.continuation
  "Instance-local continuation queue for delayed ability graph work.
   The queue stores only immutable neutral payloads; execution is delegated to
   the composition root so no Minecraft object or skill policy crosses this
   boundary.")

(def ^:const max-tasks-per-owner 256)

(defn create
  [{:keys [execute!]}]
  (when-not (ifn? execute!)
    (throw (ex-info "continuation runtime requires execute!" {})))
  {:execute! execute!
   :owners (atom {})})

(defn schedule!
  [runtime {:keys [owner delay-ticks payload] :as task}]
  (when (nil? owner)
    (throw (ex-info "continuation owner is required" {:task task})))
  (let [delay (max 1 (long (or delay-ticks 1)))]
    (swap! (:owners runtime)
           (fn [owners]
             (let [{:keys [tick tasks]} (get owners owner {:tick 0 :tasks []})]
               (when (>= (count tasks) max-tasks-per-owner)
                 (throw (ex-info "continuation owner budget exceeded"
                                 {:owner owner :limit max-tasks-per-owner})))
               (assoc owners owner {:tick (long tick)
                                    :tasks (conj (vec tasks)
                                                 {:due-tick (+ (long tick) delay)
                                                  :payload payload})}))))
    {:status :scheduled :owner owner :delay-ticks delay}))

(defn pending
  [runtime owner]
  (let [{:keys [tick tasks]} (get @(:owners runtime) owner)]
    (mapv #(assoc % :ticks-left (max 0 (- (:due-tick %) (long (or tick 0)))))
          (or tasks []))))

(defn cancel-owner!
  [runtime owner]
  (swap! (:owners runtime) dissoc owner)
  nil)

(defn cancel-all!
  [runtime]
  (reset! (:owners runtime) {})
  nil)

(defn tick-owner!
  [runtime owner]
  (let [{:keys [tick tasks]} (get @(:owners runtime) owner)]
    (when (some? tick)
      (let [now (inc (long tick))
            [due pending] (reduce (fn [[due pending] task]
                                   (if (<= (long (:due-tick task)) now)
                                     [(conj due task) pending]
                                     [due (conj pending task)]))
                                 [[] []] tasks)]
        (if (seq pending)
          (swap! (:owners runtime) assoc owner {:tick now :tasks pending})
          (swap! (:owners runtime) dissoc owner))
        (mapv (fn [{:keys [payload]}]
                ((:execute! runtime) owner payload))
              due)))))
