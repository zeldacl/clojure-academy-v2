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
            [cn.li.mcmod.platform.entity :as entity]
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
        execute-flashing! (fn [world-id owner plan]
                            (let [q (:query-result plan)
                                  {:keys [to-x to-y to-z]} q]
                              (when (and world-id (every? number? [to-x to-y to-z]))
                                (boolean (teleportation/teleport-player!
                                          (server-fn) owner world-id to-x to-y to-z)))))
        ;; shift-teleport isn't a player teleport -- place/drop the held item
        ;; at the query's raycasted point, then damage whatever it found
        ;; intersecting the caster->point line. The hand-item mutation
        ;; (main-hand placeable check, place-at-hit, consume/drop) needs a
        ;; resolved Player object, which query-port fns in combat_runtime.clj
        ;; never have -- only a uuid. query-core/get-player-by-uuid is
        ;; platform-src-only (tied to MinecraftServer), so that resolution,
        ;; and everything downstream of it, has to live here rather than in
        ;; the query. If nothing is placed/dropped (no placeable/droppable
        ;; item in hand), the cast still consumes its already-charged cost --
        ;; matches every other executor in this file that can silently no-op
        ;; after :require already let the cost deduction through.
        execute-shift-teleport! (fn [world-id owner plan]
                                 (try
                                   (when-let [player (query-core/get-player-by-uuid (server-fn) owner)]
                                     (when (entity/player-main-hand-placeable-block? player)
                                       (let [{:keys [query-result damage]} plan
                                             {:keys [hit-block-x hit-block-y hit-block-z
                                                     place-x place-y place-z face
                                                     drop-x drop-y drop-z target-entities]} query-result
                                             creative? (boolean (entity/player-creative? player))
                                             can-place? (and (block-manipulation/available?)
                                                              (not (block-manipulation/block-collidable?
                                                                    world-id place-x place-y place-z))
                                                              (block-manipulation/can-break-block?
                                                               owner world-id hit-block-x hit-block-y hit-block-z))
                                             place-result (when can-place?
                                                            (entity/player-place-main-hand-block-at-hit!
                                                             player world-id place-x place-y place-z face))
                                             dropped? (boolean
                                                       (when-not can-place?
                                                         (if creative?
                                                           (entity/player-spawn-main-hand-item-copy-at!
                                                            player 1 drop-x drop-y drop-z)
                                                           (entity/player-drop-main-hand-item-at!
                                                            player 1 drop-x drop-y drop-z))))
                                             consumed? (boolean
                                                        (or creative?
                                                            (if can-place?
                                                              (entity/player-consume-main-hand-item! player 1)
                                                              dropped?)))]
                                         (when consumed?
                                           (doseq [{:keys [uuid]} target-entities]
                                             (when uuid
                                               (entity-damage/apply-direct-damage!
                                                world-id uuid (double damage) :magic
                                                {:attacker-uuid owner}))))
                                         (boolean (or (:placed? place-result) dropped?)))))
                                   (catch Exception e
                                     (log/warn "Failed to apply shift-teleport:" (ex-message e))
                                     false)))]
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
     :spawn-entity! (fn [world-id owner entity-type _position velocity life-ticks]
                      (try
                        (when-let [^MinecraftServer server (server-fn)]
                          (when-let [player (query-core/get-player-by-uuid server (str owner))]
                            (let [v (or velocity {})
                                  vx (double (or (:x v) 0.0))
                                  vy (double (or (:y v) 0.0))
                                  vz (double (or (:z v) 0.0))
                                  speed (Math/sqrt (+ (* vx vx) (* vy vy) (* vz vz)))]

                               (entity/player-spawn-tracked-entity-by-id!
                                player (str entity-type) speed life-ticks))))
                        (catch Exception e
                          (log/warn "Failed to spawn neutral entity:" (ex-message e))
                          false)))
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
     :configure-entity! (fn [world-id entity-uuid velocity add-tags projectile-damage]
                          (try
                            (when-let [^MinecraftServer server (server-fn)]
                              (when-let [entity (entity-motion/resolve-entity
                                                 server world-id (str entity-uuid))]
                                (let [velocity (or velocity [0.0 0.0 0.0])
                                      [vx vy vz] (map double velocity)]
                                  (when (= 3 (count velocity))
                                    (entity-motion/set-velocity-for-entity!
                                     entity vx vy vz)
                                    (when (number? projectile-damage)
                                      (entity-motion/set-projectile-damage-for-entity!
                                       entity (double projectile-damage)))
                                    (doseq [tag (or add-tags [])]
                                      (when (string? tag)
                                        (entity-motion/add-tag-for-entity! entity tag)))
                                    true))))
                            (catch Exception e
                              (log/warn "Failed to configure entity:" (ex-message e))
                              false)))
     :teleport-entity! (fn [world-id entity-uuid x y z]
                         (try
                           (when-let [^MinecraftServer server (server-fn)]
                             (when-let [entity (entity-motion/resolve-entity
                                                server world-id (str entity-uuid))]
                               (boolean (entity-motion/set-position-for-entity!
                                         entity (double x) (double y) (double z)))))
                           (catch Exception e
                             (log/warn "Failed to teleport entity:" (ex-message e))
                             false)))
     :add-entity-velocity! (fn [world-id entity-uuid x y z]
                             (try
                               (when-let [^MinecraftServer server (server-fn)]
                                 (when-let [entity (entity-motion/resolve-entity
                                                    server world-id (str entity-uuid))]
                                   (boolean (entity-motion/add-velocity-for-entity!
                                             entity (double x) (double y) (double z)))))
                               (catch Exception e
                                 (log/warn "Failed to add entity velocity:" (ex-message e))
                                 false)))
     :execute-flashing! execute-flashing!
     :execute-knockback! execute-knockback!
     :execute-shift-teleport! execute-shift-teleport!
     }))

(defn install-world-effects!
  [world-effects label]
  (when-let [fw-atom (fw/fw-atom)]
    (platform/install-adapter! fw-atom :world-effects world-effects))
  nil)
