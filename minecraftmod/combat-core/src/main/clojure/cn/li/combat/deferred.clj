(ns cn.li.combat.deferred
  "Generic delayed projectile settlement owned by Combat Core.

  This namespace contains no skill identifiers.  It resolves optional moving
  origins and fresh owner ray destinations from neutral selectors, then calls
  mcmod's platform bridge for the atomic damage operation.  AC only wires the
  tick/clear hooks and publishes the deferred VFX signal."
  (:require [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private schedules* (atom {}))
(defonce ^:private vfx-emitter* (atom nil))

(defn install-vfx-emitter!
  "Install the host output bridge used for deferred, neutral VFX signals."
  [emit!]
  (when-not (ifn? emit!)
    (throw (ex-info "deferred VFX emitter must be callable" {:emit emit!})))
  (reset! vfx-emitter* emit!)
  nil)

(defn- body-pos [owner]
  (let [p (or (raycast/player-position owner) {})]
    {:x (double (or (:x p) 0.0))
     :y (double (or (:y p) 0.0))
     :z (double (or (:z p) 0.0))}))

(defn- eye-pos [owner]
  (let [p (body-pos owner)
        raw (raycast/player-position owner)]
    (assoc p :y (double (or (:eye-y raw) (:y p))))))

(defn- v+ [a b]
  {:x (+ (double (:x a)) (double (:x b)))
   :y (+ (double (:y a)) (double (:y b)))
   :z (+ (double (:z a)) (double (:z b)))})

(defn- v* [a scale]
  {:x (* (double (:x a)) (double scale))
   :y (* (double (:y a)) (double scale))
   :z (* (double (:z a)) (double scale))})

(defn- vnorm [v]
  (let [x (double (:x v)) y (double (:y v)) z (double (:z v))
        length (Math/sqrt (+ (* x x) (* y y) (* z z)))]
    (if (pos? length)
      {:x (/ x length) :y (/ y length) :z (/ z length)}
      {:x 0.0 :y 0.0 :z 1.0})))

(defn clear-owner! [owner]
  (swap! schedules* dissoc owner)
  nil)

(defn clear-all! []
  (reset! schedules* {})
  nil)

(defn pending [owner]
  (let [{:keys [tick tasks]} (get @schedules* owner)]
    (mapv #(assoc % :ticks-left (max 0 (- (:due-tick %) (long (or tick 0)))))
          (or tasks []))))

(defn schedule!
  "Queue a bounded neutral beam task for the owner's server-thread tick."
  [{:keys [owner delay-ticks] :as task}]
  (let [delay (max 1 (long (or delay-ticks 1)))]
    (swap! schedules*
           (fn [state]
             (let [{:keys [tick tasks]} (get state owner {:tick 0 :tasks []})
                   due (+ (long tick) delay)]
               (assoc state owner {:tick (long tick)
                                   :tasks (conj (vec tasks)
                                                (assoc task :due-tick due))}))))
     nil))

(defn schedule-action!
  "Capability handler for the neutral delayed beam scheduler."
  [{:keys [owner world-id origin destination damage damage-type delay-ticks
           origin-selector destination-selector exclude-owner? settlement-vfx
           seed instance-key]}]
  (if (and owner world-id (map? origin) (map? destination)
           (number? damage) (Double/isFinite (double damage)))
    (do
      (schedule! {:owner owner
                  :world-id world-id
                  :origin origin
                  :destination destination
                  :damage (double damage)
                  :damage-type (or damage-type :generic)
                  :delay-ticks (long (max 1 (or delay-ticks 1)))
                  :origin-selector origin-selector
                  :destination-selector destination-selector
                  :exclude-owner? (boolean exclude-owner?)
                  :settlement-vfx settlement-vfx
                  :instance-key instance-key
                  :seed (long (or seed 0))})
      {:status :scheduled})
    {:status :rejected :reason :invalid-beam-request}))

(defn- resolve-origin [world-id owner selector fallback]
  (let [fallback (or fallback (eye-pos owner))
        center (or (:center selector) (eye-pos owner))
        radius (double (or (:radius selector) 3.5))
        ex (double (or (:x center) 0.0))
        ey (double (or (:y center) 0.0))
        ez (double (or (:z center) 0.0))
        entity-type (:entity-type selector)
        owner-id (some-> (or (:owner-id selector) owner) str)]
    (or
      (when (and selector (world-effects/available?))
        (let [candidate (->> (world-effects/find-entities-in-aabb
                               world-id (- ex radius) (- ey radius) (- ez radius)
                               (+ ex radius) (+ ey radius) (+ ez radius))
                             (filter (fn [entity]
                                       (and (or (nil? entity-type)
                                                (= entity-type (or (:type entity)
                                                                   (:entity-type entity))))
                                            (or (nil? owner-id)
                                                (= owner-id
                                                   (some-> (or (:owner-id entity)
                                                               (:owner-uuid entity)) str))))))
                             (sort-by (fn [{:keys [x y z]}]
                                        (let [dx (- (double (or x 0.0)) ex)
                                              dy (- (double (or y 0.0)) ey)
                                              dz (- (double (or z 0.0)) ez)]
                                          (+ (* dx dx) (* dy dy) (* dz dz)))))
                             first)]
          (when candidate
            {:x (double (or (:x candidate) ex))
             :y (double (or (:y candidate) ey))
             :z (double (or (:z candidate) ez))})))
      fallback)))

(defn- resolve-looking [world-id owner direction distance]
  (let [eye (eye-pos owner)
        hit (raycast/raycast-combined-excluding
             world-id (:x eye) (:y eye) (:z eye)
             (:x direction) (:y direction) (:z direction)
             (double distance) (str owner))]
    (if (:hit-type hit)
      {:x (double (:hit-x hit))
       :y (+ (double (:hit-y hit))
             (if (= "entity" (:hit-type hit))
               (* 0.6 (double (or (:eye-height hit) 0.0))) 0.0))
       :z (double (:hit-z hit))}
      (v+ (body-pos owner) (v* direction distance)))))

(defn- point [value]
  (cond
    (and (map? value) (vector? (:vec3 value))) (:vec3 value)
    (and (map? value) (every? #(number? (get value %)) [:x :y :z]))
    [(:x value) (:y value) (:z value)]
    (vector? value) value
    :else [0.0 0.0 0.0]))

(defn- settle! [{:keys [owner world-id origin destination damage damage-type
                        origin-selector destination-selector exclude-owner?
                         settlement-vfx event-seq seed]}]
  (when (raycast/available?)
    (let [origin (if origin-selector
                   (resolve-origin world-id owner origin-selector origin)
                   origin)
          look (when destination-selector (raycast/player-look-vector owner))
          destination (if (and destination-selector look)
                        (resolve-looking world-id owner
                                         (vnorm look)
                                         (double (or (:distance destination-selector)
                                                     15.0)))
                        destination)
          [ox oy oz] (map double (point origin))
          [dx0 dy0 dz0] (map double (point destination))
          dx (- dx0 ox) dy (- dy0 oy) dz (- dz0 oz)
          distance (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))
          direction (if (pos? distance)
                      {:x (/ dx distance) :y (/ dy distance) :z (/ dz distance)}
                      {:x 0.0 :y 0.0 :z 1.0})
          hit (if exclude-owner?
                (raycast/raycast-combined-excluding world-id ox oy oz
                 (:x direction) (:y direction) (:z direction)
                 (max 0.1 distance) (str owner))
                (raycast/raycast-combined-all world-id ox oy oz
                 (:x direction) (:y direction) (:z direction)
                 (max 0.1 distance)))
          target (when (= "entity" (:hit-type hit))
                   (or (:uuid hit) (:entity-id hit)))]
      (when (and target (entity-damage/available?))
        (entity-damage/apply-direct-damage!
         world-id target (double (or damage 0.0))
         (or damage-type :generic)
         {:attacker-uuid owner :reset-invulnerable-time? true}))
      (when (and @vfx-emitter* settlement-vfx)
        (@vfx-emitter* (assoc settlement-vfx
                               :owner owner :world-id world-id
                               :event-seq (long (or event-seq 0))
                               :seed (long (or seed 0))
                               :payload (assoc (or (:payload settlement-vfx) {})
                                               :start origin :end destination)))))))

(defn tick-owner!
  "Advance one owner's queue and settle all due tasks through neutral bridges."
  [owner]
  (let [{:keys [tick tasks]} (get @schedules* owner)]
    (when (some? tick)
      (let [now (inc (long tick))
            [due pending] (reduce (fn [[due pending] task]
                                   (if (<= (long (:due-tick task)) now)
                                     [(conj due task) pending]
                                     [due (conj pending task)]))
                                 [[] []] tasks)]
        (if (seq pending)
          (swap! schedules* assoc owner {:tick now :tasks pending})
          (swap! schedules* dissoc owner))
        (doseq [task due]
          (try (settle! task)
               (catch Throwable error
                 (log/warn "Deferred neutral beam settlement failed:" (ex-message error))))))))
  nil)
