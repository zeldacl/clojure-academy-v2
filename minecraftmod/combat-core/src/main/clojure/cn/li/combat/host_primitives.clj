(ns cn.li.combat.host-primitives
  "v3 registration for combat-core's confirmed true-primitive components
   (see the mossy-wren plan's R2 layer audit -- everything registered here
   passed the 'single host call, cannot be decomposed further' test; the 6
   components that failed it -- :host/beam-trace, :entity/radial-impulse,
   :block/area-break, :block/random-break, :block/break-budget,
   :entity/teleport-group -- are deliberately absent, they become :layer
   :mid composites in R4 once the finer-grained query primitives they
   compose from exist).

   Each :impl is a pure REQUEST-SHAPING function: given typed, already-
   resolved inputs, it builds the same neutral action/query request shape
   cn.li.mcmod.runtime.effect-contract's action-request/query-request
   already define, and hands it to ctx's :dispatch-action!/:dispatch-query!
   callback. :impl never touches ExecutionFrame/HostTable directly -- that
   Java object model is an execution-engine concern, wired up once real
   content exercises this path (R4/R5), not baked into every primitive.

   ctx contract these :impl fns rely on:
     {:world-id        string
      :owner           string
      :activation-seed long
      :ability-id      keyword
      :dispatch-action! (fn [capability-kw request-map] -> nil)
      :dispatch-query!  (fn [capability-kw request-map] -> query-result)}

   ADDITIVE ONLY at this revision: nothing yet calls these through
   node-core's runtime; the old vm.clj execute-component!/emit-component!
   keeps running every existing ability unchanged until R5's cutover (see
   source_nodes.clj's docstring for the same note)."
  (:require [cn.li.node.descriptor :as node]
            [cn.li.combat.beam :as beam]))

(defn- action-impl
  "Build the :impl for a component that dispatches to `capability` through
   ctx's action pipeline. `resolved-fields` already excludes :capability
   (invoke-primitive! filters to the descriptor's own :inputs)."
  [capability]
  (fn [inputs ctx]
    ((:dispatch-action! ctx) capability
     (assoc inputs
            :capability capability
            :world-id (or (:world-id inputs) (:world-id ctx) "unknown")
            :activation-seed (:activation-seed ctx)
            :ability-id (:ability-id ctx)))
    {}))

(defn- query-impl
  "Build the :impl for a component that dispatches to `capability` through
   ctx's query pipeline and returns its single result under `output-key`.
   `query-kind`, when given, is baked into every request unconditionally
   (never a caller-supplied field) -- cn.li.combat.platform/raycast! is a
   single :raycast capability multiplexed by :query-kind into 6 different
   handlers (basic/raycast-fan/directional-destination/resolve-destination/
   block-placement/penetration); a query-primitive that omits :query-kind
   falls through to the default (plain) handler. Three of the four raycast-
   family primitives below were registered without one, meaning
   :target/resolve-destination and :target/block-placement silently called
   the WRONG host handler (basic-raycast, ignoring :hit/:policy entirely)
   -- never caught because nothing had exercised these primitives against
   the real host yet (additive-only strategy), only fake :dispatch-query!
   callbacks in tests that only assert the outgoing request shape.

   A 6th mode -- :penetration -- is not tied to any single component id
   the way the other 5 are: the old v2 execution path (vm.clj's
   invoke-query-component!) derives it from the request DATA itself,
   overriding any component-derived :query-kind whenever
   (get-in data [:policy :type]) is :penetration, so ANY :target/raycast-
   family call can opt into penetration mode just by setting that policy
   field. :target/raycast is the one this composes through
   (target_penetration_destination.edn's real v2 content calls plain
   :target/raycast with :policy {:type :penetration ...}, not a dedicated
   primitive) -- reproduced here as a data-derived override rather than a
   second static :query-kind, to match the real dispatch rule exactly."
  ([capability output-key] (query-impl capability output-key nil))
  ([capability output-key query-kind]
   (fn [inputs ctx]
     {output-key
      ((:dispatch-query! ctx) capability
       (let [derived-kind (if (= :penetration (get-in inputs [:policy :type]))
                             :penetration
                             query-kind)]
         (cond-> (assoc inputs :capability capability :owner (:owner ctx) :world-id (:world-id ctx))
           derived-kind (assoc :query-kind derived-kind))))})))

(def ^:private query-primitives
  "component-id -> {:capability :output :inputs :outputs-type :doc :category :query-kind}"
  {:target/raycast
   {:capability :raycast :output :hit
    :inputs {:origin {:type :vec3} :direction {:type :vec3} :distance {:type :double :min 0.0}
             :include-entities? {:type :boolean :default true}
             :include-blocks? {:type :boolean :default true}
             :living-only? {:type :boolean :default false}
             :policy {:type :map :default {}}}
    :output-type :hit-result :doc "Cast a ray, return the first hit." :category :targeting}

   :target/raycast-fan
   {:capability :raycast :output :hits :query-kind :raycast-fan
    :inputs {:origin {:type :vec3} :direction {:type :vec3} :distance {:type :double :min 0.0}
             :pitch-angles {:type [:list-of :double]} :yaw-range-degrees {:type :double}
             :limit {:type :long :min 0}}
    :output-type [:list-of :hit-result] :doc "Bounded fan of block rays around a direction." :category :targeting}

   :target/resolve-destination
   {:capability :raycast :output :destination :query-kind :resolve-destination
    :inputs {:hit {:type :hit-result} :origin {:type :vec3} :direction {:type :vec3} :distance {:type :double}
             :policy {:type :map :default {}}}
    :output-type :destination :doc "Resolve the neutral landing point for a raycast hit." :category :targeting}

   :target/block-placement
   {:capability :raycast :output :destination :query-kind :block-placement
    :inputs {:hit {:type :hit-result} :origin {:type :vec3} :direction {:type :vec3} :distance {:type :double}
             :policy {:type :map :default {}}}
    :output-type :block-placement :doc "Resolve a block placement/drop plan for a raycast hit." :category :targeting}

   ;; :direction is a neutral movement KEYWORD (:forward/:back/:left/
   ;; :right, defaulting to :forward server-side when absent/not a
   ;; keyword) -- cn.li.combat.platform/directional-raycast does
   ;; (if (keyword? direction) direction :forward), not a vec3 the way
   ;; every other raycast-family primitive's :direction is. Was
   ;; incorrectly declared :vec3 until this fix (a real type mismatch,
   ;; distinct from the missing-:query-kind bug fixed alongside it).
   :target/directional-destination-query
   {:capability :raycast :output :destination :query-kind :directional-destination
    :inputs {:origin {:type :vec3} :look {:type :vec3} :eye-y {:type :double}
             :direction {:type :keyword :default :forward} :distance {:type :double}
             :policy {:type :map :default {}}}
    :output-type :destination :doc "Directional movement landing query (feet-to-eye rays, strafe directions)." :category :targeting}

   :target/entities
   {:capability :entity/select :output :entities
    :inputs {:shape {:type :map} :projection {:type :map} :limit {:type :long :min 0}
             :filter {:type :map :default nil}
             :sort {:type [:list-of :map] :default nil}}
    :output-type :entity-list :doc "Query entities matching a neutral shape/projection, optionally narrowed by :filter (e.g. {:entity-types [...]}) and ordered by :sort (e.g. [{:by :distance-squared :order :ascending}], real usage in electron-missile/mag-manip) -- v2's own opcode VM never filtered a component's fields down to a declared schema, so real ability content has always sent :filter/:sort straight to the real :entity/select handler even though neither version's descriptor ever marked them :required." :category :targeting}

   :target/entity-snapshot
   {:capability :entity/snapshot :output :snapshot
    :inputs {:entity-id {:type :string} :projection {:type :map}}
    :output-type :entity-snapshot :doc "Snapshot one entity's projected fields." :category :targeting}

   :owner/snapshot
   {:capability :owner/snapshot :output :snapshot
    :inputs {:projection {:type :map}}
    :output-type :owner-snapshot :doc "Snapshot the caster's own projected fields." :category :targeting}

   :target/item-held
   {:capability :item/held :output :held-item
    :inputs {:source {:type :keyword}}
    :output-type :item-snapshot :doc "Snapshot the item held in a hand slot." :category :targeting}

   :target/saved-location
   {:capability :saved-location :output :location
    :inputs {:location-name {:type :string}}
    :output-type :vec3 :doc "Resolve a player's saved named location." :category :targeting}

   :energy/target
   {:capability :energy/target :output :energy-target
    :inputs {:hit {:type :hit-result}}
    :output-type :energy-target :doc "Resolve the energy-network target for a raycast hit." :category :targeting}

   :target/blocks
   {:capability :block/select :output :blocks
    :inputs {:shape {:type :map} :projection {:type :map} :limit {:type :long :min 0}}
    :output-type :block-list :doc "Query blocks matching a neutral shape/projection." :category :targeting}

   :terrain/propagate
   {:capability :terrain/propagate :output :plan
    :inputs {:origin {:type :vec3} :direction {:type :vec3} :max-iterations {:type :long :min 0}
             :initial-energy {:type :double}
             :entity-search-radius {:type :double :default nil}
             ;; v2's own component schema (components.clj) only ever marked
             ;; :origin/:direction/:max-iterations/:initial-energy/:spread/
             ;; :energy-cost :required -- and even got :spread/:energy-cost's
             ;; TYPE wrong relative to real content (both are maps, not
             ;; doubles). v2's opcode VM never filtered a component's fields
             ;; down to a declared schema, so the one real ability that
             ;; calls this has always sent a much larger real field set
             ;; straight through unfiltered; declared
             ;; here so invoke-primitive! (which DOES filter) doesn't
             ;; silently drop them -- the same gap class found repeatedly
             ;; this session (:projectile/schedule-beam's selector fields,
             ;; :target/entities' :filter, :motion/velocity's :dismount?/
             ;; :reset-fall-damage?).
             :spread {:type :map}
             :energy-cost {:type :map}
             :block-transforms {:type :map :default nil}
             :ground-break-probability {:type :double :default nil}
             :drop-probability {:type :double :default nil}
             :launch-base {:type :double :default nil}
             :launch-span {:type :double :default nil}
             :seed {:type :long :default nil}
             :mastery {:type :double :default nil}
             :mastery-threshold {:type :double :default nil}
             :mastery-radius {:type :long :default nil}
             :mastery-hardness-cap {:type :double :default nil}}
    :output-type :terrain-plan
    :doc "Bounded dynamic-frontier terrain propagation; the host returns a deterministic mutation plan, EDN decides how to apply it. Not decomposable into :flow/foreach -- each step's candidate set depends on the previous step's result, which a static-length loop cannot express."
    :category :world}})

(def ^:private action-primitives
  "component-id -> {:capability :inputs :doc :category}"
  {:inventory/consume
   {:capability :inventory/consume
    :inputs {:source {:type :keyword} :count {:type :long :min 0}}
    :doc "Consume N items from a hand slot." :category :inventory}

   :inventory/settle
   {:capability :inventory/settle
    :inputs {:source {:type :keyword} :count {:type :long :min 0} :position {:type :vec3}
             :drop? {:type :boolean} :creative? {:type :boolean}}
    :doc "Drop a copy at a world position, or consume one item when :drop? is false." :category :inventory}

   :inventory/place-or-drop
   {:capability :inventory/place-or-drop
    :inputs {:source {:type :keyword} :count {:type :long :min 0} :plan {:type :block-placement} :creative? {:type :boolean}}
    :doc "Atomically place the held block at the planned face, or drop it at the neutral fallback point." :category :inventory}

   :combat/damage
   {:capability :entity/damage
    :inputs {:target {:type :entity-ref} :amount {:type :double :min 0.0}
             :damage-type {:type :keyword :default :generic}
             :damage-pipeline {:type :keyword :default nil}
             :reset-invulnerable-time? {:type :boolean :default nil}}
    :doc "Apply damage to an entity. :damage-pipeline (real content sends e.g. :skill alongside :damage-type) selects which reaction/mitigation pipeline the interception boundary runs. :reset-invulnerable-time? (electron-missile's rapid-fire ball hits) is a real optional field mcmod.platform.entity-damage's real handler honors (clears Minecraft's post-hit invulnerability window so a fast follow-up hit still lands) -- v2's opcode VM passed every field straight through with no schema filtering, so both were reaching the real handler unfiltered; declared here so invoke-primitive! (which DOES filter to declared :inputs) doesn't silently drop them." :category :combat}

   :entity/trigger-behavior
   {:capability :entity/trigger-behavior
    :inputs {:entity {:type :entity-ref}}
    :doc "Trigger a behavior-driven entity's neutral behavior hook." :category :entity}

   :entity/mark
   {:capability :entity/mark
    :inputs {:target {:type :entity-ref} :mark-type {:type :keyword}
             :duration-ticks {:type :long :default nil} :requires-ability {:type :keyword :default nil}}
    :doc "Apply a neutral entity mark. :duration-ticks/:requires-ability are real optional fields content sends alongside :target/:mark-type -- same gap class found repeatedly this session (v2's opcode VM never filtered a component's fields down to a declared schema)." :category :entity}

   :energy/charge
   {:capability :energy/charge
    :inputs {:mode {:type :keyword} :target {:type :entity-ref} :amount {:type :double}}
    :doc "Charge/discharge an energy-network target." :category :world}

   :combat/impulse
   {:capability :entity/impulse
    :inputs {:target {:type :entity-ref} :vector {:type :vec3}}
    :doc "Apply an instantaneous impulse to one entity." :category :motion}

   :motion/flight
   {:capability :motion/flight
    :inputs {:direction {:type :vec3} :speed {:type :double} :acceleration {:type :double}
             :hover-near-ground-velocity {:type :double} :hover-air-velocity {:type :double}
             :near-ground-distance {:type :double} :near-ground-eye-height {:type :double}}
    :doc "Drive the caster's flight physics for one tick." :category :motion}

   :motion/velocity
   {:capability :motion/velocity
    :inputs {:velocity {:type :vec3} :dismount? {:type :boolean :default false}
             :reset-fall-damage? {:type :boolean :default false}}
    :doc "Set the caster's own velocity, through the shared safe-physics boundary. :dismount?/:reset-fall-damage? are optional fields real launch-shaped content sends alongside :velocity -- v2's opcode VM never filtered a component's fields down to a declared schema, so these reached the real handler unfiltered; declared here so invoke-primitive! doesn't silently drop them (the same gap class as :projectile/schedule-beam's selector fields and :target/entities' :filter, found earlier this session)." :category :motion}

   :owner/can-fly
   {:capability :owner/can-fly
    :inputs {:enabled? {:type :boolean}}
    :doc "Toggle the caster's ability to fly." :category :motion}

   :combat/status
   {:capability :entity/status
    :inputs {:target {:type :entity-ref} :status-id {:type :keyword} :duration-ticks {:type :long :min 0}
             :amplifier {:type :long :default 0}}
    :doc "Apply a status effect to an entity." :category :combat}

   :entity/teleport
   {:capability :entity/teleport
    :inputs {:target {:type :entity-ref} :position {:type :vec3}
             :dismount? {:type :boolean :default nil} :reset-fall-damage? {:type :boolean :default nil}}
    :doc "Teleport one entity to a position. :dismount?/:reset-fall-damage? are real optional fields content sends alongside :target/:position -- same gap class found repeatedly this session (e.g. :motion/velocity's identical pair)." :category :motion}

   :entity/reset-fall-damage
   {:capability :entity/reset-fall-damage
    :inputs {:target {:type :entity-ref}}
    :doc "Reset an entity's accumulated fall damage." :category :motion}

   :entity/spawn
   {:capability :entity/spawn
    :inputs {:entity-type {:type :keyword} :owner {:type :string} :position {:type :vec3}
             :velocity {:type :vec3 :default nil} :life-ticks {:type :long :default nil}}
    :doc "Spawn a behavior-driven entity owned by the caster. :velocity/:life-ticks are optional fields every real caller (electron-bomb/electron-missile/light-shield/mag-manip/scatter-bomb-shaped content) sends alongside the 3 required fields -- same gap class found repeatedly this session (v3's :inputs were built from v2's :required schema set, not real content's actual field usage)." :category :entity}

   :entity/discard
   {:capability :entity/discard
    :inputs {:entity {:type :entity-ref}}
    :doc "Discard an entity." :category :entity}

   :entity/configure
   {:capability :entity/configure
    :inputs {:entity {:type :entity-ref}
             :world-id {:type :string :default nil}
             :block-id {:type :keyword :default nil}
             :place-when-collide? {:type :boolean :default nil}
             :velocity {:type :vec3 :default nil}
             :projectile-damage {:type :double :default nil}
             :add-tags {:type [:list-of :string] :default nil}}
    :doc "Apply neutral configuration to a spawned entity. :world-id/:block-id/:place-when-collide? (mag-manip's held magnetic block body) and :velocity/:projectile-damage/:add-tags (vec-deviation's deviated projectile) are real optional fields both real callers send alongside :entity -- same gap class found repeatedly this session (v3's :inputs were built from v2's :required schema set, not real content's actual field usage)." :category :entity}

   :motion/entity-velocity
   {:capability :motion/entity-velocity
    :inputs {:target {:type :entity-ref} :velocity {:type :vec3}}
    :doc "Set another entity's velocity." :category :motion}

   :motion/entity-velocity-add
   {:capability :motion/entity-velocity-add
    :inputs {:target {:type :entity-ref} :velocity {:type :vec3}}
    :doc "Add to another entity's velocity." :category :motion}

   :projectile/schedule-beam
   {:capability :projectile/schedule-beam
    :inputs {:owner {:type :string} :origin {:type :vec3} :destination {:type :vec3}
             :damage {:type :double} :damage-type {:type :keyword} :delay-ticks {:type :long :min 0}
             :origin-selector {:type :map :default nil}
             :destination-selector {:type :map :default nil}
             :exclude-owner? {:type :boolean :default false}
             :settlement-vfx {:type :map :default nil}
             :seed {:type :long :default 0}
             :instance-key {:type [:list-of :any] :default nil}}
    :doc "Schedule a delayed beam impact. Not decomposable: the VM is a synchronous tree-walker with no wait/scheduled-continuation node type. :origin-selector/:destination-selector/:exclude-owner?/:settlement-vfx/:seed/:instance-key are optional fields cn.li.combat.deferred/schedule-action! (the real capability handler) reads beyond the required :origin/:destination/:damage/:damage-type/:delay-ticks -- see that namespace's schedule-action!/settle!." :category :combat}

   :block/break
   {:capability :block/break
    :inputs {:position {:type :vec3} :drop? {:type :boolean :default true}
             :expected-block-id {:type :any :default nil}
             :fortune-level {:type :long :default nil}
             :tool-tier-capped? {:type :boolean :default nil}}
    :doc "Break one block, dropping its item unless :drop? is false. :expected-block-id/:fortune-level/:tool-tier-capped? are real optional fields content sends alongside :position/:drop? -- same gap class found repeatedly this session." :category :world}

   :block/set
   {:capability :block/set
    :inputs {:position {:type :vec3} :block-id {:type :keyword}
             :expected-block-ids {:type [:list-of :any] :default nil}}
    :doc "Set one block, optionally guarded by :expected-block-ids (only apply if the current block still matches, e.g. a terrain-propagation plan computed a step ago)." :category :world}

   :world/sound
   {:capability :world/sound
    :inputs {:sound-id {:type :keyword} :position {:type :vec3}}
    :doc "Play a world sound." :category :world}

   :world/lightning
   {:capability :world/lightning
    :inputs {:position {:type :vec3}}
    :doc "Strike lightning at a position." :category :world}

   :world/explosion
   {:capability :world/explosion
    :inputs {:position {:type :vec3} :radius {:type :double :min 0.0}
             :owner {:type :string :default nil} :fire? {:type :boolean :default nil}
             :terrain? {:type :boolean :default nil}}
    :doc "Trigger a neutral terrain/entity explosion. :owner/:fire?/:terrain? are real optional fields content sends alongside :position/:radius -- same gap class found repeatedly this session." :category :world}

   :projectile/redirect
   {:capability :projectile/redirect
    :inputs {:entity {:type :entity-ref} :target-position {:type :vec3} :velocity {:type :vec3} :difficulty {:type :double}}
    :doc "Redirect an in-flight projectile entity." :category :combat}

   :resource/enforce-floor
   {:capability :resource/enforce-floor
    :inputs {:resource {:type :keyword} :minimum {:type :double}}
    :doc "Clamp a resource to a minimum." :category :resource}

   :resource/add
   {:capability :resource/add
    :inputs {:resource {:type :keyword} :amount {:type :double}}
    :doc "Add to a resource." :category :resource}})

(defn- beam-trace-impl
  "Port of cn.li.combat.beam/trace! for v3: same cn.li.combat.beam/trace-core
   geometry/falloff/reflection/block-sampling computation (unchanged, shared
   with v2), driven through ctx's :dispatch-query! instead of a HostTable --
   :block/select, :entity/select and (when :reflection-policy is set)
   :interaction/resolve are all plain registered query capabilities, not a
   single host call, which is why this stays :layer :primitive rather than
   an EDN composite (R2 audit; NODE_LANGUAGE.md's three-layer rule only
   requires a primitive be implemented as Clojure, not that it make exactly
   one host call)."
  [inputs ctx]
  (let [query! (fn [query] ((:dispatch-query! ctx) (:capability query) query))
        request (assoc inputs :owner (:owner ctx) :world-id (:world-id ctx))]
    {:beam (beam/trace-core query! request)}))

(defn install!
  "Register every confirmed true-primitive query/action component. Call
   once per registry lifetime, before node/freeze!."
  []
  (doseq [[id {:keys [capability output inputs output-type doc category query-kind]}] query-primitives]
    (node/register-primitive!
     {:id id :revision 1 :doc doc :category category
      :inputs inputs :outputs {output {:type output-type}} :effects #{:query}
      :impl (query-impl capability output query-kind)}))
  (doseq [[id {:keys [capability inputs doc category]}] action-primitives]
    (node/register-primitive!
     {:id id :revision 1 :doc doc :category category
      :inputs inputs :outputs {} :effects #{:mutate}
      :impl (action-impl capability)}))
  (node/register-primitive!
   {:id :target/beam-trace :revision 1 :category :targeting
    :doc "Trace a bounded beam: entities within :radius of the beam's axis (falloff-weighted damage, optional :reflection-policy) and blocks sampled along it. Not decomposable into a single host call -- see beam-trace-impl."
    :inputs {:origin {:type :vec3} :trace-origin {:type :vec3 :default nil}
             :direction {:type :vec3} :length {:type :double :min 0.0}
             :visual-length {:type :double :default nil} :radius {:type :double :min 0.0}
             :query-radius {:type :double :default nil} :entity-limit {:type :long :default 256}
             :damage {:type :double :default 0.0} :damage-type {:type :keyword :default :generic}
             :block-limit {:type :long :default 4096} :reflection-policy {:type :map :default nil}
             :step {:type :double :default nil}}
    :outputs {:beam {:type :map}}
    :effects #{:query}
    :impl beam-trace-impl}))
