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
            [cn.li.mcmod.platform.player-motion :as player-motion]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.teleportation :as teleportation]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.server.platform-bridge :as server-bridge]
            [cn.li.mcmod.runtime.seeded-rng :as seeded-rng]
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

(defn- terrain-overlap?
  [entity bx by bz radius]
  (let [half-width (/ (double (or (:width entity) 0.6)) 2.0)
        ex (double (or (:x entity) 0.0))
        ey (double (or (:y entity) 0.0))
        ez (double (or (:z entity) 0.0))
        height (double (or (:height entity) 1.8))
        r (double radius)]
    (and (< (- ex half-width) (+ (double bx) r))
         (> (+ ex half-width) (- (double bx) (- r 1.0)))
         (< (+ ey height) (+ (double by) (+ r 0.2)))
         (> ey (- (double by) 0.2))
         (< (- ez half-width) (+ (double bz) r))
         (> (+ ez half-width) (- (double bz) (- r 1.0))))))

(defn- terrain-plotter [x0 y0 z0 dx dz]
  (let [adx (Math/abs (double dx)) adz (Math/abs (double dz))]
    (cond
      (> adz adx)
      {:axis :z :x0 (int z0) :y0 (int y0) :z0 (int x0)
       :x (int z0) :y (int y0) :z (int x0) :dyx 0.0
       :dzx (/ (double dx) (double dz)) :dirflag (if (pos? (double dz)) 1 -1)}
      (pos? adx)
      {:axis :x :x0 (int x0) :y0 (int y0) :z0 (int z0)
       :x (int x0) :y (int y0) :z (int z0) :dyx 0.0
       :dzx (/ (double dz) (double dx)) :dirflag (if (pos? (double dx)) 1 -1)})))

(defn- terrain-next-plotter [plotter]
  (let [{:keys [x0 y0 z0 x y z dyx dzx dirflag axis]} plotter
        next-x (+ x dirflag)
        val-y (+ y0 (* (- next-x x0) dyx))
        val-z (+ z0 (* (- next-x x0) dzx))
        next-state (cond
                     (> (Math/abs (double (- val-y y))) 0.5)
                     (assoc plotter :y (+ y (* (Math/signum (double dyx)) dirflag)))
                     (> (Math/abs (double (- val-z z))) 0.5)
                     (assoc plotter :z (+ z (* (Math/signum (double dzx)) dirflag)))
                     :else (assoc plotter :x next-x))]
    [(case axis
       :z [(int (:z next-state)) (int (:y next-state)) (int (:x next-state))]
       [(int (:x next-state)) (int (:y next-state)) (int (:z next-state))])
     next-state]))

(defn terrain-propagate!
  "Build a bounded deterministic terrain propagation plan.

   This is a neutral query. EDN applies transforms, breaks, damage and
   impulses as independent actions, so no skill-specific mutation is hidden
   in the host."
  [{:keys [owner world-id origin direction max-iterations initial-energy
           entity-search-radius spread energy-cost block-transforms
           ground-break-probability drop-probability launch-base launch-span
           seed mastery mastery-threshold mastery-radius mastery-hardness-cap]} _frame]
  (let [origin (point origin) direction (point direction)
        [ox oy oz] (or origin [0.0 0.0 0.0])
        [dx _dy dz] (or direction [0.0 0.0 1.0])
        horizontal (Math/sqrt (+ (* dx dx) (* dz dz)))
        max-iterations (long (max 0 (min 64 (or max-iterations 0))))
        energy0 (double (or initial-energy 0.0))
        radius (double (or entity-search-radius 2.0))
        seed (long (or seed 0))
        spread (or spread {})
        angle (double (or (:angle-radians spread) 90.0))
        c (Math/cos angle) s (Math/sin angle)
        sx (if (pos? horizontal) (/ dx horizontal) 0.0)
        sz (if (pos? horizontal) (/ dz horizontal) 1.0)
        spread-x (+ (* sx c) (* sz s))
        spread-z (- (* sz c) (* sx s))
        entries (or (:entries spread) [[0.0 1.0]])
        transforms (or block-transforms {})
        costs (or energy-cost {})
        plotter0 (terrain-plotter (Math/floor ox) (Math/floor oy)
                                  (Math/floor oz) sx sz)]
    (if-not (and owner world-id origin plotter0 (pos? horizontal))
      {:affected-blocks [] :transforms [] :broken-blocks [] :entities [] :mastery-breaks []}
      (let [state
            (loop [iter 0 plotter plotter0 energy energy0 rng seed
                   seen-blocks #{} seen-entities #{} affected [] transforms* [] broken [] entities []]
              (if (or (nil? plotter) (>= iter max-iterations) (<= energy 0.0))
                {:affected-blocks affected :transforms transforms* :broken-blocks broken
                 :entities entities}
                (let [[[bx by bz] next-plotter] (terrain-next-plotter plotter)
                      candidates (when (world-effects/available?)
                                   (world-effects/find-entities-in-aabb
                                    (str world-id) (- bx (+ radius 3.0)) (- by 2.0) (- bz (+ radius 3.0))
                                    (+ bx (+ radius 3.0)) (+ by 4.0) (+ bz (+ radius 3.0))))
                      result
                      (reduce
                       (fn [acc entry]
                         (let [[energy seen-blocks seen-entities affected transforms* broken entities rng] acc
                               [_entry [lateral probability]] entry
                               rng (seeded-rng/next-long rng)]
                           (if (> (double probability) (seeded-rng/unit-double rng))
                             (let [px (long (Math/floor (+ bx (* (double lateral) spread-x))))
                                   pz (long (Math/floor (+ bz (* (double lateral) spread-z))))
                                   py (long (Math/floor by)) key [px py pz]]
                               (if (contains? seen-blocks key)
                                 [energy seen-blocks seen-entities affected transforms* broken entities rng]
                                 (let [block-id (when (blocks/available?)
                                                  (blocks/get-block (str world-id) px py pz))
                                       cost (double (or (get costs block-id) (:default costs) 0.5))
                                       affected (if block-id (conj affected {:x px :y py :z pz :block-id block-id}) affected)
                                       seen-blocks (conj seen-blocks key)
                                       transform (get transforms block-id)
                                       transforms* (if (and block-id transform)
                                                     (conj transforms* {:position {:x px :y py :z pz}
                                                                        :block-id (:to transform)
                                                                        :expected-block-ids [block-id]})
                                                     transforms*)
                                       energy (- energy cost)
                                       ground-rng (seeded-rng/next-long rng)
                                       break? (and block-id
                                                    (> (double (or ground-break-probability 0.0))
                                                       (seeded-rng/unit-double ground-rng)))
                                       broken (if break?
                                                (conj broken {:position {:x bx :y by :z bz}
                                                              :drop? (> (double (or drop-probability 0.0))
                                                                        (seeded-rng/unit-double
                                                                         (seeded-rng/next-long ground-rng)))})
                                                broken)
                                       new-entities
                                       (reduce (fn [acc entity]
                                                 (let [id (or (:id entity) (:uuid entity) (:entity-id entity))]
                                                   (if (and id (not= (str id) (str owner))
                                                            (true? (:living? entity))
                                                            (not (contains? seen-entities (str id)))
                                                            (terrain-overlap? entity px py pz radius))
                                                     (conj acc {:id id
                                                                :position {:x (double (or (:x entity) 0.0))
                                                                           :y (double (or (:y entity) 0.0))
                                                                           :z (double (or (:z entity) 0.0))}
                                                                :velocity (or (:velocity entity) {:x 0.0 :y 0.0 :z 0.0})
                                                                :launch-y (+ (double (or launch-base 0.6))
                                                                             (* (double (or launch-span 0.3))
                                                                                (seeded-rng/unit-double
                                                                                 (seeded-rng/next-long
                                                                                  (unchecked-add seed (count acc))))))})
                                                     acc))) [] (or candidates []))]
                                   [energy seen-blocks
                                    (into seen-entities (map #(str (:id %)) new-entities))
                                    affected transforms* broken (into entities new-entities)
                                    (seeded-rng/next-long ground-rng)])))
                             [energy seen-blocks seen-entities affected transforms* broken entities rng])))
                       [energy seen-blocks seen-entities affected transforms* broken entities rng]
                       (map-indexed vector entries))
                      [energy _seen-blocks _seen-entities affected transforms* broken entities rng] result
                      vertical (reduce (fn [out dy*]
                                         (if (and (blocks/available?)
                                                  (blocks/get-block (str world-id) bx (+ by dy*) bz))
                                           (conj out {:position {:x bx :y (+ by dy*) :z bz} :drop? false})
                                           out))
                                       broken (range 1 4))]
                  (recur (inc iter) next-plotter (double energy) (long rng) _seen-blocks _seen-entities
                         affected transforms* vertical entities))))]
        (let [mastery (double (or mastery 0.0))
              mr (long (max 0 (min 8 (or mastery-radius 0))))
              x0 (long (Math/floor ox)) y0 (long (Math/floor oy)) z0 (long (Math/floor oz))
              mastery-breaks (if (and (>= mastery (double (or mastery-threshold 1.0))) (pos? mr))
                               (vec (for [x (range (- x0 mr) (+ x0 mr)) y (range (dec y0) (inc y0))
                                          z (range (- z0 mr) (+ z0 mr))
                                          :let [hardness (when (blocks/available?)
                                                           (blocks/get-block-hardness (str world-id) x y z))]
                                          :when (and (number? hardness)
                                                      (<= (double hardness) (double (or mastery-hardness-cap 0.6)))
                                                      (blocks/can-break-block? (str owner) (str world-id) x y z))]
                                     {:position {:x x :y y :z z} :drop? true}))
                               [])]
          (assoc state :mastery-breaks mastery-breaks))))))

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
    (let [result (or hit {:hit-type :miss :hit? false
                          :world-id world-id
                          :owner owner})]
      ;; Keep one neutral hit-position shape for all platform raycast
      ;; adapters.  Some bridges expose :hit-x/:hit-y/:hit-z while entity
      ;; traces expose :x/:y/:z; ability EDN should not know either ABI.
      (let [position (or (:position result)
                         {:x (double (or (:hit-x result) (:x result)
                                         (+ sx (* dx distance))))
                          :y (double (or (:hit-y result) (:y result)
                                         (+ sy (* dy distance))))
                          :z (double (or (:hit-z result) (:z result)
                                         (+ sz (* dz distance))))})
            attacked? (= :entity (:hit-type result))
            target-id (or (:target-id result) (:entity-id result)
                          (:entity-uuid result) (:uuid result))
            target-width (double (or (:width result) 0.5))
            target-height (double (or (:height result) 0.0))
            drop-position (if attacked?
                           (update position :y + target-height)
                           position)]
        (assoc result :position position
               :attacked? attacked?
               :target-id target-id
               :target-width target-width
               :target-height target-height
               :drop-position drop-position)))))

(defn- normalize-vector
  [[x y z]]
  (let [length (Math/sqrt (+ (* (double x) (double x))
                             (* (double y) (double y))
                             (* (double z) (double z))))]
    (if (pos? length)
      [(/ (double x) length) (/ (double y) length) (/ (double z) length)]
      [0.0 0.0 1.0])))

(defn- fan-direction
  [[x _y z] yaw-degrees pitch-degrees]
  (let [[hx _ hz] (normalize-vector [x 0.0 z])
        yaw (Math/toRadians (double yaw-degrees))
        cy (Math/cos yaw) sy (Math/sin yaw)
        yaw-dir [(+ (* hx cy) (* hz sy)) 0.0
                 (- (* hz cy) (* hx sy))]
        right (normalize-vector [(- (nth yaw-dir 2)) 0.0 (nth yaw-dir 0)])
        pitch (Math/toRadians (double pitch-degrees))
        cp (Math/cos pitch) sp (Math/sin pitch)
        [rx _ rz] right
        [yx _y yz] yaw-dir]
    [(+ (* yx cp) (* rx sp))
     sp
     (+ (* yz cp) (* rz sp))]))

(defn raycast-fan!
  "Resolve a bounded deterministic fan of block rays.

   Angles, jitter, range and result limits come from the effect document;
   this host function only calls the neutral block-ray relay."
  [{:keys [world-id origin direction distance pitch-angles
           yaw-range-degrees limit seed]} _frame]
  (let [[ox oy oz] (point origin)
        [dx dy dz] (point direction)
        pitches (vec (or pitch-angles []))
        [yaw-min yaw-max] (if (and (vector? yaw-range-degrees)
                                  (= 2 (count yaw-range-degrees)))
                            (mapv double yaw-range-degrees)
                            [0.0 0.0])
        max-count (max 0 (min 128 (long (or limit (count pitches)))))
        distance (max 0.0 (min 64.0 (double (or distance 0.0))))
        seed (long (or seed 0))]
    (if (and world-id origin direction (pos? distance)
             (seq pitches) (pos? max-count) (raycast/available?))
      (loop [idx 0 state (long seed) hits (transient [])]
        (if (or (>= idx max-count) (>= idx (count pitches)))
          (persistent! hits)
          (let [next-state (seeded-rng/next-long state)
                yaw (seeded-rng/uniform next-state yaw-min yaw-max)
                dir (fan-direction [dx dy dz] yaw (double (nth pitches idx)))
                start [(- (double ox) (* 0.5 (nth dir 0)))
                       (- (double oy) (* 0.5 (nth dir 1)))
                       (- (double oz) (* 0.5 (nth dir 2)))]
                hit (raycast/raycast-blocks (str world-id)
                                            (nth start 0) (nth start 1) (nth start 2)
                                            (nth dir 0) (nth dir 1) (nth dir 2)
                                            distance)]
            (recur (inc idx) (long (seeded-rng/next-long next-state))
                   (if (map? hit)
                     (conj! hits (select-keys hit [:x :y :z :face :hit-x :hit-y :hit-z]))
                     hits)))))
      [])))

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
      :raycast-fan (raycast-fan! request nil)
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

(defn set-block!
  [{:keys [world-id position block-id]}]
  (let [p (point position)
        [x y z] (mapv #(long (Math/floor (double %))) (or p [0.0 0.0 0.0]))
        applied? (and world-id p block-id (blocks/available?)
                      (blocks/set-block! (str world-id) x y z (str block-id)))]
    {:status (if (not= false applied?) :applied :failed)
     :position {:x x :y y :z z} :block-id block-id}))

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

(defn motion-velocity!
  "Set a bounded player velocity through the neutral motion relay."
  [{:keys [owner velocity dismount? reset-fall-damage?]}]
  (let [p (point velocity)
        valid? (and owner p (= 3 (count p))
                    (every? #(Double/isFinite (double %)) p)
                    (player-motion/available?))]
    (if-not valid?
      {:status :rejected :reason :invalid-motion-request}
      (let [[x y z] p
            dismounted? (or (not (true? dismount?))
                            (player-motion/dismount-riding! owner))
            applied? (and dismounted?
                          (player-motion/set-velocity! owner x y z))]
        (when (and applied? (true? reset-fall-damage?)
                   (teleportation/available?))
          (teleportation/reset-fall-damage! owner))
        {:status (if applied? :applied :failed)
         :velocity {:x x :y y :z z}}))))

(defn reset-fall-damage!
  [{:keys [owner target]}]
  (let [entity-id (str (or target owner))]
    (if (and (seq entity-id) (teleportation/available?))
      {:status (if (teleportation/reset-fall-damage! entity-id)
                 :applied :failed)}
      {:status :rejected :reason :teleportation-port-missing})))

(defn consume-item!
  [{:keys [owner source count]}]
  (if (and owner (= :main-hand source) (inventory/available?))
    (if-let [result (inventory/consume-main-hand-item! owner (long (or count 1)))]
      {:status :applied :consumed (long (:consumed result))}
      {:status :failed})
    {:status :rejected :reason :invalid-inventory-consume-request}))

(defn settle-item!
  "Settle one main-hand item using a neutral drop/consume policy.

   The policy is supplied by EDN; this host only invokes the cross-platform
   inventory primitives and never names a skill or item type."
  [{:keys [owner source count position drop? creative?]}]
  (let [count (long (or count 1))
        p (point position)
        valid? (and owner (= :main-hand source) (pos? count) p)]
    (if-not valid?
      {:status :rejected :reason :invalid-inventory-settle-request}
      (cond
        (and drop? creative?)
        {:status (if (inventory/spawn-main-hand-item-copy-at!
                      owner count (nth p 0) (nth p 1) (nth p 2))
                   :applied :failed)}

        drop?
        {:status (if (inventory/drop-main-hand-item-at!
                      owner count (nth p 0) (nth p 1) (nth p 2))
                   :applied :failed)}

        creative?
        {:status :applied}

        :else
        {:status (if (inventory/consume-main-hand-item! owner count)
                   :applied :failed)}))))

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

(defn entity-velocity-add!
  "Add a neutral velocity delta to one entity, preserving its current motion."
  [{:keys [world-id target velocity]}]
  (let [entity-id (or (:id target) (:uuid target) (:entity-id target))
        p (point velocity)
        valid? (and world-id entity-id p
                    (= 3 (count p))
                    (every? #(Double/isFinite (double %)) p)
                    (world-effects/available?))]
    (if valid?
      (let [[x y z] p]
        {:status (if (world-effects/add-entity-velocity!
                      (str world-id) (str entity-id) x y z)
                   :applied :failed)
         :velocity {:x x :y y :z z}})
      {:status :rejected :reason :invalid-entity-velocity-add-request})))

(defn teleport-entity!
  "Move one neutral entity to a position through the mcmod relay.
   Position mutation is deliberately separate from velocity/configuration so
   callers can compose ordering without a skill-specific host operation."
  [{:keys [world-id target position]}]
  (let [entity-id (or (:id target) (:uuid target) (:entity-id target) target)
        p (point position)
        valid? (and world-id entity-id p
                    (= 3 (count p))
                    (every? #(Double/isFinite (double %)) p)
                    (world-effects/available?))]
    (if valid?
      (let [[x y z] p]
        {:status (if (world-effects/teleport-entity!
                      (str world-id) (str entity-id) x y z)
                   :applied :failed)
         :position {:x x :y y :z z}})
      {:status :rejected :reason :invalid-entity-teleport-request})))

(defn entity-impulse!
  "Set one entity's neutral velocity vector.  The component remains generic;
   the platform relay is responsible only for resolving the entity id and
   applying the vector."
  [{:keys [world-id target vector]}]
  (configure-entity! {:world-id world-id :entity target :velocity vector
                       :add-tags []}))

(defn radial-impulse!
  "Apply a deterministic radial velocity to entities in a bounded sphere."
  [{:keys [owner world-id center radius speed-min speed-max seed]}]
  (let [center (point center)
        radius (double (or radius 0.0))
        speed-min (double (or speed-min 0.0))
        speed-max (double (or speed-max speed-min))
        finite? #(and (number? %) (Double/isFinite (double %)))
        valid? (and owner world-id center
                    (every? finite? (conj center radius speed-min speed-max))
                    (<= 0.0 radius 32.0)
                    (<= 0.0 speed-min speed-max 32.0)
                    (world-effects/available?))]
    (if-not valid?
      {:status :rejected :reason :invalid-radial-impulse-request}
      (let [[cx cy cz] center
            entities (entity-select!
                      {:owner owner :world-id world-id
                       :shape {:type :sphere :center center :radius radius}
                       :projection [:id :position :eye-height]
                       :limit 256}
                      nil)
            seed (long (or seed 0))
            hits (loop [xs (seq entities) index 0 total 0]
                   (if-let [entity (first xs)]
                     (let [[ex ey ez] (or (point (:position entity)) [cx cy cz])
                           ey (+ ey (double (or (:eye-height entity) 0.0)))
                           vx (- ex cx) vy (- ey cy) vz (- ez cz)
                           length (Math/sqrt (+ (* vx vx) (* vy vy) (* vz vz)))]
                       (if (<= length 1.0e-6)
                         (recur (next xs) (inc index) total)
                         (let [rng (seeded-rng/next-long
                                    (unchecked-add seed index))
                               magnitude (seeded-rng/uniform rng speed-min speed-max)
                               result (entity-impulse!
                                       {:world-id world-id :target entity
                                        :vector [(* (/ vx length) magnitude)
                                                 (* (/ vy length) magnitude)
                                                 (* (/ vz length) magnitude)]})]
                           (recur (next xs) (inc index)
                                  (if (= :applied (:status result))
                                    (inc total) total)))))
                     total))]
        {:status :applied :hits hits}))))

(defn random-break!
  "Break bounded random blocks around an origin with configurable hardness
   and drop policies.  This is a reusable terrain primitive, not a skill
   implementation."
  [{:keys [owner world-id origin attempts radius hardness-max break-probability
           drop-probability seed drop?]}]
  (let [origin (point origin)
        attempts (long (or attempts 0))
        radius (double (or radius 0.0))
        hardness-max (double (or hardness-max 0.0))
        break-probability (double (or break-probability 1.0))
        drop-probability (double (or drop-probability
                                    (if (nil? drop?) 1.0 0.0)))
        valid? (and owner world-id origin (blocks/available?)
                    (<= 0 attempts 256) (<= 0.0 radius 32.0)
                    (Double/isFinite hardness-max) (<= 0.0 hardness-max 64.0)
                    (Double/isFinite break-probability)
                    (<= 0.0 break-probability 1.0)
                    (Double/isFinite drop-probability)
                    (<= 0.0 drop-probability 1.0))]
    (if-not valid?
      {:status :rejected :reason :invalid-random-break-request}
      (let [[ox oy oz] origin
            seed (long (or seed 0))
            broken (loop [index 0 total 0]
                     (if (>= index attempts)
                       total
                       (let [rng (seeded-rng/next-long (unchecked-add seed index))
                             rx (long (Math/floor (seeded-rng/uniform rng (- radius) radius)))
                             r1 (seeded-rng/next-long rng)
                             ry (long (Math/floor (seeded-rng/uniform r1 (- radius) radius)))
                             r2 (seeded-rng/next-long r1)
                             rz (long (Math/floor (seeded-rng/uniform r2 (- radius) radius)))
                             x (long (Math/floor (+ ox rx)))
                             y (long (Math/floor (+ oy ry)))
                             z (long (Math/floor (+ oz rz)))
                             hardness (double (or (blocks/get-block-hardness
                                                  (str world-id) x y z)
                                                 -1.0))
                             allowed? (and (>= hardness 0.0)
                                           (<= hardness hardness-max)
                                           (blocks/can-break-block?
                                            (str owner) (str world-id) x y z))
                             break? (and allowed?
                                          (<= (seeded-rng/unit-double r2)
                                              break-probability))
                             did-break? (and break?
                                              (not= false
                                                    (blocks/break-block!
                                                     (str owner) (str world-id)
                                                     x y z
                                                     (<= (seeded-rng/unit-double r2)
                                                         drop-probability))))]
                         (recur (inc index) (if did-break? (inc total) total)))))]
        {:status :applied :broken broken}))))

(defn area-break!
  "Scan a bounded spherical neighborhood inside an integer cube and apply
   EDN-supplied hardness, break and drop policies.  The loops are primitive
   and deterministic; no coordinate collection is allocated."
  [{:keys [owner world-id origin radius hardness-max break-probability
           drop-probability self-drop? seed]}]
  (let [origin (point origin)
        radius (double (or radius 0.0))
        hardness-max (double (or hardness-max 0.0))
        break-probability (double (or break-probability 1.0))
        drop-probability (double (or drop-probability 1.0))
        valid? (and owner world-id origin (blocks/available?)
                    (<= 0.0 radius 8.0)
                    (Double/isFinite hardness-max) (<= 0.0 hardness-max 64.0)
                    (Double/isFinite break-probability)
                    (<= 0.0 break-probability 1.0)
                    (Double/isFinite drop-probability)
                    (<= 0.0 drop-probability 1.0))]
    (if-not valid?
      {:status :rejected :reason :invalid-area-break-request}
      (let [[ox0 oy0 oz0] origin
            ox (double ox0) oy (double oy0) oz (double oz0)
            x0 (long (+ ox 0.5)) y0 (long (+ oy 0.5)) z0 (long (+ oz 0.5))
            r (long (Math/ceil radius))
            radius-sq (* radius radius)
            seed (long (or seed 0))
            scan-block (fn [x y z index]
                         (let [dx (- (double x) ox)
                               dy (- (double y) oy)
                               dz (- (double z) oz)
                               inside? (<= (+ (* dx dx) (* dy dy) (* dz dz)) radius-sq)
                               rng (seeded-rng/next-long (unchecked-add seed index))
                               hardness (if inside?
                                         (double (or (blocks/get-block-hardness
                                                      (str world-id) x y z) -1.0))
                                         -1.0)
                               block-id (when (and inside? (>= hardness 0.0))
                                          (blocks/get-block (str world-id) x y z))
                               allowed? (and inside? (<= hardness hardness-max) block-id
                                             (blocks/can-break-block?
                                              (str owner) (str world-id) x y z))
                               break? (and allowed?
                                            (<= (seeded-rng/unit-double rng)
                                                break-probability))
                               drop? (if (or (not allowed?) self-drop?)
                                        false
                                        (<= (seeded-rng/unit-double
                                             (seeded-rng/next-long rng))
                                            drop-probability))]
                           (if break?
                             (let [result (blocks/break-block!
                                           (str owner) (str world-id) x y z drop?)]
                               (when (and self-drop?
                                          (not= false result)
                                          (server-bridge/server-bridge-available?))
                                 (server-bridge/spawn-item-stack-at!
                                  (str world-id) x y z block-id 1))
                               (if (not= false result) 1 0))
                             0)))
            scan-z (fn scan-z [x y z total index]
                     (if (> z (+ z0 r))
                       [total index]
                       (scan-z x y (inc z)
                               (+ total (scan-block x y z index))
                               (inc index))))
            scan-y (fn scan-y [x y total index]
                     (if (> y (+ y0 r))
                       [total index]
                       (let [[total index] (scan-z x y (- z0 r) total index)]
                         (scan-y x (inc y) total index))))
            scan-x (fn scan-x [x total index]
                     (if (> x (+ x0 r))
                       total
                       (let [[total index] (scan-y x (- y0 r) total index)]
                         (scan-x (inc x) total index))))
            broken (scan-x (- x0 r) 0 0)]
        {:status :applied :broken broken}))))

(defn query-handlers []
  {:raycast raycast!
   :entity/select entity-select!
   :block/select block-select!
   :terrain/propagate terrain-propagate!
   :interaction/resolve interaction-resolve!})

(defn action-handlers []
  {:entity/damage damage!
   :entity/impulse entity-impulse!
   :entity/radial-impulse radial-impulse!
   :block/break break!
   :block/set set-block!
   :block/break-budget break-budget!
   :world/sound sound!
   :world/explosion explosion!
   :motion/velocity motion-velocity!
   :entity/reset-fall-damage reset-fall-damage!
   :inventory/consume consume-item!
   :inventory/settle settle-item!
   :entity/spawn spawn-entity!
   :entity/discard discard-entity!
   :entity/configure configure-entity!
   :entity/teleport teleport-entity!
   :block/random-break random-break!
   :block/area-break area-break!
   :motion/entity-velocity entity-velocity!
   :motion/entity-velocity-add entity-velocity-add!
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
