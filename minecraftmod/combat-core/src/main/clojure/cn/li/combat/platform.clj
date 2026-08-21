(ns cn.li.combat.platform
  "Neutral atomic combat capabilities.

   Combat Core owns request validation, bounded projection and targeting
   semantics.  The mcmod namespaces used here are relays only; their
   operations are installed by platform-src and never contain Minecraft
   behaviour."
  (:require [cn.li.combat.targeting :as targeting]
            [cn.li.mcmod.platform.block-manipulation :as blocks]
            [cn.li.mcmod.platform.entity-damage :as damage]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.world-effects :as world-effects]))

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
                :owner-id (or (:owner-id entity) (:owner-uuid entity))}]
    (select-keys values (or (seq projection)
                            [:id :type :position :eye-height]))))

(defn entity-select!
  [{:keys [world-id owner shape filter projection limit]} _frame]
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
             (take limit)
             (mapv #(project-entity % projection)))))
      [])))

(defn block-select!
  [{:keys [world-id shape limit]} _frame]
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
                     {:position [(double (or (:x block) 0.0))
                                 (double (or (:y block) 0.0))
                                 (double (or (:z block) 0.0))]
                      :hardness (double (or (:hardness block) 0.0))
                      :block-id (:block-id block)}))))
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
  [{:keys [owner world-id position expected-block-id drop?]}]
  (let [p (point position)
        [x y z] (mapv #(long (Math/floor (double %)))
                      (or p [0.0 0.0 0.0]))
        current (when (and world-id (blocks/available?))
                  (blocks/get-block (str world-id) x y z))
        valid? (and owner world-id p current
                    (or (nil? expected-block-id)
                        (= (str expected-block-id) (str current)))
                    (blocks/can-break-block? (str owner) (str world-id) x y z))
        broken? (and valid?
                     (blocks/break-block! (str owner) (str world-id)
                                          x y z (not= false drop?)))]
    {:status (if broken? :applied :failed)
     :block-id current
     :position {:x x :y y :z z}}))

(defn query-handlers []
  {:raycast raycast!
   :entity/select entity-select!
   :block/select block-select!
   :interaction/resolve interaction-resolve!})

(defn action-handlers []
  {:entity/damage damage!
   :block/break break!})
