(ns cn.li.vfx.final-client
  "Client-side adapter for the final typed VFX host.

   It owns only neutral effect instances and frame batches.  Minecraft and
   renderer objects stay outside this namespace; platform code consumes the
   returned batches through the opaque VFX host ABI."
  (:require [cn.li.vfx.final-engine :as engine])
  (:import [java.util ArrayList]
           [cn.li.mcmod.runtime.vfx ParticleBuffer ParticleKernel VfxBatch VfxFrame VfxOutput VfxOutputKind VfxRenderStage]))

(defn create-runtime
  [{:keys [max-instances max-batches]
    :or {max-instances 2048 max-batches 32768}}]
  {:registry (atom {})
   :instances (atom {})
   :tombstones (atom {})
   :next-id (atom 1)
   :frames (atom {})
   :latest-frame (atom nil)
   :frozen? (atom false)
   :generation (atom 0)
   :max-instances max-instances
   :max-batches max-batches
   :max-frames 8})

(defn- allocate-id [runtime]
  (let [id (swap! (:next-id runtime) inc)] id))

(defn registered-effects [runtime]
  (set (keys @(:registry runtime))))
(defn effect-lifecycle [runtime effect-id]
  (get-in @(:registry runtime) [effect-id :lifecycle]))
(defn register-effect! [runtime descriptor]
  (when @(:frozen? runtime)
    (throw (ex-info "final VFX registry is frozen" {:effect-id (:id descriptor)})))
  (swap! (:registry runtime) assoc (:id descriptor) descriptor)
  nil)

(defn- new-instance [runtime effect-id owner world-id instance-key instance-id]
  (let [descriptor (get @(:registry runtime) effect-id)
        _ (when-not descriptor
            (throw (ex-info "unknown final VFX effect"
                            {:effect-id effect-id :reason :unknown-effect})))
        id (allocate-id runtime)
        state ((or (:init descriptor) (constantly {})) {})]
    {:id id :instance-id instance-id :effect-id effect-id :owner owner :world-id world-id
     :instance-key instance-key :event-seq -1 :state-seq -1
     :descriptor descriptor :state state :params {} :age 0 :events []
     :particle-buffer (when-let [capacity (:particle-capacity descriptor)]
                        (ParticleBuffer. (int capacity)))}))

(defn ensure-instance! [runtime effect-id {:keys [owner instance-key]}]
  (or (some (fn [[id instance]]
              (when (and (= effect-id (:effect-id instance))
                         (= owner (:owner instance))
                         (= instance-key (:instance-key instance))) id)) @(:instances runtime))
      (let [instance (new-instance runtime effect-id owner nil instance-key nil)]
        (swap! (:instances runtime) assoc (:id instance) instance)
        (:id instance))))

(defn instance-for-effect [runtime effect-id]
  (some (fn [[id instance]] (when (= effect-id (:effect-id instance)) id))
        @(:instances runtime)))
(defn instance-for-owner [runtime effect-id owner]
  (some (fn [[id instance]]
          (when (and (= effect-id (:effect-id instance)) (= owner (:owner instance))) id))
        @(:instances runtime)))
(defn instance-state [runtime instance-id]
  (:state (get @(:instances runtime) instance-id)))
(defn update-instance-state! [runtime instance-id f]
  (swap! (:instances runtime) update-in [instance-id :state] f)
  nil)

(declare clear-owner!)

(defn signal! [runtime {:keys [instance]} event params]
  (swap! (:instances runtime) update-in [instance :events]
         (fnil conj []) {:event event :payload (or params {})})
  nil)

(defn- signal-instance-id [runtime effect-id owner world-id instance-key instance-id]
  (some (fn [[id instance]]
          (when (or (and instance-id (= instance-id (:instance-id instance))
                         (= effect-id (:effect-id instance))
                         (or (nil? owner) (= owner (:owner instance)))
                         (or (nil? world-id) (= world-id (:world-id instance))))
                    (and (nil? instance-id)
                         (= effect-id (:effect-id instance))
                         (= owner (:owner instance))
                         (= world-id (:world-id instance))
                         (= instance-key (:instance-key instance))))
            id))
        @(:instances runtime)))

(defn- tombstone-key [signal]
  [(:effect-id signal) (:instance-id signal) (:instance-key signal) (:owner signal)])

(defn- tombstone-seq [runtime signal]
  (get @(:tombstones runtime) (tombstone-key signal) {:event-seq -1 :state-seq -1}))

(defn- remember-tombstone! [runtime signal instance]
  (swap! (:tombstones runtime) assoc (tombstone-key signal)
         {:event-seq (long (or (:event-seq signal) (:event-seq instance) -1))
          :state-seq (long (or (:state-seq signal) (:state-seq instance) -1))})
  nil)

(defn dispatch-signal!
  "Apply a network signal with explicit lifecycle and sequence semantics.

   :snapshot creates/rebases an instance without replaying historical events;
   :release removes only the local tracking copy; :destroy removes it and
   records a tombstone so delayed deltas cannot resurrect an authoritative
   instance. State and event sequences are checked independently."
  [runtime signal]
  (let [{:keys [op effect-id owner world-id instance-key instance-id
                event-seq state-seq event params]} signal
        event-seq (long (or event-seq 0))
        state-seq (long (or state-seq event-seq 0))]
    (cond
      (= :clear-owner op)
      (clear-owner! runtime owner)

      :else
      (let [_ (when-not (get @(:registry runtime) effect-id)
                (throw (ex-info "unknown final VFX effect"
                                {:effect-id effect-id :reason :unknown-effect})))
            tombstone (tombstone-seq runtime signal)
            internal-id (signal-instance-id runtime effect-id owner world-id instance-key instance-id)
            create? (and (contains? #{:spawn :snapshot} op)
                         (or (nil? internal-id)
                             (> event-seq (long (:event-seq tombstone)))))
            internal-id (or internal-id
                            (when create?
                              (let [instance (new-instance runtime effect-id owner world-id instance-key instance-id)]
                                (swap! (:instances runtime) assoc (:id instance)
                                       (assoc instance :params (or params {})
                                              :event-seq event-seq :state-seq state-seq
                                              :age (long (or (:age-ticks signal) 0))))
                                (:id instance))))
            instance (when internal-id (get @(:instances runtime) internal-id))
            event-new? (and instance (> event-seq (long (:event-seq instance))))
            state-new? (and instance (> state-seq (long (:state-seq instance))))]
        (when instance
          (case op
            :release
            (when (or event-new? state-new?)
              (swap! (:instances runtime) dissoc internal-id))

            :destroy
            (when (or event-new? state-new?)
              (remember-tombstone! runtime signal instance)
              (swap! (:instances runtime) dissoc internal-id))

            :update
            (when state-new?
              (swap! (:instances runtime) update internal-id
                     (fn [current]
                       (assoc current :state-seq state-seq
                              :params (merge (:params current) (or params {})))))
              (when event-new?
                (swap! (:instances runtime) update internal-id assoc :event-seq event-seq)
                (signal! runtime {:instance internal-id} event params)))

            (:spawn :snapshot)
            (when (or event-new? state-new? (= :snapshot op))
              (swap! (:instances runtime) update internal-id
                     (fn [current]
                       (cond-> (assoc current :event-seq (max event-seq (long (:event-seq current)))
                                             :state-seq (max state-seq (long (:state-seq current))))
                         (contains? #{:spawn :snapshot} op)
                         (update :params merge (or params {})))))
              ;; A snapshot is a baseline, not an event replay.
              (when (and event-new? (= :spawn op))
                (signal! runtime {:instance internal-id} event params)))

            :trigger
            (when event-new?
              (swap! (:instances runtime) update internal-id assoc :event-seq event-seq)
              (signal! runtime {:instance internal-id} event params))
            nil))))
    nil))
(defn- tick-instance [instance context]
  (when-let [^ParticleBuffer particles (:particle-buffer instance)]
    (ParticleKernel/integrate particles 0 (.size particles)
                              (float (or (:delta-seconds context) 0.0))))
  (let [descriptor (:descriptor instance)
        next-state (when-let [update (:update descriptor)]
                     (update (:state instance)
                             (assoc context :instance instance :events (:events instance))))]
    (if (and (nil? next-state)
             (= :transient (:lifecycle descriptor)))
      nil
      (assoc instance :state (or next-state (:state instance)) :events []))))

(defn tick! [runtime context]
  (let [next (into {}
                   (keep (fn [[id instance]]
                           (let [updated (some-> (tick-instance instance context)
                                                 (update :age (fnil inc 0)))]
                             (when updated [id updated])))
                    @(:instances runtime)))]
    (reset! (:instances runtime) next)
    {:instances (count next)}))

(def ^:private stage->java
  {:world-translucent VfxRenderStage/WORLD_TRANSLUCENT
   :world-additive VfxRenderStage/WORLD_ADDITIVE
   :world-after-translucent VfxRenderStage/WORLD_AFTER_TRANSLUCENT
   :first-person VfxRenderStage/FIRST_PERSON
   :screen VfxRenderStage/SCREEN})

(def ^:private primitive->java
  {:billboard 1 :particle 2 :beam 3 :ribbon 4 :line 5 :mesh 6 :quad 7})

(defn- op->java-batch [op]
  (VfxBatch. (or (get stage->java (:stage op)) VfxRenderStage/WORLD_TRANSLUCENT)
             (int (hash (or (:material op) :default)))
             (int (get primitive->java (:primitive op) 0))
             0 nil))

(defn- op->java-output [op]
  (let [kind (case (:operation op)
               :audio VfxOutputKind/AUDIO
               (:camera-fov :camera-shake) VfxOutputKind/CAMERA
               :post-process VfxOutputKind/SCREEN
               nil)]
    (when kind
      (VfxOutput. kind (int (hash (or (:sound-id op) (:effect op) :none)))
                  (float (or (:volume op) (:value op) (:amplitude op) 0.0))
                  (some-> (or (:sound-id op) (:effect op)) str)))))

(defn- java-frame [frame]
  (let [batches (ArrayList.) outputs (ArrayList.)]
    (doseq [op (mapcat val (:stages frame))]
      (.add batches (op->java-batch op)))
    (doseq [op (:outputs frame)]
      (when-let [output (op->java-output op)] (.add outputs output)))
    (VfxFrame. (long (:frame-id frame)) 0 batches outputs)))
(defn sample-frame! [runtime context]
  (let [batches (atom [])
        sink {:emit! #(swap! batches conj %)}]
    (doseq [[_ instance] @(:instances runtime)]
      (if-let [sample (:sample (:descriptor instance))]
        (sample (assoc context :instance instance :sink sink))
        (when (:control-graph (:descriptor instance))
          (doseq [op (engine/sample-graph (:descriptor instance)
                                          (:params instance)
                                          (:state instance)
                                          (:age instance)
                                          (long (or (:seed instance) 0)))]
            (swap! batches conj (assoc op :instance-id (:id instance)))))))
    (let [ops @batches
          draw-ops (filterv #(= :draw-batch (:operation %)) ops)
          outputs (filterv #(not= :draw-batch (:operation %)) ops)
          base-frame {:frame-id (:frame-id context)
                      :stages (group-by :stage draw-ops)
                      :outputs outputs}
          frame (assoc base-frame :java-frame (java-frame base-frame))]
      (swap! (:frames runtime)
             (fn [frames]
               (let [next (assoc frames (:frame-id frame) frame)
                     ids (sort (keys next))
                     excess (max 0 (- (count ids) (:max-frames runtime)))]
                 (apply dissoc next (take excess ids)))))
      (reset! (:latest-frame runtime) frame)
      frame)))

(defn frame-stage [runtime frame-id stage]
  (get-in @(:frames runtime) [frame-id :stages stage]))
(defn latest-frame-stage [runtime stage]
  (get-in @(:latest-frame runtime) [:stages stage]))
(defn release-frame! [runtime frame-id]
  (swap! (:frames runtime) dissoc frame-id)
  nil)
(defn freeze-registry! [runtime] (reset! (:frozen? runtime) true) nil)

(defn clear-owner! [runtime owner]
  (swap! (:instances runtime)
         (fn [instances]
           (into {} (remove (fn [[_ instance]] (= owner (:owner instance))) instances))))
  nil)
(defn clear-world! [runtime world-id]
  (swap! (:instances runtime)
         (fn [instances]
           (into {} (remove (fn [[_ instance]] (= world-id (:world-id instance))) instances))))
  world-id)
(defn reload-resources! [runtime generation]
  (reset! (:generation runtime) generation)
  generation)
(defn resource-generation [runtime] @(:generation runtime))
