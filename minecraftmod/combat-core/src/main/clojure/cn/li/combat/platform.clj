(ns cn.li.combat.platform
  "Neutral atomic combat capabilities.

   Combat Core owns request validation, bounded projection and targeting
   semantics.  The mcmod namespaces used here are relays only; their
   operations are installed by platform-src and never contain Minecraft
   behaviour."
  (:require [clojure.set :as set]
            [cn.li.combat.actions :as combat-actions]
            [cn.li.combat.deferred :as deferred]
            [cn.li.combat.targeting :as targeting]
            [cn.li.mcmod.platform.block-manipulation :as blocks]
            [cn.li.mcmod.platform.entity-damage :as damage]
            [cn.li.mcmod.platform.inventory :as inventory]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.runtime.capabilities :as capabilities]))

(set! *warn-on-reflection* true)

(defn- point [value]
  (cond
    (and (map? value) (vector? (:vec3 value))) (mapv double (:vec3 value))
    (and (map? value) (every? #(number? (get value %)) [:x :y :z]))
    [(double (:x value)) (double (:y value)) (double (:z value))]
    (and (vector? value) (= 3 (count value))) (mapv double value)
    :else nil))

(defn- project-entity [entity projection]
  (let [id (or (:id entity) (:uuid entity) (:entity-id entity))
        type (or (:type entity) (:entity-type entity))
        x (double (or (:x entity) 0.0))
        y (double (or (:y entity) 0.0))
        z (double (or (:z entity) 0.0))
        eye-height (double (or (:eye-height entity) (:height entity) 0.0))
        values {:id id :type type
                :position {:x x :y y :z z}
                :eye-height eye-height
                :age-ms (long (or (:age-ms entity) 0))
                :motion-progress (double (or (:motion-progress entity) 0.0))
                :owner-id (or (:owner-id entity) (:owner-uuid entity))
                :velocity (:velocity entity)
                :difficulty (double (or (:difficulty entity) 0.0))
                :explosion-power (:explosion-power entity)
                :item? (boolean (:item? entity))
                :projectile? (boolean (:projectile? entity))
                :arrow? (boolean (:arrow? entity))
                :living? (boolean (:living? entity))
                :mob? (boolean (:mob? entity))
                :multipart? (boolean (:multipart? entity))
                :tags (set (or (:tags entity) []))}]
    (select-keys values (or (seq projection)
                            [:id :type :position :eye-height]))))

(defn- entity-sort-key [by center entity]
  (let [[cx cy cz] center
        ex (double (or (:x entity) 0.0))
        ey (double (or (:y entity) 0.0))
        ez (double (or (:z entity) 0.0))]
    (case by
      :distance-squared
      (let [dx (- ex cx) dy (- ey cy) dz (- ez cz)]
        (+ (* dx dx) (* dy dy) (* dz dz)))
      :distance
      (Math/sqrt (double (entity-sort-key :distance-squared center entity)))
      :age-ms (long (or (:age-ms entity) 0))
      :motion-progress (double (or (:motion-progress entity) 0.0))
      (str (or (get entity by) "")))))

(defn- sort-entities [entities center sort-spec]
  (reduce (fn [items {:keys [by order]}]
            (let [items (sort-by #(entity-sort-key by center %) items)]
              (if (= :descending order) (reverse items) items)))
          entities
          (or sort-spec [])))

(defn entity-select!
  [{:keys [world-id owner shape filter projection limit sort]} _frame]
  (let [center (point (or (:center shape) (:origin shape)))
        eye-origin (point (or (:eye-origin shape) (:origin shape)
                              (:center shape)))
        direction (point (:direction shape))
        cone? (= :cone (:type shape))
        radius (double (or (:radius shape) 0.0))
        distance (double (or (:distance shape) (:range shape) radius))
        limit (max 0 (min 256 (long (or limit 256))))
        owner (some-> owner str)
        types (set (or (:entity-types filter) []))
        ids (set (map str (or (:entity-ids filter) [])))
        excluded (set (map str (or (:excluded-entity-ids filter) [])))
        excluded-tags (set (or (:excluded-tags filter) []))
        difficulty-map (reduce (fn [result entry]
                                 (if (string? entry)
                                   (let [index (.lastIndexOf ^String entry ":")]
                                     (if (pos? index)
                                       (try (assoc result (subs entry 0 index)
                                                    (Double/parseDouble (subs entry (inc index))))
                                            (catch Throwable _ result))
                                       result))
                                   result)) {} (or (:difficulty-entries filter) []))
        owner-filter (some-> (or (:owner filter) (:owner-id filter)) str)
        living-filter (when (contains? filter :living?) (boolean (:living? filter)))]
    (if (and world-id center (pos? limit)
             (if cone?
               (and direction eye-origin (<= 0.0 distance 128.0))
               (<= 0.0 radius 128.0))
             (world-effects/available?))
      (let [[x y z] center]
        (let [candidates (if cone?
                           (world-effects/find-entities-in-aabb
                            (str world-id) (- x distance) (- y distance) (- z distance)
                            (+ x distance) (+ y distance) (+ z distance))
                           (world-effects/find-entities-in-radius
                            (str world-id) x y z radius))
              [dx dy dz] (or direction [0.0 0.0 1.0])
              dlen (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))
              [dx dy dz] (if (pos? dlen)
                           [(/ dx dlen) (/ dy dlen) (/ dz dlen)]
                           [0.0 0.0 1.0])
              yaw-span (double (or (:yaw-span-degrees shape) 360.0))
              pitch-span (double (or (:pitch-span-degrees shape) 180.0))
              yaw0 (Math/atan2 dx dz)
              pitch0 (Math/atan2 dy (Math/sqrt (+ (* dx dx) (* dz dz))))]
          (->> candidates
             (filter map?)
             (remove #(= owner (str (or (:id %) (:uuid %) (:entity-id %)))))
             (filter #(or (empty? types)
                          (contains? types (or (:type %) (:entity-type %)))))
             (filter #(let [id (str (or (:id %) (:uuid %) (:entity-id %)))]
                        (and (or (empty? ids) (contains? ids id))
                             (not (contains? excluded id)))))
             (remove #(seq (set/intersection excluded-tags
                                              (set (or (:tags %) [])))))
             (filter #(or (nil? owner-filter)
                          (= owner-filter (str (or (:owner-id %)
                                                   (:owner-uuid %))))))
             (filter #(or (nil? living-filter)
                          (= living-filter (boolean (:living? %)))))
             (filter #(if-not cone?
                        true
                        (let [ex (- (double (or (:x %) x)) x)
                              ey (- (+ (double (or (:y %) y))
                                       (double (or (:eye-height %) 0.0)))
                                    (double (or (second eye-origin) y)))
                              ez (- (double (or (:z %) z)) z)
                              len (Math/sqrt (+ (* ex ex) (* ey ey) (* ez ez)))]
                          (if (<= len 1.0e-8)
                            true
                            (let [yaw (Math/atan2 ex ez)
                                  pitch (Math/atan2 ey (Math/sqrt (+ (* ex ex) (* ez ez))))
                                  yd (Math/abs (Math/toDegrees (- yaw yaw0)))
                                  pd (Math/abs (Math/toDegrees (- pitch pitch0)))]
                              (and (<= (min yd (- 360.0 yd)) (* 0.5 yaw-span))
                                   (<= pd (* 0.5 pitch-span))))))))
             (sort-entities [x y z] sort)
             (take limit)
             (mapv (fn [entity]
                     (let [type (or (:type entity) (:entity-type entity))
                           difficulty (double (or (get difficulty-map type) 1.0))]
                       (project-entity (assoc entity :difficulty difficulty)
                                       projection))))))
      []))))

(defn block-select!
  [{:keys [owner world-id shape limit]} _frame]
  (let [start (point (or (:start shape) (:origin shape)))
        direction (point (:direction shape))
        length (double (or (:length shape) 0.0))
        step (double (or (:step shape) 0.9))
        limit (max 0 (min 4096 (long (or limit 4096))))]
    (if (and world-id start direction (pos? length)
             (pos? step) (blocks/available?))
      (let [[sx sy sz] start [dx dy dz] direction]
        (->> (blocks/find-blocks-in-line
              (str world-id) sx sy sz dx dy dz length)
             (filter map?)
             (take limit)
             (mapv (fn [block]
                     (let [x (long (or (:x block) 0))
                           y (long (or (:y block) 0))
                           z (long (or (:z block) 0))]
                       {:position [(double x)
                                 (double (or (:y block) 0.0))
                                 (double (or (:z block) 0.0))]
                        :hardness (double (or (:hardness block) 0.0))
                        :block-id (:block-id block)
                        ;; These are neutral policy facts, not a skill
                        ;; decision.  The ability EDN decides whether a
                        ;; tier-capped variant may proceed.
                        :breakable? (boolean (and owner world-id
                                                   (blocks/can-break-block?
                                                    (str owner) (str world-id) x y z)))
                        :requires-high-tier-tool? (boolean
                                                   (and world-id
                                                        (blocks/requires-high-tier-tool?
                                                         (str world-id) x y z)))})
                     ))))
      [])))

(defn- basic-raycast [request]
  (let [{:keys [world-id owner origin direction distance include-entities?
                include-blocks?]} request
        [sx sy sz] (point origin)
        [dx dy dz] (point direction)
        distance (max 0.0 (min 128.0 (double (or distance 0.0))))
        hit (cond
              (and (:living-only? request) owner
                   (not= false include-entities?))
              (raycast/raycast-combined-from-player
               (str owner) distance true)
              (and (not= false include-entities?)
                   (not= false include-blocks?))
              (raycast/raycast-combined world-id sx sy sz dx dy dz distance)
              (not= false include-entities?)
              (raycast/raycast-entities world-id sx sy sz dx dy dz distance)
              (not= false include-blocks?)
              (raycast/raycast-blocks world-id sx sy sz dx dy dz distance)
              :else nil)]
    (or hit {:hit-type :miss :hit? false
             :position {:x (+ sx (* dx distance))
                        :y (+ sy (* dy distance))
                        :z (+ sz (* dz distance))}
             :world-id world-id
             :owner owner})))

(defn- directional-raycast [owner request]
  (let [{:keys [origin direction distance policy world-id]} request
        origin (or (point origin) [0.0 0.0 0.0])
        look (or (point (:look request))
                 (point direction)
                 [0.0 0.0 1.0])
        [ox oy oz] origin
        world-id (str world-id)
        raycast-fn (fn [sx sy sz dx dy dz max-distance]
                     (raycast/raycast-combined-excluding
                      world-id sx sy sz dx dy dz max-distance (str owner)))
        head-blocked? (fn [x y z]
                        (blocks/block-collidable?
                         world-id (int (Math/floor (double x)))
                         (int (Math/floor (+ (double y) 1.0)))
                         (int (Math/floor (double z)))))]
    (targeting/directional-destination
     {:origin {:x ox :y oy :z oz}
      :look {:x (nth look 0) :y (nth look 1) :z (nth look 2)}
      :eye-y (double (or (:eye-y request) (+ oy 1.62)))
      :distance distance :policy policy :raycast raycast-fn
      :direction (if (keyword? direction) direction :forward)
      :head-blocked? head-blocked?})))

(defn- resolve-destination [owner request]
  (let [{:keys [hit origin direction distance policy]} request
        origin (or (point origin) [0.0 0.0 0.0])
        direction (or (point direction) [0.0 0.0 1.0])
        [ox oy oz] origin
        [dx dy dz] direction
        hit (when (map? hit) hit)
        miss? (or (nil? hit) (= :miss (:hit-type hit)))
        hx (double (or (:hit-x hit) (:x hit) 0.0))
        hy (double (or (:hit-y hit) (:y hit) 0.0))
        hz (double (or (:hit-z hit) (:z hit) 0.0))
        eye-height (double (or (:eye-height hit)
                               (:entity-eye-height policy) 1.6))
        face (:face hit)
        destination (if miss?
                      {:x (+ ox (* dx (double distance)))
                       :y (+ oy (* dy (double distance)))
                       :z (+ oz (* dz (double distance)))}
                      (if (= :entity (:hit-type hit))
                        {:x hx :y (+ hy eye-height) :z hz}
                        (case face
                          :down {:x hx :y (- hy 1.0) :z hz}
                          :up {:x hx :y (+ hy 1.8) :z hz}
                          :north {:x hx :y (+ hy 1.7) :z (- hz 0.6)}
                          :south {:x hx :y (+ hy 1.7) :z (+ hz 0.6)}
                          :west {:x (- hx 0.6) :y (+ hy 1.7) :z hz}
                          :east {:x (+ hx 0.6) :y (+ hy 1.7) :z hz}
                          {:x hx :y hy :z hz})))
        ddx (- (double (:x destination)) ox)
        ddy (- (double (:y destination)) oy)
        ddz (- (double (:z destination)) oz)]
    {:position destination
     :distance (Math/sqrt (+ (* ddx ddx) (* ddy ddy) (* ddz ddz)))
     :hit? (not miss?)
     :valid? (>= (Math/sqrt (+ (* ddx ddx) (* ddy ddy) (* ddz ddz)))
                 (double (or (:minimum-distance policy) 0.0)))}))

(defn- penetration-raycast [request]
  (let [{:keys [origin direction distance policy world-id]} request
        world-id (str world-id)
        result (targeting/march-through-collision
                origin direction distance
                (:scan-step policy)
                (:clearance-steps policy)
                (fn [x y z]
                  (and (blocks/available?)
                       (blocks/block-collidable? world-id (int x) (int y) (int z)))))]
    (when result
      (let [position (:position result)]
        (assoc result
               :marker-position
               (update position :y + (double (or (:marker-offset-y policy) 0.0)))
               :hit? false)))))

(defn raycast!
  [{:keys [owner query-kind] :as request} _frame]
  (when (and (:world-id request) (point (:origin request))
             (or (point (:direction request))
                 (= :directional-destination query-kind))
             (raycast/available?))
    (case query-kind
      :directional-destination (directional-raycast owner request)
      :resolve-destination (resolve-destination owner request)
      :penetration (penetration-raycast request)
      (basic-raycast request))))

(defn interaction-resolve!
  "Resolve a neutral reflection interaction for the supplied target.

   Eligibility is supplied by the caller/session boundary; this function
   never names a skill.  The platform relay only supplies the target's eye
   ray and entity collision result."
  [{:keys [world-id target policy visual-origin visual-direction]} _frame]
  (let [target (some-> target str)
        distance (double (or (:shot-distance policy) 10.0))]
    (when (and world-id target (raycast/available?))
      (let [eye (raycast/player-position target)
            look (raycast/player-look-vector target)]
        (when (and eye look)
          (let [eye-y (double (or (:eye-y eye) (:y eye) 0.0))
                hit (raycast/raycast-entities
                     (str world-id) (:x eye) eye-y (:z eye)
                     (:x look) (:y look) (:z look) distance)]
            (when (= :entity (:hit-type hit))
              (let [visual-origin (point visual-origin)
                    visual-direction (point visual-direction)
                    visual-length (when (and visual-origin visual-direction)
                                    (let [[ox oy oz] visual-origin
                                          [ex ey ez] [(double (:x eye)) eye-y (double (:z eye))]
                                          vx (- ex ox) vy (- ey oy) vz (- ez oz)]
                                      (Math/sqrt (+ (* vx vx) (* vy vy) (* vz vz)))))
                    visual-direction (when visual-direction
                                       (let [[dx dy dz] visual-direction
                                             len (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))]
                                         (if (> len 1.0e-8)
                                           [(/ dx len) (/ dy len) (/ dz len)]
                                           [0.0 0.0 1.0])))
                    visual-start (when (and visual-origin visual-direction visual-length)
                                   (let [[ox oy oz] visual-origin
                                         [dx dy dz] visual-direction]
                                     {:x (+ ox (* dx visual-length))
                                      :y (+ oy (* dy visual-length))
                                      :z (+ oz (* dz visual-length))}))
                    visual-end (when (and visual-start visual-direction)
                                 (let [[dx dy dz] visual-direction]
                                   {:x (+ (:x visual-start) (* dx distance))
                                    :y (+ (:y visual-start) (* dy distance))
                                    :z (+ (:z visual-start) (* dz distance))}))]
                {:reflection-accepted? true
                 :reflection-target (or (:uuid hit) (:entity-id hit))
                 :reflection-start (or visual-start
                                       {:x (:x eye) :y eye-y :z (:z eye)})
                 :reflection-end (or visual-end
                                     {:x (+ (:x eye) (* (:x look) distance))
                                      :y (+ eye-y (* (:y look) distance))
                                      :z (+ (:z eye) (* (:z look) distance))})
               :reflection-damage
               (* (double (or (:damage-multiplier policy) 0.5))
                  (double (or (:reflection-base-damage policy) 0.0)))}))))))))

(defn damage!
  [{:keys [world-id target amount damage-type owner]}]
  (let [amount (double (or amount 0.0))]
    (when (and world-id target (pos? amount) (Double/isFinite amount)
               (damage/available?))
      (let [applied (damage/apply-direct-damage!
                     (str world-id) (str target) amount
                     (or damage-type :generic)
                     {:attacker-uuid owner})]
        {:status (if (not= false applied) :applied :failed)}))))

(defn break!
  [{:keys [owner world-id position expected-block-id drop? fortune-level
           tool-tier-capped?]}]
  (let [p (point position)
        [x y z] (mapv #(long (Math/floor (double %)))
                      (or p [0.0 0.0 0.0]))
        current (when (and world-id (blocks/available?))
                  (blocks/get-block (str world-id) x y z))
        tier-blocked? (and tool-tier-capped?
                           (blocks/requires-high-tier-tool? (str world-id) x y z))
        valid? (and owner world-id p current (not tier-blocked?)
                    (or (nil? expected-block-id)
                        (= (str expected-block-id) (str current)))
                    (blocks/can-break-block? (str owner) (str world-id) x y z))
        broken? (and valid?
                     (if (some? fortune-level)
                       (blocks/break-block! (str owner) (str world-id)
                                            x y z (not= false drop?)
                                            (long fortune-level))
                       (blocks/break-block! (str owner) (str world-id)
                                            x y z (not= false drop?))))]
    {:status (if broken? :applied :failed)
     :block-id current
     :position {:x x :y y :z z}}))

(defn break-budget!
  "Bounded, energy-metered break of a beam/aoe's discovered block list.
   Delegates each individual block to break! so both paths share the same
   permission/collision checks and internal-break flag."
  [request]
  (combat-actions/commit-block-break-budget! request break!))

(defn sound!
  [{:keys [world-id position sound-id source volume pitch]}]
  (let [p (point position)]
    (when (and world-id sound-id p (world-effects/available?))
      (let [[x y z] p]
        {:status (if (world-effects/play-sound!
                      (str world-id) x y z (str sound-id)
                      (or source :ambient)
                      (double (or volume 1.0)) (double (or pitch 1.0)))
                   :applied :failed)}))))

(defn explosion!
  "Create a bounded neutral explosion through the mcmod relay."
  [{:keys [world-id position radius fire? terrain? owner]}]
  (let [p (point position)
        radius (double (or radius 0.0))
        valid? (and world-id p (pos? radius) (<= radius 32.0)
                    (every? #(Double/isFinite (double %)) p)
                    (world-effects/available?))]
    (if valid?
      (let [[x y z] p
            result (world-effects/create-explosion!
                    (str world-id) x y z radius (boolean fire?)
                    {:terrain? (boolean terrain?)
                     :attacker-uuid (when owner (str owner))})]
        {:status (if (not= false result) :applied :failed)})
      {:status :rejected :reason :invalid-explosion-request})))

(defn consume-item!
  [{:keys [owner source count]}]
  (if (and owner (= :main-hand source) (inventory/available?))
    (if-let [result (inventory/consume-main-hand-item! owner (long (or count 1)))]
      {:status :applied :consumed (long (:consumed result))}
      {:status :failed})
    {:status :rejected :reason :invalid-inventory-consume-request}))

(defn spawn-entity!
  "Spawn a neutral tracked entity through the mcmod relay."
  [{:keys [world-id owner entity-type position velocity life-ticks]}]
  (if (and world-id owner (string? entity-type) (world-effects/available?))
    (let [entity-id (world-effects/spawn-entity!
                     world-id owner entity-type position velocity life-ticks)]
      {:status (if entity-id :applied :failed)
       :entity-id (when (not (true? entity-id)) entity-id)})
    {:status :rejected :reason :invalid-entity-spawn}))

(defn discard-entity!
  "Discard a neutral entity through the mcmod relay."
  [{:keys [world-id entity]}]
  (let [entity-id (or (:id entity) (:uuid entity) (:entity-id entity))]
    (if (and world-id entity-id (world-effects/available?))
      {:status (if (world-effects/discard-entity-by-uuid!
                    world-id (str entity-id))
                 :applied :failed)}
      {:status :rejected :reason :invalid-entity-discard})))

(defn configure-entity!
  "Apply only neutral entity state mutations requested by a compiled ability.
   The platform relay owns entity lookup; Combat Core owns the bounded shape."
  [{:keys [world-id entity velocity add-tags projectile-damage]}]
  (let [entity-id (or (:id entity) (:uuid entity) (:entity-id entity))
        velocity (or velocity [0.0 0.0 0.0])
        velocity (if (and (map? velocity) (vector? (:vec3 velocity)))
                   (:vec3 velocity)
                   (if (map? velocity)
                     [(:x velocity) (:y velocity) (:z velocity)]
                     velocity))
        valid? (and world-id entity-id (= 3 (count velocity))
                    (every? #(and (number? %) (Double/isFinite (double %))) velocity)
                    (or (nil? projectile-damage)
                        (and (number? projectile-damage)
                             (Double/isFinite (double projectile-damage))))
                    (every? string? (or add-tags []))
                    (world-effects/available?))]
    (if valid?
      {:status (if (world-effects/configure-entity!
                   (str world-id) (str entity-id)
                   (mapv double velocity) (vec add-tags)
                   (when (some? projectile-damage)
                     (double projectile-damage)))
                 :applied :failed)}
      {:status :rejected :reason :invalid-entity-configuration})))

(defn entity-velocity!
  [{:keys [world-id target velocity]}]
  (configure-entity! {:world-id world-id :entity target :velocity velocity
                       :add-tags []}))

(defn query-handlers []
  {:raycast raycast!
   :entity/select entity-select!
   :block/select block-select!
   :interaction/resolve interaction-resolve!})

(defn action-handlers []
  {:entity/damage damage!
   :block/break break!
   :block/break-budget break-budget!
   :world/sound sound!
   :world/explosion explosion!
   :inventory/consume consume-item!
   :entity/spawn spawn-entity!
   :entity/discard discard-entity!
   :entity/configure configure-entity!
   :motion/entity-velocity entity-velocity!
   :projectile/schedule-beam deferred/schedule-action!})

(defn install!
  "Register every neutral capability Combat Core owns with the shared
   mcmod capability registry.  Idempotent: a capability another caller
   already registered is left alone.  AC's bootstrap calls this once,
   ahead of catalog load, instead of hand-registering each capability
   itself."
  []
  (doseq [[capability handler] (query-handlers)]
    (when-not (contains? (:queries (capabilities/snapshot)) capability)
      (capabilities/register-query! capability handler)))
  (doseq [[capability handler] (action-handlers)]
    (when-not (contains? (:actions (capabilities/snapshot)) capability)
      (capabilities/register-action! capability handler))))
