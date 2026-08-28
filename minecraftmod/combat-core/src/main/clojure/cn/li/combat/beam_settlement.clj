(ns cn.li.combat.beam-settlement
  "Generic delayed projectile settlement owned by Combat Core.

  This namespace contains no skill identifiers.  It resolves optional moving
  origins and fresh owner ray destinations from neutral selectors, then calls
  mcmod's platform bridge for the atomic damage operation. AC only wires the
  tick/clear hooks; settlement returns a neutral result whose VFX intents are
  routed by the ability composition boundary."
  (:require [clojure.set :as set]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.util.log :as log]))

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

(defn- resolve-origin [world-id owner selector fallback]
  (let [fallback (or fallback (eye-pos owner))
        center (or (:center selector) (eye-pos owner))
        radius (double (or (:radius selector) 3.5))
        ex (double (or (:x center) 0.0))
        ey (double (or (:y center) 0.0))
        ez (double (or (:z center) 0.0))
        entity-type (:entity-type selector)
        entity-id (some-> (:entity-id selector) str)
        owner-id (some-> (or (:owner-id selector) owner) str)
        required-tags (set (or (:required-tags selector) []))]
    (or
      (when (and selector (world-effects/available?))
        (let [candidate (->> (world-effects/find-entities-in-aabb
                               world-id (- ex radius) (- ey radius) (- ez radius)
                               (+ ex radius) (+ ey radius) (+ ez radius))
                             (filter (fn [entity]
                                       (and (or (nil? entity-type)
                                                (= entity-type (or (:type entity)
                                                                   (:entity-type entity))))
                                            (or (nil? entity-id)
                                                (= entity-id
                                                   (some-> (or (:id entity)
                                                               (:uuid entity)
                                                               (:entity-id entity)) str)))
                                            (or (nil? owner-id)
                                                (= owner-id
                                                   (some-> (or (:owner-id entity)
                                                               (:owner-uuid entity)) str)))
                                            (set/subset? required-tags
                                                         (set (or (:tags entity) []))))))
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

(defn settle! [{:keys [owner world-id origin destination damage damage-type
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
      {:status :applied
       :owner owner
       :world-id world-id
       :vfx-signals (vec (when settlement-vfx
                           [(assoc settlement-vfx
                                   :owner owner :world-id world-id
                                   :event-seq (long (or event-seq 0))
                                   :seed (long (or seed 0))
                                   :payload (assoc (or (:payload settlement-vfx) {})
                                                   :start origin :end destination))]))})))

(defn schedule-action!
  "Validate a neutral delayed beam request and pass it to an injected
   instance-local continuation scheduler.  Combat Core owns settlement; the
   scheduler lifetime belongs to the composition root."
  [schedule! {:keys [owner delay-ticks] :as request}]
  (if (and (ifn? schedule!) owner (:world-id request)
           (map? (:origin request)) (map? (:destination request))
           (number? (:damage request))
           (Double/isFinite (double (:damage request))))
    (try
      (schedule! {:owner owner
                  :delay-ticks (long (max 1 (or delay-ticks 1)))
                  :payload (-> request
                               (assoc :damage (double (:damage request)))
                               (assoc :damage-type (or (:damage-type request) :generic))
                               (assoc :exclude-owner? (boolean (:exclude-owner? request)))
                               (assoc :seed (long (or (:seed request) 0))))})
      {:status :scheduled :owner owner}
      (catch Throwable error
        {:status :rejected :reason :continuation-schedule-failed
         :message (ex-message error)}))
    {:status :rejected :reason :invalid-beam-request}))
