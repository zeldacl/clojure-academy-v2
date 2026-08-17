(ns cn.li.mcbase.runtime.adapter.world-effects
  "Shared IWorldEffects adapter factory.

  Platform namespaces provide the server lookup plus platform-specific
  entity/lightning/explosion callbacks; this namespace owns the shared
  world-query orchestration and protocol-var installation."
  (:require [cn.li.mcbase.runtime.entity-query-core :as query-core]
            [cn.li.mcbase.runtime.entity-motion-core :as entity-motion]
            [cn.li.mcbase.runtime.player-motion-core :as player-motion]
            [cn.li.mcbase.runtime.teleportation-core :as teleportation]
            [cn.li.mcbase.runtime.multipart-entity :as multipart]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.framework.platform :as platform]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.block-manipulation :as block-manipulation]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerLevel]
           [net.minecraft.world.entity Entity]
           [net.minecraft.world.level.block Block]))

(defonce ^:private world-core-atom (atom nil))

(defn install-world-core!
  [m]
  (reset! world-core-atom m)
  m)

(defn- core []
  (let [m @world-core-atom]
    (when (nil? m)
      (throw (IllegalStateException. "world-effects-core not installed")))
    m))

(defn- resolve-level [server resolve-level-fn world-id]
  (when server
    (resolve-level-fn server world-id)))

(defn- point-of
  "Defensively read an {:x :y :z} point off either a normalized :impact map
   (ac.ability.util.attack's shape) or a raw raycast hit map (:hit-x/:hit-y/
   :hit-z). Checks :hit-x before :x, matching ac.ability.util.attack/
   block-impact-point's established precedent for the same data."
  [m]
  {:x (double (or (:hit-x m) (:x m) 0.0))
   :y (double (or (:hit-y m) (:y m) 0.0))
   :z (double (or (:hit-z m) (:z m) 0.0))})

(defn- sort-by-distance
  "Order entity maps ({:x :y :z ...}) nearest-first to origin."
  [origin entities]
  (sort-by (fn [{:keys [x y z]}]
             (let [dx (- (double (or x 0.0)) (double (:x origin)))
                   dy (- (double (or y 0.0)) (double (:y origin)))
                   dz (- (double (or z 0.0)) (double (:z origin)))]
               (+ (* dx dx) (* dy dy) (* dz dz))))
           entities))

(defn- within-cone?
  "True when entity is within half-angle-degrees of dir (a unit vector) as
   seen from origin. A zero-length offset (entity exactly at origin) always
   passes -- there is no meaningful direction to test."
  [origin dir-x dir-y dir-z half-angle-degrees {:keys [x y z]}]
  (let [dx (- (double (or x 0.0)) (double (:x origin)))
        dy (- (double (or y 0.0)) (double (:y origin)))
        dz (- (double (or z 0.0)) (double (:z origin)))
        len (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))]
    (or (zero? len)
        (>= (/ (+ (* dx dir-x) (* dy dir-y) (* dz dir-z)) len)
            (Math/cos (Math/toRadians (double half-angle-degrees)))))))

(defn- execute-vec-deviation-adapter!
  [server-fn world-id plan]
  (let [entities (:entities (:query-result plan))]
    (and world-id
         (sequential? entities)
         (every? (fn [{:keys [uuid]}]
                   (when uuid
                     (when-let [entity (entity-motion/resolve-entity
                                        (server-fn) world-id uuid)]
                       (entity-motion/set-velocity-for-entity! entity 0.0 0.0 0.0)
                       (entity-motion/add-tag-for-entity! entity "ac_vm_deviated")
                       true)))
                 entities))))

(defn create-world-effects
  [server-fn {:keys [resolve-level-fn spawn-lightning-fn create-explosion-fn spawn-projectile-fn get-entities-in-aabb-fn resolve-entity-id-fn block-id-fn get-entity-by-uuid-fn]
              :or {resolve-level-fn query-core/resolve-level-strict
                   get-entity-by-uuid-fn query-core/get-entity-by-uuid
                   resolve-entity-id-fn (fn [^Entity entity] (str (.getDescriptionId (.getType entity))))
                   block-id-fn (fn [^Block block _block-state] (str (.getDescriptionId block)))}}]
  (let [spawn-lightning! (or spawn-lightning-fn (fn [_level _x _y _z _visual-only?] false))
        create-explosion! (or create-explosion-fn
                              (fn [_level _source _x _y _z _radius _fire? _terrain?]
                                false))
        spawn-projectile! (or spawn-projectile-fn
                              (fn [level projectile-spec]
                                ((:spawn-projectile-in-level! (core))
                                  level projectile-spec resolve-entity-id-fn get-entity-by-uuid-fn)))
        get-entities-in-aabb (or get-entities-in-aabb-fn (fn [_level _aabb] []))
        multipart-entity? multipart/multipart?
        find-entities-in-radius! (fn [world-id x y z radius]
                                   (try
                                     (when-let [^MinecraftServer server (server-fn)]
                                       (when-let [^ServerLevel level (resolve-level server resolve-level-fn world-id)]
                                         ((:entities-in-radius (core))
                                           level
                                           x y z radius
                                           get-entities-in-aabb
                                           resolve-entity-id-fn
                                           multipart-entity?)))
                                     (catch Exception e
                                       (log/warn "Failed to find entities:" (ex-message e))
                                       [])))
        ;; apply-aoe-damage! (mcmod.platform.entity-damage) has no
        ;; owner-exclusion parameter at all -- calling it directly for a
        ;; player-cast AOE would also damage the caster. Owner exclusion and
        ;; the spherical distance check mirror ac.ability.util.attack/
        ;; aoe-victims, which this namespace cannot require (platform code
        ;; must not depend on AC).
        aoe-victims! (fn [world-id owner x y z radius]
                       (let [radius (double radius)
                             radius-sq (* radius radius)]
                         (->> (find-entities-in-radius! world-id x y z radius)
                              (remove #(= (str owner) (str (:uuid %))))
                              (filter (fn [{ex :x ey :y ez :z}]
                                        (let [dx (- (double (or ex 0.0)) x)
                                              dy (- (double (or ey 0.0)) y)
                                              dz (- (double (or ez 0.0)) z)]
                                          (<= (+ (* dx dx) (* dy dy) (* dz dz)) radius-sq)))))))
        apply-aoe-damage-excluding-owner! (fn [world-id owner x y z radius damage source-type]
                                            (try
                                              (let [victims (aoe-victims! world-id owner x y z radius)]
                                                (doseq [{:keys [uuid]} victims]
                                                  (entity-damage/apply-direct-damage!
                                                   world-id uuid (double damage) source-type
                                                   {:attacker-uuid owner}))
                                                true)
                                              (catch Exception e
                                                (log/warn "Failed to apply AOE damage:" (ex-message e))
                                                false)))
        execute-thunder-clap! (fn [world-id owner plan]
                                (let [{:keys [query-result amount aoe-radius]} plan
                                      point (point-of (:impact query-result))]
                                  (apply-aoe-damage-excluding-owner!
                                   world-id owner (:x point) (:y point) (:z point)
                                   aoe-radius amount :electric)))
        execute-blood-retrograde! (fn [world-id owner plan]
                                    (let [{:keys [query-result amount entity-search-radius]} plan
                                          point (point-of query-result)]
                                      (apply-aoe-damage-excluding-owner!
                                       world-id owner (:x point) (:y point) (:z point)
                                       entity-search-radius amount :vector)))
        execute-plasma-cannon! (fn [world-id owner plan]
                                 (let [{:keys [query-result damage explosion-radius]} plan
                                       point (point-of (:impact query-result))]
                                   (apply-aoe-damage-excluding-owner!
                                    world-id owner (:x point) (:y point) (:z point)
                                    explosion-radius damage :electric)))
        ;; Only the raycast-hit target takes damage. beam-radius/block-energy
        ;; (block melting along the beam path) and the :reflection field
        ;; (Vector-Reflection passive integration) are deliberately not
        ;; implemented here -- see docs/04-systems/COMBAT_VFX_PLATFORM_GAPS.md.
        execute-meltdowner! (fn [world-id owner plan]
                             (let [{:keys [target damage]} plan
                                   entity-uuid (or (:uuid target) (:entity-uuid target) (:entity-id target))]
                               (try
                                 (boolean
                                  (and entity-uuid
                                       (entity-damage/apply-direct-damage!
                                        world-id entity-uuid (double damage) :electric
                                        {:attacker-uuid owner})))
                                 (catch Exception e
                                   (log/warn "Failed to apply meltdowner damage:" (ex-message e))
                                   false))))
        ;; Owner-keyed mining progress. combat-core drives this executor once
        ;; per real tick (:period-ticks 1) with a freshly re-scanned block
        ;; position each time; break-speed is a fraction of the target
        ;; block's hardness gained per tick, not an instant-break threshold
        ;; (mine-ray-basic's 0.2-0.4 is well under most block hardness
        ;; values). Aiming at a different block resets progress -- there is
        ;; no partial credit carried between targets, matching vanilla
        ;; mining's own behavior when you stop hitting a block.
        mine-progress-atom (atom {})
        execute-mine-ray! (fn [world-id owner plan]
                            (try
                              (let [{:keys [scan fortune]} plan
                                    {:keys [x y z hardness]} scan
                                    break-speed (double (:break-speed plan))
                                    hardness (double (or hardness 1.0))
                                    pos-key [world-id x y z]
                                    prior (get @mine-progress-atom owner)
                                    progress (+ break-speed
                                                (if (= (:pos prior) pos-key)
                                                  (double (or (:progress prior) 0.0))
                                                  0.0))]
                                (if (>= progress hardness)
                                  (do (swap! mine-progress-atom dissoc owner)
                                      (boolean (block-manipulation/break-block!
                                                owner world-id x y z true (long (or fortune 0)))))
                                  (do (swap! mine-progress-atom assoc owner
                                             {:pos pos-key :progress progress})
                                      true)))
                              (catch Exception e
                                (log/warn "Failed to apply mine-ray:" (ex-message e))
                                false)))
        ;; storm-wing is personal flight: thrust in the caster's look
        ;; direction, boosted by speed-scale while below speed-threshold
        ;; (a "kick to get moving, then cruise" feel common to flight
        ;; abilities), plus a hover assist that differs near ground vs.
        ;; airborne so the ability can both lift off and sustain altitude.
        execute-storm-wing! (fn [world-id owner plan]
                             (try
                               (when-let [player (query-core/get-player-by-uuid (server-fn) owner)]
                                 (let [{:keys [hover-near-ground-velocity hover-air-velocity
                                               acceleration speed-scale speed-threshold]} plan
                                       on-ground? (player-motion/is-on-ground-for-player? player)
                                       current (or (player-motion/get-velocity-for-player player)
                                                   {:x 0.0 :y 0.0 :z 0.0})
                                       look (when (raycast/available?)
                                              (raycast/player-look-vector owner))
                                       lx (double (or (:x look) 0.0))
                                       ly (double (or (:y look) 0.0))
                                       lz (double (or (:z look) 1.0))
                                       horizontal-speed (Math/sqrt (+ (Math/pow (double (:x current)) 2)
                                                                       (Math/pow (double (:z current)) 2)))
                                       boosted? (< horizontal-speed (double speed-threshold))
                                       thrust (* (double acceleration)
                                                 (if boosted? (double speed-scale) 1.0))
                                       vy (if on-ground?
                                            (double hover-near-ground-velocity)
                                            (max (double (:y current)) (double hover-air-velocity)))]
                                   (boolean
                                    (player-motion/set-velocity-for-player!
                                     player
                                     (+ (double (:x current)) (* thrust lx))
                                     (+ vy (* thrust ly 0.2))
                                     (+ (double (:z current)) (* thrust lz))))))
                               (catch Exception e
                                 (log/warn "Failed to apply storm-wing:" (ex-message e))
                                 false)))
        ;; Conservative implementations (2026-08-17 追加会话, see
        ;; docs/04-systems/COMBAT_VFX_PLATFORM_GAPS.md 节 C-2): each only
        ;; implements the well-defined "deal damage to nearby/targeted
        ;; entities" core the skill's own valid? already validates. Visual
        ;; behavior (traveling missiles, scatter spread) and light-shield's
        ;; damage-absorption (needs a damage-pipeline interception hook that
        ;; doesn't exist anywhere in this codebase) are deliberately not
        ;; implemented -- see the doc for why those need design input this
        ;; session has no authority to invent.
        execute-light-shield! (fn [world-id owner plan]
                                (try
                                  (when-let [player (query-core/get-player-by-uuid (server-fn) owner)]
                                    (let [{:keys [touch-damage touch-radius front-cone-degrees]} plan
                                          origin {:x (.getX player) :y (.getY player) :z (.getZ player)}
                                          look (when (raycast/available?)
                                                 (raycast/player-look-vector owner))
                                          lx (double (or (:x look) 0.0))
                                          ly (double (or (:y look) 0.0))
                                          lz (double (or (:z look) 1.0))
                                          victims (aoe-victims! world-id owner
                                                                 (:x origin) (:y origin) (:z origin)
                                                                 touch-radius)
                                          targets (filter #(within-cone? origin lx ly lz
                                                                          (/ (double front-cone-degrees) 2.0) %)
                                                           victims)]
                                      (doseq [{:keys [uuid]} targets]
                                        (entity-damage/apply-direct-damage!
                                         world-id uuid (double touch-damage) :generic
                                         {:attacker-uuid owner}))
                                      true))
                                  (catch Exception e
                                    (log/warn "Failed to apply light-shield touch damage:" (ex-message e))
                                    false)))
        ;; Owner-keyed fire-interval throttle. combat-core drives this once
        ;; per real tick (:pulse, :period-ticks 1); electron-missile's own
        ;; fire-interval/spawn-interval describe a slower cadence than that,
        ;; so the executor counts ticks itself and only actually fires when
        ;; the interval elapses -- an instant hit on the nearest target in
        ;; range rather than a traveling homing projectile.
        electron-missile-state (atom {})
        execute-electron-missile! (fn [world-id owner plan]
                                   (try
                                     (let [{:keys [damage seek-range fire-interval]} plan
                                           fire-interval (long (max 1 (long (or fire-interval 8))))
                                           elapsed (inc (long (or (:ticks (get @electron-missile-state owner)) 0)))]
                                       (if (< elapsed fire-interval)
                                         (do (swap! electron-missile-state assoc owner {:ticks elapsed})
                                             true)
                                         (do (swap! electron-missile-state assoc owner {:ticks 0})
                                             (when-let [player (query-core/get-player-by-uuid (server-fn) owner)]
                                               (let [origin {:x (.getX player) :y (.getY player) :z (.getZ player)}
                                                     target (first (sort-by-distance
                                                                    origin
                                                                    (aoe-victims! world-id owner
                                                                                  (:x origin) (:y origin) (:z origin)
                                                                                  seek-range)))]
                                                 (when target
                                                   (entity-damage/apply-direct-damage!
                                                    world-id (:uuid target) (double damage) :electric
                                                    {:attacker-uuid owner}))))
                                             true)))
                                     (catch Exception e
                                       (log/warn "Failed to apply electron-missile:" (ex-message e))
                                       false)))
        execute-scatter-bomb! (fn [world-id owner plan]
                                (try
                                  (when-let [player (query-core/get-player-by-uuid (server-fn) owner)]
                                    (let [{:keys [ball-count auto-aim-radius damage]} plan
                                          origin {:x (.getX player) :y (.getY player) :z (.getZ player)}
                                          targets (->> (aoe-victims! world-id owner
                                                                      (:x origin) (:y origin) (:z origin)
                                                                      auto-aim-radius)
                                                       (sort-by-distance origin)
                                                       (take (long (or ball-count 0))))]
                                      (doseq [{:keys [uuid]} targets]
                                        (entity-damage/apply-direct-damage!
                                         world-id uuid (double damage) :electric
                                         {:attacker-uuid owner}))
                                      true))
                                  (catch Exception e
                                    (log/warn "Failed to apply scatter-bomb:" (ex-message e))
                                    false)))
        ;; directed-shock ("Directed Shock" / 定向冲力): "seize the counter-
        ;; force from a punch and redirect it into the target, making the
        ;; punch more powerful" -- a forward push along the caster's look
        ;; direction, not a pull. knockback-scale's sign (-0.7 in content)
        ;; only makes sense combined with the *existing* velocity term below,
        ;; matching this file's other velocity executors (vec-accel,
        ;; mag-movement, storm-wing): damp/invert whatever momentum the
        ;; target already has, then add a fresh directional impulse. This
        ;; formula is inferred from the field names and the flavor text, not
        ;; verified against a reference implementation -- if in-game testing
        ;; shows this pulling instead of pushing, start here.
        execute-knockback! (fn [world-id owner plan]
                            (try
                              (let [{:keys [target impulse knockback-y-adjust knockback-scale]} plan
                                    entity (entity-motion/resolve-entity (server-fn) world-id target)
                                    current (or (entity-motion/get-velocity-for-entity entity)
                                                {:x 0.0 :y 0.0 :z 0.0})
                                    look (when (raycast/available?)
                                           (raycast/player-look-vector owner))
                                    lx (double (or (:x look) 0.0))
                                    lz (double (or (:z look) 1.0))]
                                (boolean
                                 (and entity
                                      (entity-motion/set-velocity-for-entity!
                                       entity
                                       (+ (* (double (:x current)) knockback-scale) (* lx impulse))
                                       (+ (* (double (:y current)) knockback-scale)
                                          (* impulse knockback-y-adjust))
                                       (+ (* (double (:z current)) knockback-scale) (* lz impulse))))))
                              (catch Exception e
                                (log/warn "Failed to apply knockback:" (ex-message e))
                                false)))
        execute-vec-accel! (fn [world-id owner plan]
                             (let [q (:query-result plan)
                                   velocity (:initial-velocity q)]
                               (when (and world-id (map? q) (map? velocity))
                                 (let [player (query-core/get-player-by-uuid (server-fn) owner)]
                                   (and (player-motion/set-velocity-for-player!
                                         player (:x velocity) (:y velocity) (:z velocity))
                                        (when player (.resetFallDistance player)))))))
        execute-mag-movement! (fn [world-id owner plan]
                                (let [q (:query-result plan)
                                      target (select-keys q [:target-x :target-y :target-z])]
                                  (when (and world-id
                                             (every? number? (vals target)))
                                    (let [player (query-core/get-player-by-uuid (server-fn) owner)]
                                      (when player
                                        (let [current (or (player-motion/get-velocity-for-player player)
                                                          {:x 0.0 :y 0.0 :z 0.0})
                                              dx (- (double (:target-x target)) (.getX player))
                                              dy (- (double (:target-y target)) (.getY player))
                                              dz (- (double (:target-z target)) (.getZ player))
                                          dist (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))
                                          scale (if (> dist 1.0e-6) dist 1.0)
                                          a (double (:acceleration plan))]
                                          (player-motion/set-velocity-for-player!
                                           player
                                           (+ (double (:x current)) (* a (/ dx scale)))
                                           (+ (double (:y current)) (* a (/ dy scale)))
                                           (+ (double (:z current)) (* a (/ dz scale))))))))))
        execute-flashing! (fn [world-id owner plan]
                            (let [q (:query-result plan)
                                  {:keys [to-x to-y to-z]} q]
                              (when (and world-id (every? number? [to-x to-y to-z]))
                                (boolean (teleportation/teleport-player!
                                          (server-fn) owner world-id to-x to-y to-z)))))
        execute-mag-manip! (fn [world-id _owner plan]
                             (let [q (:query-result plan)
                                   entity-uuid (:entity-uuid q)
                                   position (:position q)
                                   target (:throw-target q)]
                               (when (and world-id entity-uuid (map? position) (map? target)
                                          (every? number? (map position [:x :y :z]))
                                           (every? number? (map target [:x :y :z]))))
                                 (let [dx (- (double (:x target)) (double (:x position)))
                                       dy (- (double (:y target)) (double (:y position)))
                                       dz (- (double (:z target)) (double (:z position)))
                                       len (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))]
                                   (when (pos? len)
                                     (entity-motion/set-velocity-for-entity!
                                      (entity-motion/resolve-entity (server-fn) world-id entity-uuid)
                                      (* (double (:throw-speed plan)) (/ dx len))
                                      (* (double (:throw-speed plan)) (/ dy len))
                                       (* (double (:throw-speed plan)) (/ dz len)))))))]
    {:spawn-lightning! (fn spawn-lightning-adapter!
                         ([world-id x y z] (spawn-lightning-adapter! world-id x y z false))
                         ([world-id x y z visual-only?]
                          (try
                            (when-let [^MinecraftServer server (server-fn)]
                              (when-let [^ServerLevel level (resolve-level server resolve-level-fn world-id)]
                                (spawn-lightning! level x y z (boolean visual-only?))))
                            (catch Exception e
                              (log/warn "Failed to spawn lightning:" (ex-message e))
                              false))))
     :create-explosion! (fn create-explosion-adapter!
                          ([world-id x y z radius fire?]
                           (create-explosion-adapter!
                            world-id x y z radius fire?
                            {:terrain? (boolean fire?)}))
                          ([world-id x y z radius fire? opts]
                           (try
                             (when-let [^MinecraftServer server (server-fn)]
                               (when-let [^ServerLevel level
                                          (resolve-level server resolve-level-fn world-id)]
                                 (let [source
                                       (when-let [attacker-uuid
                                                  (:attacker-uuid opts)]
                                         (get-entity-by-uuid-fn
                                          level attacker-uuid))]
                                   (boolean
                                    (create-explosion!
                                     level source x y z radius
                                     (boolean fire?)
                                     (boolean (:terrain? opts)))))))
                             (catch Exception e
                               (log/warn "Failed to create explosion:" (ex-message e))
                               false))))
     :spawn-projectile! (fn [world-id projectile-spec]
                          (try
                            (when-let [^MinecraftServer server (server-fn)]
                              (when-let [^ServerLevel level (resolve-level server resolve-level-fn world-id)]
                                (spawn-projectile! level projectile-spec)))
                            (catch Exception e
                              (log/warn "Failed to spawn projectile:" (ex-message e))
                              {:success? false})))
     :find-entities-in-radius find-entities-in-radius!
     :find-entities-in-aabb (fn [world-id min-x min-y min-z max-x max-y max-z]
                              (try
                                (when-let [^MinecraftServer server (server-fn)]
                                  (when-let [^ServerLevel level (resolve-level server resolve-level-fn world-id)]
                                    ((:entities-in-aabb (core))
                                      level
                                      min-x min-y min-z max-x max-y max-z
                                      get-entities-in-aabb
                                      resolve-entity-id-fn
                                      multipart-entity?)))
                                (catch Exception e
                                  (log/warn "Failed to find entities in AABB:" (ex-message e))
                                  [])))
     :find-blocks-in-radius (fn [world-id x y z radius block-predicate]
                              (try
                                (when-let [^MinecraftServer server (server-fn)]
                                  (when-let [^ServerLevel level (resolve-level server resolve-level-fn world-id)]
                                    ((:find-blocks-in-radius-in-level (core))
                                      level x y z radius block-predicate block-id-fn)))
                                (catch Exception e
                                  (log/warn "Failed to find blocks:" (ex-message e))
                                  [])))
     :play-sound! (fn [world-id x y z sound-id source volume pitch]
                    (try
                      (when-let [^MinecraftServer server (server-fn)]
                        (when-let [^ServerLevel level (resolve-level server resolve-level-fn world-id)]
                          (boolean ((:play-sound-in-level! (core)) level x y z sound-id source volume pitch))))
                      (catch Exception e
                        (log/warn "Failed to play world sound:" (ex-message e))
                        false)))
     :trigger-behavior-hit! (fn [world-id entity-uuid]
                             (try
                               (when-let [^MinecraftServer server (server-fn)]
                                 (when-let [^ServerLevel level (resolve-level server resolve-level-fn world-id)]
                                   (boolean ((:trigger-behavior-hit-in-level! (core)) level entity-uuid get-entity-by-uuid-fn))))
                               (catch Exception e
                                 (log/warn "Failed to trigger behavior hit:" (ex-message e))
                                 false)))
     :discard-entity-by-uuid! (fn [world-id entity-uuid]
                                (try
                                  (when-let [^MinecraftServer server (server-fn)]
                                    (when-let [^ServerLevel level (resolve-level server resolve-level-fn world-id)]
                                      (when-let [^Entity entity (get-entity-by-uuid-fn level (str entity-uuid))]
                                        (.discard entity)
                                        true)))
                                      (catch Exception e
                                        (log/warn "Failed to discard entity:" (ex-message e))
                                    false)))
     :execute-vec-accel! execute-vec-accel!
     :execute-mag-movement! execute-mag-movement!
     :execute-flashing! execute-flashing!
     :execute-mag-manip! execute-mag-manip!
     :execute-thunder-clap! execute-thunder-clap!
     :execute-blood-retrograde! execute-blood-retrograde!
     :execute-plasma-cannon! execute-plasma-cannon!
     :execute-meltdowner! execute-meltdowner!
     :execute-mine-ray! execute-mine-ray!
     :execute-storm-wing! execute-storm-wing!
     :execute-light-shield! execute-light-shield!
     :execute-electron-missile! execute-electron-missile!
     :execute-scatter-bomb! execute-scatter-bomb!
     :execute-knockback! execute-knockback!
     :execute-vec-deviation! (fn [world-id _owner plan]
                               (execute-vec-deviation-adapter! server-fn world-id plan))}))

(defn install-world-effects!
  [world-effects label]
  (when-let [fw-atom (fw/fw-atom)]
    (platform/install-adapter! fw-atom :world-effects world-effects))
  nil)
