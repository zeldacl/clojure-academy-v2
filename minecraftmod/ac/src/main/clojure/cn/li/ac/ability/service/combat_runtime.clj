(ns cn.li.ac.ability.service.combat-runtime
  "AC composition root for the neutral combat engine.

   Combat Core itself never knows about AC, Minecraft or VFX."
  (:require [cn.li.combat.vfx-publish :as vfx-publish]
            [cn.li.combat.deferred :as deferred]
            [cn.li.ac.ability.service.runtime-store :as runtime-store]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.ac.ability.model.preset :as preset-data]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.service.command-runtime :as command-runtime]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.model.ability :as ability-model]
            [cn.li.ac.ability.service.combat-catalog :as combat-catalog]
            [cn.li.ac.ability.final-runtime :as final-runtime]
            [cn.li.ac.ability.service.combat-sessions :as combat-sessions]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.registry.event :as ability-event]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.mcmod.runtime.capabilities :as capabilities]
            [cn.li.ac.ability.config :as ability-config]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-motion :as entity-motion]
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
            [cn.li.mcmod.framework.platform :as platform]))

(defonce ^:private engine* (atom nil))
(defonce ^:private catalog* (atom nil))
(defonce ^:private final-runtime* (atom nil))
;; How often (in server ticks) tick! resends active :session/:persistent VFX
;; instances through cn.li.combat.vfx-publish/replay-persistent-signals! so a
;; player who enters tracking range after an effect started still sees it.
(def ^:private persistent-replay-interval-ticks 40)
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
(declare owner-state resolve-slot finalize-result! initialize-final-runtime!
         dispatch-domain-event!)

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
  ([_options]
   (initialize-final-runtime!)
   @final-runtime*))

(defn engine [] (:engine (or @final-runtime* (initialize!))))
(defn catalog [] (or @catalog* (some-> @final-runtime* :catalog deref)))
(defn content-hash [] (:content-hash @catalog*))
(defn domain-state [] {})
(defn register-provider! [provider]
  (throw (ex-info "final combat runtime has no dynamic providers" {:provider provider})))

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

(defn commit-final-state!
  "Commit only neutral final-engine resource/cooldown deltas through AC's
   reducer boundary. Unsupported state paths fail explicitly so a migrated
   graph cannot silently mutate an unpersisted field."
  [entries]
  (doseq [{:keys [owner base-state state]} entries]
    (let [base-cp (double (or (get-in base-state [:resources :cp]) 0.0))
          next-cp (double (or (get-in state [:resources :cp]) 0.0))
          base-overload (double (or (get-in base-state [:resources :overload]) 0.0))
          next-overload (double (or (get-in state [:resources :overload]) 0.0))
          cp-delta (- next-cp base-cp)
          overload-delta (- next-overload base-overload)
          commands (cond-> []
                     (neg? cp-delta) (conj {:command :consume-resource :cp (- cp-delta) :overload 0.0})
                     (neg? overload-delta) (conj {:command :consume-resource :cp 0.0 :overload (- overload-delta)}))]
      (when (or (pos? cp-delta) (pos? overload-delta))
        (throw (ex-info "final state contains unsupported resource credit" {:owner owner :base base-state :state state})))
      (when (seq commands)
        (let [result (command-runtime/run-commands-in-session!
                      (server-session-id) (str owner) commands)]
          (when-not (:success? result)
            (throw (ex-info "final state resource commit rejected" {:owner owner :result result}))))))))

(defn initialize-final-runtime!
  "Install AC's production final runtime against mcmod neutral capability
   handlers. This is the only runtime used after the final dispatch cutover."
  []
  (or @final-runtime*
      (let [runtime (final-runtime/install-production!
                     {:state-provider (fn [owner] {:revision 0 :state (owner-state owner)})
                      :commit-state! commit-final-state!})]
        (reset! final-runtime* runtime)
        (reset! catalog* @(:catalog runtime))
        runtime)))

(defn final-runtime [] @final-runtime*)

(defn resolve-slot
  "Resolve a client slot only against the server-authoritative preset." 
  [owner intent]
  (when-let [state (runtime-store/get-player-state (server-session-id) (str owner))]
    (let [slots (preset-data/get-active-slots (:preset-data state))
          slot (nth slots (long (:slot intent)) nil)]
      (when (and (vector? slot) (= 2 (count slot)))
        (skill-query/get-skill-by-controllable (first slot) (second slot))))))
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
           (vfx-publish/publish-combat-result!
            (:vfx (combat-catalog/catalog))
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
                (dispatch-domain-event!
                 {:type :entity-mark
                   :source-player-id owner
                   :target-id target
                   :mark-type mark-type
                   :duration duration
                   :tick (long @last-known-tick*)})
                 {:status :applied
                  :vfx-signals (vec (keep identity
                                          [(mark-vfx-signal request policy
                                                             position duration)]))}))))))
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

(defn dispatch-intent! [owner intent]
  ;; Final runtime is the sole production dispatch path.  Pending source
  ;; graphs return an explicit migration status; there is no legacy VM or
  ;; catalog fallback at this boundary.
  (let [ability-id (edn-ability-id owner intent)]
    (assoc (final-runtime/dispatch-production! owner ability-id
                                               (assoc intent
                                                      :activation-seed
                                                      (or (:activation-seed intent)
                                                          (generate-activation-seed
                                                           owner ability-id
                                                           (long (or (:server-tick intent)
                                                                     @last-known-tick*))))))
           :schema-version 1
           :ability-id ability-id)))

(defn dispatch-trigger!
  "Dispatch a server-resolved external trigger from the EDN trigger index.

  The trigger map is produced by `combat-catalog/resolve-trigger`; clients never
  provide ability/event mappings." 
  [owner trigger context]
  (when (and (map? trigger) (:ability trigger) (:event trigger))
    (dispatch-intent! owner
                      {:op :event
                       :action :event
                       :ability-id (:ability trigger)
                       :event (:event trigger)
                       :server-tick @last-known-tick*
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
      {:status :unhandled :event event}))

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

(defn- final-damage-request
  [player-id attacker-id original-damage damage-source precheck?]
  (let [runtime (final-runtime/production-runtime)
        event {:world-id (or (:world-id damage-source) "unknown")
               :source (or attacker-id :environment)
               :target player-id
               :base (double original-damage)
               :type (or (:damage-type damage-source) :generic)
               :seed (long (or (:seed damage-source) @last-known-tick*))}
        result (final-runtime/resolve-damage! runtime event)]
    (assoc result
           :precheck? precheck?
           :reaction-damage-applied? false
           :base (double (:amount result))
           :cancelled? (boolean (:cancelled? result)))))

(defn process-damage-request!
  "Authoritative damage interception boundary for platform adapters.

   Combat Core returns the transformed neutral request; the platform writes
   only the resulting numeric amount back to its event."
  [player-id attacker-id original-damage damage-source]
  (let [request (final-damage-request player-id attacker-id original-damage damage-source false)]
    (if (:cancelled? request) 0.0 (double (:base request)))))

(defn apply-attack-precheck!
  "Whether the native hit must not land: either Combat Core's reaction
   pipeline explicitly cancelled the request, or reaction damage (e.g. a
   reflect) already landed in its place. The platform calls this single
   boundary before cancellation; ordinary requests stay pure and continue to
   live damage."
  [player-id attacker-id original-damage damage-source]
  (let [request (final-damage-request player-id attacker-id original-damage damage-source true)]
    (boolean (or (:cancelled? request) (:reaction-damage-applied? request)))))

(defn finalize-result!
  "Apply one accepted result at the AC composition boundary: commit its
   owner/session patches, invoke registered capability actions, then reduce
   explicit domain events. Acknowledgements remain attached to the immutable
   result for publication and diagnostics."
  [owner result]
  (let [result (if (= :accepted (:status result))
                 (assoc result :patch-results [] :action-results [])
                 result)
        domain-results (if (= :accepted (:status result))
                         (dispatch-result-domain-events! owner result)
                         [])]
    (assoc result :domain-event-results (vec domain-results))))
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
      (vfx-publish/publish-combat-result!
       (:vfx (combat-catalog/catalog)) (finalize-result! owner result)))
    result))

(defn tick!
  "Advance scheduled final graph work and publish its neutral result."
  [tick]
  (reset! last-known-tick* (long tick))
  (if-let [runtime (final-runtime/production-runtime)]
    (final-runtime/tick! runtime tick)
    {:status :rejected :reason :final-runtime-not-installed :tick tick}))
(defn abort-owner! [owner]
  (if-let [runtime (final-runtime/production-runtime)]
    (final-runtime/abort-owner! runtime owner)
    {:status :rejected :reason :final-runtime-not-installed :owner owner}))
(defn snapshot-owner [owner]
  {:combat-session (combat-sessions/session owner)})

(defn reset-for-test! []
  (reset! engine* nil)
  (reset! catalog* nil)
  (reset! last-known-tick* 0)
  (vfx-publish/reset-for-test!)
  nil)
