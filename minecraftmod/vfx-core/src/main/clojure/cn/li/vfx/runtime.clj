(ns cn.li.vfx.runtime
  "Pure Clojure VFX lifecycle, update and frame sampling runtime."
  (:require [cn.li.mcmod.runtime.vfx-contract :as contract]))

(defn- canonical-value
  "Canonicalize only values admitted by the neutral ABI for stable replay."
  [value]
  (cond
    (instance? java.nio.ByteBuffer value)
    (let [buffer ^java.nio.ByteBuffer (.duplicate ^java.nio.ByteBuffer value)
          bytes (byte-array (.remaining buffer))]
      (.get buffer bytes)
      (vec (map #(bit-and (int %) 0xff) bytes)))
    (map? value)
    (into (sorted-map)
          (map (fn [[key nested]] [key (canonical-value nested)]))
          value)
    (vector? value) (mapv canonical-value value)
    (sequential? value) (mapv canonical-value value)
    (set? value) (vec (sort-by pr-str (map canonical-value value)))
    :else value))

(defn create-runtime
  ([] (create-runtime {}))
  ([{:keys [resource-generation max-instances max-batches max-signals max-tombstones]}]
   {:next-id (atom 0) :registry (atom {}) :registry-frozen? (atom false)
    :instances (atom {}) :owner-index (atom {}) :world-index (atom {})
    :instance-index (atom {}) :event-seq (atom {}) :tombstones (atom {})
    :signals (atom []) :max-signals (long (or max-signals 32768))
    :max-tombstones (long (or max-tombstones 4096))
    :last-tick-id (atom nil)
    :last-frame-id (atom nil) :last-frame (atom nil) :latest-frame (atom nil)
    :resource-generation (atom (long (or resource-generation 0)))
    :max-instances (long (or max-instances 8192))
    :max-batches (long (or max-batches 16384))
    :faults (atom [])}))

(defn- index-add! [index key id]
  (when (some? key)
    (swap! index update key (fnil conj #{}) id)))

(defn- index-remove! [index key id]
  (when (some? key)
    (swap! index update key (fn [ids]
                             (let [next (disj (or ids #{}) id)]
                               (when (seq next) next))))))

(defn register-effect! [runtime {:keys [id init update sample bounds priority] :as descriptor}]
  (when @(:registry-frozen? runtime)
    (throw (ex-info "VFX registry is frozen" {:id id})))
  (when-not (keyword? id)
    (throw (ex-info "VFX effect id must be a keyword" {:id id})))
  (when (contains? @(:registry runtime) id)
    (throw (ex-info "duplicate VFX effect id" {:id id})))
  (doseq [[k f] [[:init init] [:update update] [:sample sample] [:bounds bounds]]]
    (when-not (ifn? f)
      (throw (ex-info "VFX effect callback is required" {:id id :callback k}))))
  (swap! (:registry runtime) assoc id (assoc descriptor :priority (or priority :normal)))
  id)

(defn freeze-registry! [runtime]
  (reset! (:registry-frozen? runtime) true)
  (set (keys @(:registry runtime))))

(defn registered-effects [runtime]
  (set (keys @(:registry runtime))))

(defn- descriptor [runtime effect-id]
  (or (get @(:registry runtime) effect-id)
      (throw (ex-info "unknown VFX effect" {:effect-id effect-id}))))

(defn spawn! [runtime effect-id {:keys [owner world-id seed params anchor instance-key event-seq] :as context}]
  (when (< (count @(:instances runtime)) (:max-instances runtime))
    (let [effect (descriptor runtime effect-id) id (swap! (:next-id runtime) inc)
          seed (long (or seed id))
          instance {:id id :effect-id effect-id :owner owner :world-id world-id
                    :instance-key instance-key :event-seq (long (or event-seq -1))
                    :seed seed :anchor anchor :params (or params {}) :age-seconds 0.0
                    :prev-state nil :state ((:init effect) (assoc context :id id :seed seed))
                    :alive? true :priority (:priority effect)}]
      (index-add! (:owner-index runtime) owner id)
      (index-add! (:world-index runtime) world-id id)
      (when instance-key (swap! (:instance-index runtime) assoc instance-key id))
      (when instance-key (swap! (:event-seq runtime) assoc instance-key (long (or event-seq -1))))
      (swap! (:instances runtime) assoc id instance)
      id)))

(defn signal! [runtime target event payload]
  (swap! (:signals runtime)
         (fn [pending]
           (if (< (count pending) (:max-signals runtime))
             (conj pending {:target target :event event :payload payload})
             pending)))
  nil)

(defn instance-for-key [runtime instance-key]
  (get @(:instance-index runtime) instance-key))

(defn- remember-tombstone! [runtime instance-key event-seq]
  (when instance-key
    (swap! (:tombstones runtime)
           (fn [tombstones]
             (let [next (assoc tombstones instance-key (long event-seq))]
               (if (> (count next) (:max-tombstones runtime))
                 (dissoc next (first (keys next)))
                 next))))))

(declare destroy! clear-owner!)

(defn dispatch-signal!
  "Apply a stable-key VFX signal exactly once.

   The network/content layer owns the envelope; this function owns only the
   client instance index and sequence/tombstone rules."
  [runtime raw-signal]
  (let [{:keys [op effect-id instance-key owner world-id event-seq event params seed]
         :as signal} (contract/signal raw-signal)
        event-seq (long (or event-seq -1))
        current (long (or (get @(:event-seq runtime) instance-key) -1))
        tombstone (long (or (get @(:tombstones runtime) instance-key) -1))]
    (case op
      :spawn
      (cond
        (<= event-seq tombstone) nil
        (nil? (instance-for-key runtime instance-key))
        (do (when (some? event)
              (when-let [instance-id (spawn! runtime effect-id
                                             {:owner owner :world-id world-id :seed seed
                                              :params params :instance-key instance-key
                                              :event-seq event-seq})]
                (signal! runtime {:instance instance-id} event params)))
            (when-not (some? event)
              (spawn! runtime effect-id {:owner owner :world-id world-id :seed seed
                                         :params params :instance-key instance-key
                                         :event-seq event-seq}))
            nil)
        (> event-seq current)
        (do (swap! (:event-seq runtime) assoc instance-key event-seq)
            (signal! runtime {:instance (instance-for-key runtime instance-key)} event params))
        :else nil)

      :signal
      (when-let [instance-id (instance-for-key runtime instance-key)]
        (when (> event-seq current)
          (swap! (:event-seq runtime) assoc instance-key event-seq)
          (signal! runtime {:instance instance-id} event params)))

      :destroy
      (do (when-let [instance-id (instance-for-key runtime instance-key)]
            (destroy! runtime instance-id))
          (remember-tombstone! runtime instance-key event-seq)
          nil)

      :clear-owner
      (do (clear-owner! runtime owner)
          nil))))

(defn destroy! [runtime instance-id]
  (when-let [instance (get @(:instances runtime) instance-id)]
    (swap! (:instances runtime) dissoc instance-id)
    (index-remove! (:owner-index runtime) (:owner instance) instance-id)
    (index-remove! (:world-index runtime) (:world-id instance) instance-id)
    (when-let [instance-key (:instance-key instance)]
      (swap! (:instance-index runtime) dissoc instance-key)
      (swap! (:event-seq runtime) dissoc instance-key)))
  nil)

(defn instance-state
  "Read one runtime instance's authoritative state.

   Composition roots use this to expose read-only snapshots without owning a
   second state table."
  [runtime instance-id]
  (get-in @(:instances runtime) [instance-id :state]))

(defn update-instance-state!
  "Apply a pure state transition to one instance on the client thread."
  [runtime instance-id f & args]
  (when (contains? @(:instances runtime) instance-id)
    (swap! (:instances runtime)
           update-in [instance-id :state]
           (fn [state] (apply f state args))))
  (instance-state runtime instance-id))

(defn instance-for-effect
  "Return the first instance id registered for an effect descriptor."
  [runtime effect-id]
  (some (fn [[instance-id instance]]
          (when (= effect-id (:effect-id instance)) instance-id))
        @(:instances runtime)))

(defn ensure-instance!
  "Create the singleton state instance used by aggregate content effects."
  [runtime effect-id context]
  (or (instance-for-effect runtime effect-id)
      (spawn! runtime effect-id context)))

(defn- remove-indexed! [runtime index key]
  (doseq [id (get @index key #{})] (destroy! runtime id))
  nil)

(defn clear-owner! [runtime owner] (remove-indexed! runtime (:owner-index runtime) owner))
(defn clear-world! [runtime world-id] (remove-indexed! runtime (:world-index runtime) world-id))

(defn- target-matches? [instance target]
  (or (= (:instance target) (:id instance))
      (and (:owner target) (= (:owner target) (:owner instance)))))

(defn- consume-signals! [runtime]
  (let [signals (atom nil)]
    (swap! (:signals runtime)
           (fn [pending]
             (reset! signals (vec pending))
             []))
    (reduce (fn [events signal]
              (reduce (fn [result [id instance]]
                        (if (target-matches? instance (:target signal))
                          (update result id (fnil conj []) (select-keys signal [:event :payload]))
                          result)) events @(:instances runtime)))
            {} @signals)))

(defn- fault! [runtime instance throwable phase]
  (swap! (:faults runtime) conj {:instance-id (:id instance) :effect-id (:effect-id instance)
                                  :phase phase :error throwable}) nil)

(defn- tick-instance [runtime context events [id instance]]
  (let [effect (descriptor runtime (:effect-id instance))]
    (try
      (let [state ((:update effect) (:state instance)
                   (assoc context :instance instance :events (get events id [])))]
        (when (some? state)
          [id (assoc instance :prev-state (:state instance)
                     :state state
                     :age-seconds (+ (:age-seconds instance)
                                     (double (:delta-seconds context))))]))
      (catch Throwable throwable
        (fault! runtime instance throwable :update)
        nil))))

(defn tick! [runtime context]
  (let [{:keys [tick-id delta-seconds] :as context} (contract/tick-context context)]
    (when-not (= tick-id @(:last-tick-id runtime))
      (reset! (:last-tick-id runtime) tick-id)
      (let [events (consume-signals! runtime)]
        (let [removed (atom [])]
          (swap! (:instances runtime)
                 (fn [instances]
                   (reduce-kv (fn [result id instance]
                                (if-let [updated (tick-instance runtime context events [id instance])]
                                  (assoc result (first updated) (second updated))
                                  (do (swap! removed conj instance) result)))
                              {} instances)))
          (doseq [instance @removed]
            (index-remove! (:owner-index runtime) (:owner instance) (:id instance))
            (index-remove! (:world-index runtime) (:world-id instance) (:id instance))
            ;; A descriptor returning nil is a normal lifetime transition.
            ;; Remove every secondary index as well, otherwise the stable key
            ;; remains permanently occupied and a later spawn is discarded.
            (when-let [instance-key (:instance-key instance)]
              (swap! (:instance-index runtime) dissoc instance-key)
              (swap! (:event-seq runtime) dissoc instance-key)))))
    nil)))

(defn- make-sink [buckets batch-count max-batches]
  {:emit! (fn [{:keys [stage] :as value}]
            (when (< @batch-count max-batches)
              (let [batch (contract/batch value)]
                (swap! batch-count inc)
                (swap! buckets update stage (fnil conj []) batch) batch)))})

(defn- priority-rank [priority]
  (case priority :high 0 :normal 1 :low 2 3))

(defn interpolate-value
  "Interpolate immutable state without allocating platform objects."
  [previous current t]
  (cond
    (and (number? previous) (number? current))
    (+ (double previous) (* (double t) (- (double current) (double previous))))
    (and (map? previous) (map? current))
    (into (empty current)
          (map (fn [[k value]]
                 [k (if (contains? previous k)
                      (interpolate-value (get previous k) value t)
                      value)]))
          current)
    (and (vector? previous) (vector? current)
         (= (count previous) (count current)))
    (mapv #(interpolate-value %1 %2 t) previous current)
    :else current))

(defn- coordinate [value key index]
  (cond
    (map? value) (when (number? (get value key)) (double (get value key)))
    (and (sequential? value) (number? (nth value index nil)))
    (double (nth value index))
    :else nil))

(defn- distance-squared [a b]
  (let [ax (coordinate a :x 0) ay (coordinate a :y 1) az (coordinate a :z 2)
        bx (coordinate b :x 0) by (coordinate b :y 1) bz (coordinate b :z 2)]
    (when (every? some? [ax ay az bx by bz])
      (+ (Math/pow (- ax bx) 2.0)
         (Math/pow (- ay by) 2.0)
         (Math/pow (- az bz) 2.0)))))

(defn- invoke-bounds [bounds instance context]
  (try
    (bounds (assoc context :instance instance :state (:state instance)))
    (catch clojure.lang.ArityException _
      (bounds (:state instance) context))))

(defn- visible-instance? [effect instance context]
  (let [custom-visible (:visible? effect)
        custom-result (when custom-visible
                        (try
                          (custom-visible (assoc context :instance instance
                                                 :state (:state instance)))
                          (catch clojure.lang.ArityException _
                            (custom-visible (:state instance) context))))
        bounds-fn (:bounds effect)
        bounds (when bounds-fn (invoke-bounds bounds-fn instance context))
        center (:center bounds)
        radius (double (or (:radius bounds) 0.0))
        camera (:camera-pos context)
        max-distance (or (:max-distance bounds) (:max-distance effect)
                         (:view-distance context))
        distance2 (when (and center camera) (distance-squared center camera))]
    (and (not= false custom-result)
         (or (nil? distance2)
             (nil? max-distance)
             (<= distance2 (Math/pow (+ (double max-distance) radius) 2.0))))))

(defn sample-frame! [runtime context]
  (let [{:keys [frame-id] :as context} (contract/frame-context context)]
    (if (= frame-id @(:last-frame-id runtime)) @(:last-frame runtime)
        (do
          (reset! (:last-frame-id runtime) frame-id)
          (let [buckets (atom {}) batch-count (atom 0)]
            (doseq [[_ instance] (sort-by (fn [[id instance]]
                                            [(priority-rank (:priority instance)) id])
                                          @(:instances runtime))
                    :when (:alive? instance)]
              (let [effect (descriptor runtime (:effect-id instance))]
                (try
                  (let [sample-context (assoc context
                                              :instance instance
                                              :state (:state instance)
                                              :previous-state (:prev-state instance)
                                              :interpolated-state
                                              (interpolate-value (:prev-state instance)
                                                                 (:state instance)
                                                                 (:partial-tick context)))]
                    (when (and (visible-instance? effect instance sample-context)
                               (or (nil? (:lod effect))
                                   ((:lod effect) sample-context)))
                      ((:sample effect)
                       (assoc sample-context
                              :sink (make-sink buckets batch-count
                                               (:max-batches runtime))))))
                  (catch Throwable throwable (fault! runtime instance throwable :sample)))))
            (let [ordered-buckets (into {}
                                        (map (fn [[stage batches]]
                                               [stage (vec (sort-by (juxt :material :variant :count)
                                                                    batches))])
                                             @buckets))
                  frame (contract/frame frame-id @(:resource-generation runtime) ordered-buckets)]
              (reset! (:last-frame runtime) frame)
              (reset! (:latest-frame runtime) frame)
              frame))))))

(defn frame-digest
  "Stable, platform-neutral digest used by deterministic replay tests.
   Payloads are canonicalized because the ABI admits only immutable values or
   ByteBuffers; platform objects must never be present at this boundary."
  [frame]
  (hash (pr-str (canonical-value frame))))

(defn frame-stage [runtime frame-id stage]
  (let [frame @(:last-frame runtime)]
    (when (and frame (= frame-id (:frame-id frame))) (get-in frame [:stages stage] []))))
(defn latest-frame-stage
  "Read a stage from the most recently sampled immutable frame."
  [runtime stage]
  (get-in @(:latest-frame runtime) [:stages stage] []))
(defn release-frame! [runtime frame-id]
  (when (= frame-id @(:last-frame-id runtime)) (reset! (:last-frame runtime) nil)) nil)
(defn reload-resources! [runtime generation]
  (reset! (:resource-generation runtime) (long generation))
  (reset! (:last-frame runtime) nil)
  (reset! (:latest-frame runtime) nil)
  generation)
(defn resource-generation [runtime]
  @(:resource-generation runtime))
(defn faults [runtime] @(:faults runtime))
