(ns cn.li.ac.ability.service.combat-runtime
  "AC composition root for the neutral combat engine.

   Combat Core itself never knows about AC, Minecraft or VFX."
  (:require [cn.li.combat.registry :as registry]
            [cn.li.combat.compiler :as compiler]
            [cn.li.combat.skill-runtime :as combat-skill-runtime]
            [cn.li.combat.reactions :as combat-reactions]
            [cn.li.combat.deferred :as deferred]
            [cn.li.combat.runtime :as combat]
            [cn.li.ac.ability.service.runtime-store :as runtime-store]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.ac.ability.model.preset :as preset-data]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.service.command-runtime :as command-runtime]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.model.ability :as ability-model]
            [cn.li.ac.ability.service.combat-catalog :as combat-catalog]
            [cn.li.ac.ability.service.combat-sessions :as combat-sessions]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.registry.event :as ability-event]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.mcmod.runtime.capabilities :as capabilities]
            [cn.li.ac.ability.config :as ability-config]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.entity-motion :as entity-motion]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.ac.achievement.dispatcher :as achievement-dispatcher]
            [cn.li.mcmod.platform.block-manipulation :as block-manipulation]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as position]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.server.platform-bridge :as server-bridge]
            [cn.li.mcmod.runtime.seeded-rng :as seeded-rng]
            [cn.li.mcmod.runtime.vfx-contract :as vfx-contract]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.mcmod.block.multiblock-core :as multiblock]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.framework.platform :as platform]
            [cn.li.mcmod.runtime.combat-contract :as contract]))

(defonce ^:private engine* (atom nil))
(defonce ^:private catalog* (atom nil))
(defonce ^:private world-effect-handler* (atom nil))
(defonce ^:private result-sink* (atom nil))
(defonce ^:private edn-host-capabilities-installed? (atom false))
;; The authoritative source for `:now-tick` when a caller does not supply one.
;; `tick!` below updates this from the real server tick every call; intents
;; dispatched between full tick-loop passes read the last observed value.
(defonce ^:private last-known-tick* (atom 0))
;; Monotonic tiebreaker for activation-seed generation: (owner, ability-id,
;; tick) alone can repeat within a single tick, so a plain hash of those three
;; would make `random/*` behave identically for repeated activations. Mixing
;; in this counter and wall-clock nanos gives every activation its own seed.
(defonce ^:private activation-seed-counter* (atom 0))
(declare owner-state resolve-slot execute-world-effects! finalize-result! publish-result!)

(defn- generate-activation-seed
  "Produce a fresh per-activation RNG seed. Never deterministic across
  activations for the same owner/ability -- see `dispatch-intent!`'s only
  caller, `execute-combat-intent!`, which threads the result into both the
  program context and the session store so every reader of
  :activation-seed observes the same real value instead of independently
  recomputing a constant fallback."
  [owner ability-id tick]
  (hash [owner ability-id tick
         (swap! activation-seed-counter* unchecked-inc)
         (System/nanoTime)]))

(defn- valid-damage-world-effect?
  [effect]
  (let [request (:request effect)
        base (:base request)]
    (and (= :damage (:type effect))
         (:source request) (:target request)
         (not= (str (:source request)) (str (:target request)))
         (number? base) (Double/isFinite (double base))
         (pos? (double base)) (<= (double base) 10000.0))))

(defn- execute-damage-effects!
  "Execute only validated neutral damage effects emitted by reactions."
  [owner result]
  (let [effects (vec (filter valid-damage-world-effect?
                             (:world-effects result)))]
    (when (= (count effects) (count (:world-effects result)))
      (execute-world-effects! owner (assoc result :world-effects effects)))))

(defn- damage-output?
  [result]
  (and (seq (:world-effects result))
       (some valid-damage-world-effect? (:world-effects result))))

(defn- horizontal-yaw-degrees [x z]
  (- (Math/toDegrees (Math/atan2 (double x) (double z)))))

(defn- attacker-front?
  "Resolve Light Shield's horizontal-yaw cone at the AC boundary.

   Missing entity geometry fails closed. Platform damage sources may provide
   an already validated neutral `:attacker-front?` fact for tests or special
   damage types; ordinary entity damage is resolved through the neutral motion
   and raycast ports here, before Combat Core sees the request."
  [player-id attacker-id damage-source]
  (cond
    (and (map? damage-source) (contains? damage-source :attacker-front?))
    (boolean (:attacker-front? damage-source))

    (nil? attacker-id) true

    (not (and (raycast/available?) (entity-motion/available?)))
    false

    :else
    (try
      (let [position (raycast/player-position (str player-id))
            look (raycast/player-look-vector (str player-id))
            world-id (:world-id position)
            attacker-pos (entity-motion/entity-position world-id (str attacker-id))]
        (boolean
         (when (and (map? position) (map? look) (map? attacker-pos))
           (let [dx (- (double (:x attacker-pos)) (double (:x position)))
                 dz (- (double (:z attacker-pos)) (double (:z position)))
                 player-yaw (horizontal-yaw-degrees (:x look) (:z look))
                 target-yaw (horizontal-yaw-degrees dx dz)
                 diff (mod (Math/abs (double (- target-yaw player-yaw))) 360.0)]
             (< diff (skill-config/tunable-double
                      :light-shield :combat.front-cone-degrees))))))
      (catch Exception _ false))))

(defn- mark-policy-for
  "Return the declarative policy for a neutral mark type.

   Policies are authored by effects/abilities in EDN; this lookup deliberately
   does not name a concrete skill so every mark producer can reuse the same
   host action and VFX bridge."
  [mark-type]
  (some (fn [[_ ability]]
          (some #(when (= mark-type (:mark-type %)) %)
                (:mark-policies ability)))
        (get-in (combat-catalog/catalog) [:combat :abilities])))

(defn- mark-vfx-signal
  [request policy target-position duration]
  (when (and policy target-position)
    (when-let [vfx (:vfx policy)]
    (let [offset (or (get-in vfx [:offset :vec3]) [0.0 0.0 0.0])
          [x y z] (mapv double (or (:vec3 target-position)
                                   [(:x target-position)
                                    (:y target-position)
                                    (:z target-position)]))
          [ox oy oz] (mapv double offset)
          payload (assoc (:payload vfx)
                         :position [ (+ x ox) (+ y oy) (+ z oz)]
                         :ttl-ticks (long duration)
                         :seed (long (or (:activation-seed request) 0)))]
        (vfx-contract/signal
         {:op :spawn
          :effect-id (:effect-id vfx)
          :instance-key [:entity-mark (:mark-type policy) (str (:target request))]
          :owner (:owner request)
          :world-id (:world-id request)
          :event-seq (long (or (:server-tick request) 1))
          :seed (long (or (:activation-seed request) 0))
          :event :spawn
          :params payload})))))

;; `:runtime-interop :get-block-entity-at` is a neutral AC host adapter used
;; by the generic energy query/action ports below. It returns an opaque tile
;; only inside this composition root; no tile or Minecraft object crosses the
;; Combat Core contract.
(defn- block-entity-at
  [world-id x y z]
  (when-let [fw-atom (fw/fw-atom)]
    (platform/call-adapter fw-atom :runtime-interop :get-block-entity-at world-id x y z)))

(defn- held-item-at
  [owner]
  (when-let [fw-atom (fw/fw-atom)]
    (platform/call-adapter-optional fw-atom :runtime-interop
                                    :get-player-main-hand-item (str owner))))

(defn- resolve-energy-tile
  "Resolve a neutral block position to the controller tile when the platform
  exposes a multiblock controller.  The query/action boundary never returns
  this tile object; it is used only inside the AC host function."
  [world-id block-pos]
  (when (and world-id (vector? block-pos) (= 3 (count block-pos)))
    (let [[x y z] (map long block-pos)
          tile (block-entity-at world-id x y z)]
      (or
       (try
         (when-let [level (platform-be/be-get-world-safe tile)]
           (let [block-id (platform-be/get-block-id tile)
                 controller (when (and block-id)
                              (multiblock/resolve-controller-pos
                               {:world level :pos (position/create-block-pos x y z)
                                :block-id block-id}))]
             (when controller
               (world/get-tile-entity level controller))))
         (catch Throwable _ nil))
       tile))))

(defn- energy-target-result
  [world-id hit]
  (let [block-pos (or (when (map? (:block-position hit))
                        (let [{:keys [x y z]} (:block-position hit)]
                          (when (every? number? [x y z])
                            [(long (Math/floor (double x)))
                             (long (Math/floor (double y)))
                             (long (Math/floor (double z)))])))
                      (:block-position hit)
                      (when (and (= :block (:hit-type hit))
                                 (every? number? [(:x hit) (:y hit) (:z hit)]))
                        [(long (Math/floor (double (:x hit))))
                         (long (Math/floor (double (:y hit))))
                         (long (Math/floor (double (:z hit))))]))
        tile (resolve-energy-tile world-id block-pos)
        supported? (boolean (and tile
                                 (or (energy/is-node-supported? tile)
                                     (energy/is-receiver-supported? tile))))]
    {:chargeable? supported?
     :block-pos block-pos
     :block-bounds (when (and tile block-pos)
                     (try
                       (let [level (platform-be/be-get-world-safe tile)
                             block-id (platform-be/get-block-id tile)]
                         (when (and level block-id)
                           (multiblock/structure-bounds
                            {:world level
                             :pos (apply position/create-block-pos block-pos)
                             :block-id block-id})))
                       (catch Throwable _ nil)))}))

(defn initialize!
  ([] (initialize! {}))
  ([{:keys [owner-state-fn query-port now-tick ability-resolver damage-pipeline
            domain-event-handler]}]
   (or @engine*
       (let [catalog (compiler/compile-all!)]
         (when-not (registry/frozen?) (registry/freeze!))
         (reset! catalog* catalog)
         (reset! engine* (combat/create-engine
                           {:catalog catalog
                            :initial-owner-state (or owner-state-fn owner-state)
                            ;; The v1 engine's own :op-keyed program-node
                            ;; interpreter (which :query-port feeds) has no
                            ;; registered providers -- every ability is EDN v2,
                            ;; executed entirely through combat-skill-runtime's
                            ;; VM instead. :query-port only remains a pass-through
                            ;; seam for a caller-supplied port map (tests).
                            :query-port (or query-port {})
                            ;; Explicit `(or now-tick ...)`: create-engine's :or
                            ;; default only fires when the key is absent, and
                            ;; this map always includes :now-tick (possibly
                            ;; nil when initialize! is called with {}), so an
                            ;; unguarded pass-through here silently binds the
                            ;; engine's now-tick to nil.
                            :now-tick (or now-tick (fn [] @last-known-tick*))
                            :ability-resolver (or ability-resolver resolve-slot)
                            :domain-event-handler domain-event-handler
                            :damage-pipeline damage-pipeline}))
         (when-not @world-effect-handler*
           (reset! world-effect-handler*
                   (fn [owner effect]
                     (if-let [handler (contract/host-port :world-effect)]
                       (handler owner effect)
                       (case (:type effect)
                         :damage
                         (let [{:keys [request]} effect
                               {:keys [world-id target base type source]} request]
                           {:status (if (and world-id target
                                              (entity-damage/available?)
                                              (entity-damage/apply-direct-damage!
                                               world-id target base type
                                               {:attacker-uuid source}))
                                        :applied :failed)
                            :effect effect})
                         :damage-aoe
                         (let [{:keys [world-id origin radius amount damage-type]} effect
                               {:keys [x y z]} origin]
                            {:status (if (and world-id origin
                                              (entity-damage/available?)
                                              (entity-damage/apply-aoe-damage!
                                               world-id x y z (double radius)
                                               (double amount) damage-type false))
                                        :applied :failed)
                            :effect effect})
                         :damage-targets
                         (let [{:keys [world-id targets amount damage-type source]} effect
                               amount (double amount)
                               target-ids (->> (or targets [])
                                               (map #(or (:uuid %)
                                                         (:entity-id %)
                                                         (:target-id %)
                                                         %))
                                               (filter string?)
                                               distinct
                                               sort
                                               (take 64))
                               hits (if (and world-id
                                              (Double/isFinite amount)
                                              (pos? amount)
                                              (entity-damage/available?))
                                      (reduce (fn [n target-id]
                                                (if (entity-damage/apply-direct-damage!
                                                     world-id target-id amount damage-type
                                                     {:attacker-uuid source})
                                                  (inc n)
                                                  n))
                                              0 target-ids)
                                      0)]
                           {:status (cond
                                      (= hits (count target-ids)) :applied
                                      (pos? hits) :partial
                                      :else :failed)
                            :hits hits
                            :target-count (count target-ids)
                            :effect effect})
                         :lightning
                         (let [{:keys [world-id origin visual-only?]} effect
                               {:keys [x y z]} (if (map? origin) origin {})
                               valid? (and world-id
                                            (every? #(and (number? %) (Double/isFinite (double %)))
                                                    [x y z])
                                            (world-effects/available?))]
                           {:status (if (and valid?
                                              (world-effects/spawn-lightning!
                                               world-id (double x) (double y) (double z)
                                               (boolean visual-only?)))
                                      :applied
                                      :failed)
                            :effect effect})
                         :teleport-approved-target
                         {:status :unhandled
                          :reason :unsupported-teleport-mode
                          :effect effect}
                         :knockback
                         (let [{:keys [world-id target movement]} effect
                               {:keys [impulse knockback-y-adjust knockback-scale]} movement
                               finite? #(and (number? %) (Double/isFinite (double %)))
                               valid? (and world-id target
                                            (finite? impulse) (<= 0.0 (double impulse) 4.0)
                                            (finite? knockback-y-adjust) (<= -2.0 (double knockback-y-adjust) 2.0)
                                            (finite? knockback-scale) (<= -2.0 (double knockback-scale) 2.0)
                                            (world-effects/available?))
                               plan {:target target
                                     :impulse (double (or impulse 0.0))
                                     :knockback-y-adjust (double (or knockback-y-adjust 0.0))
                                     :knockback-scale (double (or knockback-scale 1.0))}]
                           {:status (if (and valid?
                                              (world-effects/execute-knockback!
                                               world-id owner plan))
                                      :applied
                                      :failed)
                            :effect effect})
                         {:status :unhandled
                          :reason :missing-world-effect-host-port
                          :effect effect}))))
         @engine*)))))

(defn engine [] (or @engine* (initialize!)))
(defn catalog [] @catalog*)
(defn content-hash [] (:content-hash @catalog*))
(defn domain-state [] (combat/domain-state (engine)))
(defn register-provider! [provider]
  (registry/register-provider! provider))

(defn- server-session-id []
  (runtime-hooks/player-state-server-session-id))

(defn owner-state
  "Project AC's authoritative player state into Combat Core's neutral view.
   Combat Core never sees the original AC store shape." 
  [owner]
  (let [state (runtime-store/get-player-state (server-session-id) (str owner))
        resource-data (:resource-data state)
        cooldown-data (:cooldown-data state)
        position (when (raycast/available?)
                   (raycast/player-position (str owner)))]
    {:resources {:cp (double (or (:cur-cp resource-data) 0.0))
                 :max-cp (double (or (:max-cp resource-data) 0.0))
                 :overload (double (or (:cur-overload resource-data) 0.0))}
     :active-abilities (if-let [session (combat-sessions/session (str owner))]
                         #{(:ability-id session)}
                         #{})
     ;; {ability-id {sub-id ticks}} -- keyed by BOTH ctrl-id and sub-id, unlike
     ;; the flattened {ctrl-id ticks} this used to project, which silently
     ;; collapsed an ability with more than one named cooldown onto a single
     ;; value. Combat Core's cooldown gate (skill_runtime/dispatch!) reads
     ;; this shape directly.
     :cooldowns (reduce (fn [acc [[ctrl-id sub-id] value]]
                          (assoc-in acc [ctrl-id sub-id]
                                    (long (or (:ticks value) 0))))
                        {} cooldown-data)
     :ability-data (:ability-data state)
     :preset-data (:preset-data state)
     :position (when (map? position)
                 [(:x position) (:y position) (:z position)])
     :world-id (:world-id position)}))

(defn resolve-slot
  "Resolve a client slot only against the server-authoritative preset." 
  [owner intent]
  (when-let [state (runtime-store/get-player-state (server-session-id) (str owner))]
    (let [slots (preset-data/get-active-slots (:preset-data state))
          slot (nth slots (long (:slot intent)) nil)]
      (when (and (vector? slot) (= 2 (count slot)))
        (skill-query/get-skill-by-controllable (first slot) (second slot))))))
(defn- commit-state-patch! [owner patches]
  (let [session-id (server-session-id)
        commands (keep (fn [[kind key amount]]
                         (case kind
                           :resource
                           (cond
                             (= key :cp)
                             {:command :consume-resource
                              :cp (- (double amount))}
                             (= key :overload)
                             {:command :consume-resource
                              :overload (- (double amount))}
                             :else nil)
                           :ability-exp
                           {:command :add-skill-exp
                            :skill-id key
                            :amount (double amount)
                            :source :combat-core}
                           :cooldown
                           (let [ticks (max 0 (long (- amount @last-known-tick*)))]
                             {:command :set-cooldown
                              :ctrl-id key
                              :sub-id :main
                              :ticks ticks})
                           nil))
                       patches)]
    (when (seq commands)
      (command-runtime/run-commands-in-session! session-id owner commands))))

(defn- edn-owner-patch-commands
  "Translate the neutral owner-patch contract into AC reducer commands.

  Core never knows AC's player-state layout; this adapter is the only place
  where neutral paths become persistent state transitions.

  Every entry in every owner-patch action is translated independently: an
  action carrying both a cp entry and an overload entry must commit both,
  not just the first one a scan happens to hit."
  [patch-actions]
  (mapcat (fn [{:keys [entries]}]
            (keep (fn [{:keys [path mode value]}]
                    (let [amount (when (number? value) (double value))]
                      (cond
                        (and (= mode :increment)
                             (= path [:resources :cp])
                             (some? amount))
                        {:command :consume-resource :cp (- amount)}
                        (and (= mode :increment)
                             (= path [:resources :overload])
                             (some? amount))
                        {:command :consume-resource :overload (- amount)}
                        (and (= mode :increment)
                             (= 3 (count path))
                             (= [:ability-data :skill-exps] (subvec (vec path) 0 2))
                             (keyword? (nth path 2))
                             (some? amount))
                        {:command :add-skill-exp
                         :skill-id (nth path 2)
                         :amount amount
                         :source :combat-core}
                        (and (= mode :assign)
                             (= 3 (count path))
                             (= [:cooldown-data] (subvec (vec path) 0 1))
                             (keyword? (nth path 1))
                             (keyword? (nth path 2))
                             (some? amount))
                        {:command :set-cooldown
                         :ctrl-id (nth path 1)
                         :sub-id (nth path 2)
                         :ticks (max 0 (long amount))}
                        :else nil)))
                  entries))
          (filter #(= :owner-patch (:type %)) patch-actions)))

(defn- commit-edn-owner-patches!
  [owner actions]
  (let [patch-actions (vec (filter #(= :owner-patch (:type %)) actions))
        commands (vec (edn-owner-patch-commands patch-actions))]
    (when (seq commands)
      (let [result (command-runtime/run-commands-in-session!
                    (server-session-id) owner commands)]
        [{:status (if (:success? result) :committed :failed)
          :capability :owner-patch
          :command-count (count commands)}]))))

(defn- edn-ability-id [owner intent]
  (or (:ability-id intent)
      (:ability intent)
      (some-> (resolve-slot owner intent) :id)))


(defn- activation-context
  [owner ability-id intent seed]
  (let [state (runtime-store/get-player-state (server-session-id) (str owner))
        resource-data (:resource-data state)
        position (when (raycast/available?)
                   (raycast/player-position (str owner)))
        eye (if position
              {:x (double (:x position)) :y (double (:eye-y position)) :z (double (:z position))}
              {:x 0.0 :y 65.62 :z 0.0})
        look (when (raycast/available?)
               (raycast/player-look-vector (str owner)))]
    (merge {:owner owner
            :ability-id ability-id
            :world-id (or (:world-id position) "minecraft:overworld")
            :eye-pos eye
            :look look
            :activation-seed (long seed)
            :skill-exp (double (or (get-in state [:ability-data :skill-exps ability-id])
                                   0.0))
            ;; Generic progression metadata exposed through the caster
            ;; facade.  Mine Detect uses it to select its configurable
            ;; presentation tier; it is not a skill-specific runtime hook.
            :ability-level (long (or (get-in state [:ability-data :level]) 0))
            :resources {:cp (double (or (:cur-cp resource-data) 0.0))
                        :overload (double (or (:cur-overload resource-data) 0.0))}
            :creative? (boolean (:creative? intent))}
           (:context intent))))

(defn- caster-facade
  "Schema v2 design C: the neutral capability table an EDN ability reads via
  `{:from :caster/...}` instead of `{:ref [:context ...]}` reaching straight
  into AC's own context shape. This is the ONLY place that shape is allowed
  to leak into a form combat-core sees -- change AC's context layout and
  only this function needs updating, never any EDN.

  Deliberately incomplete: `:caster/hand-item` and `:toggle/enabled?` are
  not wired yet (the former needs a new cross-platform held-item port, the
  latter needs the same session-presence check the damage-reaction path
  already computes at combat_runtime.clj ~2394). Referencing either from
  EDN fails closed (`combat-core/vm.clj`'s :from resolution throws) rather
  than silently resolving to nil; both get added in Phase 5 alongside the
  abilities that actually need them."
  [owner context]
  (let [look (or (:look context) {:x 0.0 :y 0.0 :z 1.0})
        lx (double (or (:x look) 0.0))
        ly (double (or (:y look) 0.0))
        lz (double (or (:z look) 1.0))
        length (max 1.0e-9 (Math/sqrt (+ (* lx lx) (* ly ly) (* lz lz))))
        forward {:x (/ lx length) :y (/ ly length) :z (/ lz length)}
        left-length (max 1.0e-9 (Math/sqrt (+ (* lz lz) (* lx lx))))]
    {:caster/eye (:eye-pos context)
     :caster/body (if-let [position (when (raycast/available?)
                                       (raycast/player-position (str owner)))]
                    {:x (double (:x position)) :y (double (:y position)) :z (double (:z position))}
                    {:x 0.0 :y 64.0 :z 0.0})
     :caster/eye-y (double (or (:y (:eye-pos context)) 0.0))
     :caster/aim look
     :caster/id owner
     :world/id (:world-id context)
     :caster/creative? (boolean (:creative? context))
     ;; Configured target registries are snapshotted at activation and exposed
     ;; as neutral lists.  EDN never reaches back into AC config paths.
     :targeting/normal-metal-blocks (ability-config/get-normal-metal-blocks)
     :targeting/weak-metal-blocks (ability-config/get-weak-metal-blocks)
     :targeting/metal-entities (ability-config/get-metal-entities)
     :movement/forward forward
     :movement/back {:x (- (:x forward)) :y (- (:y forward)) :z (- (:z forward))}
     :movement/left {:x (/ lz left-length) :y 0.0 :z (- (/ lx left-length))}
     :movement/right {:x (- (/ lz left-length)) :y 0.0 :z (/ lx left-length)}
     :charge/ticks (long (or (:hold-ticks context) 0))
   ;; Raw (pre-curve) mastery and RNG seed: legitimate exceptions to design
   ;; B/E folding skill-exp/seed away. Some content hands both to an AC-side
   ;; domain-event handler that isn't itself an EDN node (arc-gen's ignite/
   ;; fishing resolution) -- that handler needs the same inputs the VM's own
   ;; :expr evaluator would have used, just not through a lerp/random/* node.
   :progression/mastery (double (or (:skill-exp context) 0.0))
     :progression/level (long (or (:ability-level context) 0))
     :rng/seed (long (or (:activation-seed context) 0))}))

(defn install-ac-host-capabilities!
  "Link AC's own domain capabilities (resource/progression/energy/mark) to
   the neutral EDN host table once.

   World-facing capabilities (raycast, damage, entity motion, world effects,
   ...) are owned and registered entirely by Combat Core -- see
   `cn.li.combat.platform/install!`, called from `cn.li.ac.core.init/init`
   ahead of this function. This function only links the handful of ports
   that are legitimately AC's own domain: player resources (CP/overload),
   energy items/blocks, and entity marks (which read AC's assembled ability
   catalog for mark-policy metadata).

   Public and called from cn.li.ac.core.init/init, ahead of
   combat-catalog/initialize!, so capabilities are registered before the catalog
   ever loads (Design E precondition R9). It also still runs lazily on first
   dispatch below (compare-and-set! below makes a second call a no-op) as a
   safety net for any other entry path, but that is no longer the only time
   it runs."
  []
  (when (compare-and-set! edn-host-capabilities-installed? false true)
    (try
      (deferred/install-vfx-emitter!
       (fn [{:keys [effect-id payload owner world-id event-seq seed audience instance-key]}]
         (let [normalized (vfx-contract/signal
                           {:op :spawn
                            :effect-id effect-id
                            :instance-key (or instance-key [:delayed-beam effect-id])
                            :owner owner
                            :world-id world-id
                            :audience audience
                            :event-seq (long (or event-seq 0))
                            :seed (long (or seed 0))
                            :event :spawn
                            :params (or payload {})})]
           (publish-result!
            (finalize-result!
             owner
             {:schema-version 2
              :status :accepted
              :owner owner
              :vfx-signals [normalized]})))))
      (when-not (contains? (:queries (capabilities/snapshot)) :energy/target)
        (capabilities/register-query!
         :energy/target
         (fn [{:keys [world-id hit]} _frame]
           (energy-target-result world-id hit))))
      (when-not (contains? (:actions (capabilities/snapshot)) :entity/mark)
        (capabilities/register-action!
         :entity/mark
         (fn [{:keys [owner target mark-type duration-ticks requires-ability
                      world-id] :as request}]
           (let [owner (str owner)
                 target (str target)
                 learned? (or (nil? requires-ability)
                              (ability-model/is-learned?
                               (:ability-data (owner-state owner))
                               requires-ability))]
             (when (and (not= "nil" owner)
                        (not= "nil" target)
                        mark-type
                        learned?)
               (let [duration (long (or duration-ticks 60))
                     policy (mark-policy-for mark-type)
                     position (when world-id
                                (entity-motion/entity-position
                                 (str world-id) target))]
                 (combat/dispatch-domain-event!
                  (engine)
                  {:type :entity-mark
                   :source-player-id owner
                   :target-id target
                   :mark-type mark-type
                   :duration duration
                   :tick (long @last-known-tick*)})
                 {:status :applied
                  :vfx-signals (vec (keep #(mark-vfx-signal request policy
                                                             position duration)
                                          [policy]))}))))))
      (when-not (contains? (:actions (capabilities/snapshot)) :energy/charge)
        (capabilities/register-action!
         :energy/charge
         (fn [{:keys [owner world-id mode target amount]}]
           (let [amount (double (or amount 0.0))
                 applied? (and (pos? amount)
                               (Double/isFinite amount)
                               (case mode
                                 :item (let [stack (held-item-at owner)]
                                         (when (and stack
                                                    (energy/is-energy-item-supported? stack))
                                           (< (double (energy/charge-energy-to-item
                                                       stack amount false)) amount)))
                                 :block (let [tile (resolve-energy-tile
                                                    world-id (:block-pos target))]
                                          (boolean
                                           (when tile
                                             (cond
                                               (energy/is-node-supported? tile)
                                               (< (double (energy/charge-node tile amount true)) amount)
                                               (energy/is-receiver-supported? tile)
                                               (< (double (energy/charge-receiver tile amount)) amount)
                                               :else false))))
                                 false))]
             {:status (if applied? :applied :failed)}))))
      (when-not (contains? (:actions (capabilities/snapshot)) :resource/enforce-floor)
        (capabilities/register-action!
         :resource/enforce-floor
         (fn [{:keys [owner resource minimum]}]
           (if (and owner (= :overload resource) (number? minimum)
                    (Double/isFinite (double minimum)))
             (let [result (command-runtime/run-commands-in-session!
                           (server-session-id) (str owner)
                           [{:command :enforce-overload-floor
                             :floor-value (double minimum)}])]
               {:status (if (:success? result) :applied :failed)})
             {:status :rejected :reason :invalid-resource-floor}))))
      (when-not (contains? (:actions (capabilities/snapshot)) :resource/add)
        (capabilities/register-action!
         :resource/add
         (fn [{:keys [owner resource amount]}]
           (let [amount (double (or amount 0.0))]
             (if (and owner (= :overload resource)
                      (Double/isFinite amount) (pos? amount))
               (let [result (command-runtime/run-command-in-session!
                              (server-session-id) (str owner)
                              {:command :consume-resource
                               :overload amount :cp 0.0 :creative? false})]
                 {:status (if (:success? result) :applied :failed)})
               {:status :rejected :reason :invalid-resource-add})))))
      (catch Throwable _
        ;; A loader may freeze the registry before AC content boots.  Leave the
        ;; registry state authoritative; missing ports surface as :unhandled.
        (reset! edn-host-capabilities-installed? false)))
  (capabilities/snapshot)))

(defn- execute-combat-intent!
  "Delegate the complete EDN lifecycle operation to Combat Core.

   AC supplies only session/state/platform callbacks; it does not inspect
   component trees or execute the VM."
  [owner intent]
  (combat-skill-runtime/dispatch!
   {:catalog (combat-catalog/catalog)
    :owner owner
    :intent intent
    :now-tick-fn #(long (or @last-known-tick* 0))
    :seed-fn generate-activation-seed
    :owner-view-fn owner-state
    :activation-context-fn activation-context
    :caster-facade-fn caster-facade
    :session-port
    {:current combat-sessions/session
     :resolve-slot-fn resolve-slot
     :start! combat-sessions/start!
     :context combat-sessions/context-for
     :apply-actions! (fn [owner actions]
                       (combat-sessions/apply-actions! owner actions))
     :remove! combat-sessions/remove!}}))

(defn dispatch-intent! [owner intent]
  ;; The migration table is authoritative at the server boundary.  Pending
  ;; skills never reach the legacy engine and therefore have no compatibility
  ;; fallback; once their EDN catalog entry is migrated this gate naturally
  ;; opens without changing the core runtime.
  (let [;; Slot resolution is server-owned preset data, never a client
        ;; ability/event mapping.  The resolved id is then checked against the
        ;; migrated EDN catalog before execution.
        ability-id (edn-ability-id owner intent)]
    (if-not (combat-catalog/available? ability-id)
      {:schema-version 2
        :status :rejected
        :reason :ability-not-migrated
        :feedback [{:type :ability-not-migrated
                    :ability-id ability-id
                    :status (combat-catalog/migration-status ability-id)}]}
      (do
        (install-ac-host-capabilities!)
        (execute-combat-intent! owner intent)))))

(defn dispatch-trigger!
  "Dispatch a server-resolved external trigger from the EDN trigger index.

  The trigger map is produced by `combat-catalog/resolve-trigger`; clients never
  provide ability/event mappings." 
  [owner trigger context]
  (when (and (map? trigger) (:ability trigger) (:event trigger))
    ;; An external event is a terminal interruption for the owner session.
    ;; Run the generic EDN abort phase first so session-scoped VFX and other
    ;; cleanup actions are finalized through the same commit boundary.
    (when-let [session (combat-sessions/session owner)]
      (let [abort-result (execute-combat-intent!
                          owner
                          {:op :abort
                           :action :abort
                           :ability-id (:ability-id session)
                           :context (:context session)
                           :parameter-snapshot (:parameter-snapshot session)
                           :activation-seed (:activation-seed session)})]
        (when (= :accepted (:status abort-result))
          (publish-result! (finalize-result! owner abort-result)))))
    (dispatch-intent! owner
                      {:op :event
                       :action :event
                       :ability-id (:ability trigger)
                       :event (:event trigger)
                       :context context})))
(defn- handle-neutral-domain-event!
  "Apply the two generic domain events emitted by the migrated Arc recipe.

  The event contains only a bounded impact fact and probabilities from the
  activation snapshot.  We re-read the target block immediately before a
  mutation, so a stale raycast cannot overwrite a changed world block."
  [event]
  (case (:type event)
    :achievement/trigger
    (let [payload (:payload event)]
      (when (and (map? payload) (:owner event) (:id payload))
        (achievement-dispatcher/trigger-custom-event!
         (str (:owner event)) (str (:id payload))))
      {:status :applied :type (:type event)})

    :player/feedback
    (let [{:keys [message-key args translate?]} (:payload event)
          owner (:owner event)]
      (when (and owner (string? message-key) (fw/fw-atom))
        (platform/call-adapter (fw/fw-atom)
                               :player-feedback
                               :send-player-feedback!
                               (str owner)
                               {:mode :chat
                                :message message-key
                                :args (vec (or args []))
                                :translate? (boolean (if (nil? translate?) true translate?))}))
      {:status :applied :type (:type event)})

    :world/block-impact
    (let [{:keys [world-id position block-position water? ignite-probability
                  fishing-probability fishing-exp-threshold skill-exp seed]} (:payload event)
          point (cond
                  (vector? position) position
                  (map? position) [(:x position) (:y position) (:z position)]
                  :else nil)
          block-point (cond
                        (vector? block-position) block-position
                        (map? block-position) [(:x block-position)
                                               (:y block-position)
                                               (:z block-position)]
                        :else nil)
          finite-point? (fn [p]
                          (and (vector? p) (= 3 (count p))
                               (every? #(and (number? %) (Double/isFinite (double %))) p)))
          seed (long (or seed 0))
          fish? (and water? (> (double (or skill-exp 0.0))
                               (double (or fishing-exp-threshold 1.0)))
                     (< (seeded-rng/unit-double seed)
                        (double (or fishing-probability 0.0))))
          ignite? (and (not water?)
                       (< (seeded-rng/unit-double (seeded-rng/next-long seed))
                          (double (or ignite-probability 0.0))))]
      (cond
        (not (and (string? world-id) (finite-point? point)
                  (finite-point? block-point)))
        {:status :rejected :reason :invalid-impact-fact}

        fish?
        (if (and (server-bridge/server-bridge-available?)
                 (<= (Math/abs (- (double (nth point 0))
                                  (double (nth block-point 0)))) 1.0)
                 (<= (Math/abs (- (double (nth point 1))
                                  (double (nth block-point 1)))) 1.0)
                 (<= (Math/abs (- (double (nth point 2))
                                  (double (nth block-point 2)))) 1.0))
          (do (server-bridge/spawn-item-stack-at!
               world-id (double (nth point 0)) (double (nth point 1))
               (double (nth point 2)) "minecraft:cooked_cod" 1)
              {:status :applied :operation :spawn-item})
          {:status :unhandled :reason :missing-item-spawn-port})

        ignite?
        (let [[x y z] (mapv #(int (Math/floor (double %))) block-point)
              current (when (block-manipulation/available?)
                        (block-manipulation/get-block world-id x (inc y) z))]
          (if (and (block-manipulation/available?)
                   (or (nil? current) (= "minecraft:air" current)))
            (do (block-manipulation/set-block! world-id x (inc y) z "minecraft:fire")
                {:status :applied :operation :ignite})
            {:status :rejected :reason :impact-target-not-air}))

        :else {:status :applied :operation :none}))

    nil))

(defn dispatch-domain-event! [event]
  (or (handle-neutral-domain-event! event)
      (combat/dispatch-domain-event! (engine) event)))

(defn dispatch-result-domain-events!
  "Dispatch explicit domain events from one CombatResult.

   Query trace entries are intentionally ignored.  The caller controls when
   this seam is invoked so ordering with StatePatch and WorldEffect commits is
   explicit at the application boundary."
  [owner result]
  (reduce (fn [results event]
            (if (and (map? event) (not= :query (:type event)))
              (conj results
                    (dispatch-domain-event!
                     (assoc event :owner (or (:owner event) owner))))
              results))
          []
          (:events result)))

(defn- apply-combat-damage-reactions
  "Delegate declarative damage reactions to Combat Core."
  [request boundary]
  (combat-reactions/apply!
   request
   {:reactions (vals (get-in (combat-catalog/catalog) [:combat :abilities]))
   :session-fn #(combat-sessions/session (str %))
   :state-fn owner-state
   :domain-state (combat/domain-state (engine))
    :precheck? (:precheck? boundary)
    :tunables-fn
    (fn [ability-id session state]
      (let [ability (get-in (combat-catalog/catalog)
                            [:combat :abilities ability-id])
            skill-exp (double (or (get-in state
                                          [:ability-data :skill-exps ability-id])
                                  0.0))]
                 (combat-skill-runtime/materialize-tunables ability skill-exp)))}
   ))

(defn process-damage-request!
  "Authoritative damage interception boundary for platform adapters.

   The old mutable damage-handler registry is not consulted. Combat Core
   returns the transformed neutral request; the platform writes only the
   resulting numeric amount back to its event."
  [player-id attacker-id original-damage damage-source]
  (let [world-id (or (:world-id damage-source)
                     (some-> (raycast/player-position (str player-id)) :world-id))
        target-position (when world-id
                          (entity-motion/entity-position world-id (str player-id)))
        request (apply-combat-damage-reactions
                (combat/process-damage-request
                 (engine)
                 {:source (or attacker-id :environment)
                  :target player-id
                  :base (double original-damage)
                  :type (or (:damage-type damage-source) :generic)
                  :components {:direct (double original-damage)}
                  :tags #{:combat :intercepted}
                  :metadata {:damage-source damage-source
                             :world-id world-id
                             :target-position (when (map? target-position)
                                                [(double (or (:x target-position) 0.0))
                                                 (double (or (:y target-position) 0.0))
                                                 (double (or (:z target-position) 0.0))])
                             :activation-seed (hash [attacker-id player-id
                                                     @last-known-tick*
                                                     original-damage
                                                     (:damage-type damage-source)])
                             :attacker-front? (attacker-front?
                                               player-id attacker-id damage-source)}})
                {})]
    ;; Damage interception is the live boundary: Combat Core owns the
    ;; reduction decision, while AC remains the single writer for player
    ;; resources/cooldowns.  Apply only the neutral patch returned by the
    ;; pipeline; never reconstruct costs in the platform hook.
    (when (and (not (:cancelled? request))
               (seq (:state-patch request)))
      (commit-state-patch! player-id (:state-patch request)))
    (when (and (not (:cancelled? request)) attacker-id
               (seq (:source-state-patch request)))
      (commit-state-patch! (str attacker-id) (:source-state-patch request)))
    (when (and (not (:cancelled? request))
               (seq (:session-patch request)))
      (combat-sessions/apply-actions!
       player-id [{:type :session-patch :entries (:session-patch request)}]))
    (when (and (not (:cancelled? request))
               (damage-output? request))
      (execute-damage-effects! player-id request))
    (when (and (not (:cancelled? request)) (seq (:vfx-signals request)))
      (publish-result! {:schema-version 2 :status :accepted :owner player-id
                        :ability-id :combat-damage
                        :vfx-signals (:vfx-signals request)}))
    (when (and (not (:cancelled? request)) (seq (:events request)))
      (dispatch-result-domain-events! player-id request))
    (if (:cancelled? request)
      0.0
      (double (:base request)))))

(defn process-attack-precheck!
  "Route attack prechecks through the authoritative DamageRequest pipeline.

   The removed mutable cancel/precheck registries have no replacement hook;
   combat nodes can cancel through the same deterministic request pipeline."
  [player-id attacker-id original-damage damage-source]
  (let [world-id (or (:world-id damage-source)
                     (some-> (raycast/player-position (str player-id)) :world-id))
        target-position (when world-id
                          (entity-motion/entity-position world-id (str player-id)))
        request (apply-combat-damage-reactions
                (combat/process-damage-request
                 (engine)
                 {:source (or attacker-id :environment)
                  :target player-id
                  :base (double original-damage)
                  :type (or (:damage-type damage-source) :generic)
                  :components {:direct (double original-damage)}
                  :tags #{:combat :attack-precheck}
                  :metadata {:damage-source damage-source
                             :world-id world-id
                             :target-position (when (map? target-position)
                                                [(double (or (:x target-position) 0.0))
                                                 (double (or (:y target-position) 0.0))
                                                 (double (or (:z target-position) 0.0))])
                             :activation-seed (hash [attacker-id player-id
                                                     @last-known-tick*
                                                     original-damage
                                                     (:damage-type damage-source)])
                             :attacker-front? (attacker-front?
                                               player-id attacker-id damage-source)}})
                {:precheck? true})]
    {:cancelled? (boolean (:cancelled? request))
     :request request}))

(defn apply-attack-precheck!
  "Apply validated Combat Core reflection output before native hurt.

   The platform calls this single boundary before cancellation. Ordinary
   requests stay pure and continue to live damage; reflection output is
   committed and executed here exactly once."
  [player-id attacker-id original-damage damage-source]
  (let [{:keys [request]} (process-attack-precheck!
                           player-id attacker-id original-damage damage-source)
        damage? (damage-output? request)
        patch? (or (seq (:state-patch request))
                   (seq (:session-patch request)))]
    ;; A fully absorbed hit has no world-effect payload, but it still owns the
    ;; resource/session patches and must cancel the native hit.  Partial
    ;; absorbs are intentionally deferred by the reaction to the amount
    ;; modifier, avoiding a second payment on the same attack.
    (when (or damage? patch?)
      (commit-state-patch! player-id (:state-patch request))
      (when (and attacker-id (seq (:source-state-patch request)))
        (commit-state-patch! (str attacker-id) (:source-state-patch request)))
      (when (seq (:session-patch request))
        (combat-sessions/apply-actions!
         player-id [{:type :session-patch :entries (:session-patch request)}]))
      (when damage?
        (execute-damage-effects! player-id request)
        (dispatch-result-domain-events! player-id request)))
    (when (and (not (:cancelled? request)) (seq (:vfx-signals request)))
      (publish-result! {:schema-version 2 :status :accepted :owner player-id
                        :ability-id :combat-damage
                        :vfx-signals (:vfx-signals request)}))
    (boolean (or (:cancelled? request) damage?))))

(defn install-world-effect-handler!
  "Install AC's ordered WorldEffect interpreter.

   The handler is injected by the platform composition root and receives
   `[owner effect]`. Combat Core never calls it directly; this keeps world
   mutation outside the neutral engine while making effect execution explicit
   and observable." 
  [handler]
  (when-not (ifn? handler)
    (throw (ex-info "world-effect handler must be callable" {:value handler})))
  (reset! world-effect-handler* handler)
  handler)

(defn execute-world-effects!
  "Execute WorldEffects in result order and return EffectResults.

   Missing host wiring is reported as a structured result instead of being
   silently discarded. Resource commits have already happened by this point;
   callers must model compensation explicitly." 
  [owner result]
  (let [handler @world-effect-handler*
        effect-results
        (mapv (fn [effect]
                (if-not handler
                  (contract/effect-result {:status :unhandled
                                           :reason :missing-world-effect-handler
                                           :effect effect})
                  (try
                    (contract/effect-result (handler owner effect))
                    (catch Throwable throwable
                      (contract/effect-result
                       {:status :failed
                        :reason :world-effect-exception
                        :effect effect
                        :message (ex-message throwable)})))))
              (:world-effects result))]
    (assoc result :effect-results effect-results)))

(defn finalize-result!
  "Apply one accepted result at the AC composition boundary.

   World effects execute before explicit domain-event reduction.  Both
   acknowledgements remain attached to the immutable result for publication
   and diagnostics."
  [owner result]
  (let [result (if (and (= 2 (:schema-version result))
                        (= :accepted (:status result)))
                 (let [actions (vec (:actions result))
                       patch-results (commit-edn-owner-patches! owner actions)
                       action-results
                 (combat-skill-runtime/commit-actions!
                        owner
                        (vec (remove #(#{:owner-patch :session-patch}
                                       (:type %)) actions))
                        (:actions (capabilities/snapshot)))]
                   (assoc (update result :vfx-signals into
                                  (mapcat :vfx-signals action-results))
                          :patch-results (vec patch-results)
                          :action-results action-results))
                 result)
        result (execute-world-effects! owner result)
        domain-results (if (= :accepted (:status result))
                         (dispatch-result-domain-events! owner result)
                         [])]
    (assoc result :domain-event-results (vec domain-results))))
(defn install-result-sink!
  "Install the AC network sink for server-driven session results.

   The sink receives `[owner result]`; Combat Core remains unaware of the
   network transport and only returns neutral result data."
  [sink]
  (when-not (ifn? sink)
    (throw (ex-info "combat result sink must be callable" {:value sink})))
  (reset! result-sink* sink)
  sink)

(defn- publish-result!
  [result]
  (when-let [sink @result-sink*]
    (when-let [owner (:owner result)]
      (sink owner result)))
  result)

(defn dispatch-and-publish-event!
  "Dispatch a one-shot ability event (no active session required -- a fresh
   activation context is generated the same way a :start intent would) and
   publish its result through the installed sink.

   For neutral platform callbacks that need to route a world event into an
   ability's own EDN program instead of applying an effect directly -- e.g. a
   scripted entity's collision hit reporting {:target-id ...} so the owning
   ability's :events entry decides the damage, not the platform caller."
  [owner ability-id event context]
  (let [result (dispatch-intent! owner
                {:op :event :action :event :ability-id ability-id
                 :event event :context context})]
    (when (= :accepted (:status result))
      (publish-result! (finalize-result! owner result)))
    result))

(defn tick!
  "Advance sessions, execute their world effects, and publish each result."
  [tick]
  (reset! last-known-tick* (long tick))
  (let [edn-results
        (mapv (fn [[owner session]]
                (execute-combat-intent!
                 owner
                 {:op :pulse
                  :action :pulse
                  :ability-id (:ability-id session)
                  :server-tick (long tick)}))
              (combat-sessions/tick! tick))
        ]
    (mapv (fn [result]
            ;; Session pulses produce authoritative patches before effects
            ;; and publication, exactly like start/release intents.
            (when (= :accepted (:status result))
              (commit-state-patch! (:owner result) (:state-patch result)))
            (publish-result! (finalize-result! (:owner result) result)))
          edn-results)))
(defn abort-owner! [owner]
  (combat-sessions/remove! owner)
  nil)
(defn snapshot-owner [owner]
  {:combat-session (combat-sessions/session owner)})

(defn reset-for-test! []
  (reset! engine* nil)
  (reset! catalog* nil)
  (reset! world-effect-handler* nil)
  (reset! result-sink* nil)
  (reset! last-known-tick* 0)
  (combat-sessions/reset-for-test!)
  nil)
