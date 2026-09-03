(ns cn.li.combat.dsl-vocabulary
  "The new surface-DSL combat vocabulary: {node-id {:params ... :returns ...
   :effects #{...} :capability kw :cost n}}. Mechanically derived, node by
   node, from the old cn.li.combat.vocabulary's ~57-entry component-specs
   (still live -- this is an ADDITIVE new namespace, not a replacement; see
   the redesign plan's staging notes) plus final_engine.clj's
   capability-routes/query-capabilities/query-kinds tables, which this
   folds into ONE table instead of three plus a node-kind dispatch.

   Six old node ids are gone by design, not oversight: ability/caster,
   ability/tunable, ability/budget, ability/cooldown, ability/progression,
   ability/context all just read a named value out of the activation's
   initial input -- exactly what a capability read already is. They fold
   into the ?cap sigil uniformly: ability/tunable -> $name (already its own
   sigil); ability/caster's named ports -> ?caster/eye, ?caster/aim, etc;
   ability/budget/cooldown/progression/context -> ?budget/name,
   ?cooldown/name, ?progression/name, ?context/name. The capability TYPE
   table these resolve against is assembled by whoever builds the real
   :capabilities env passed to cn.li.node.compile (this module's own
   composition root, not this file -- a vocabulary table and a capability
   table are different concerns even though both back a `?`-read).

   :effects, unlike everything else here, has no old-system equivalent --
   it is new, and is what both player admission (cn.li.combat.player) and
   a future editor's grey-out list check. Assigned by node-KIND (the same
   partition final_engine.clj's node-kind-for used to route dispatch):
   :query nodes read the world (#{:world-read}, #{:owner-read} for the two
   owner/* nodes); :action nodes mutate it (#{:world-write}, or
   #{:owner-write} for the resource/cooldown/progression/score family that
   mutates the CASTER's own state instead of the world, or
   #{:inventory-write} for the three inventory/* nodes specifically).

   Types are :any (the gradual-typing escape hatch -- see
   cn.li.node.types/assignable?) except where a field's old name makes its
   real type unambiguous (:position/:origin/:direction/:velocity -> :vec3,
   :amount/:duration-ticks/*-ticks -> :double, :limit -> :long, a `?`-suffixed
   name -> :boolean, :world-id -> :string, :target/:entity -> :entity-ref).
   This is honest about what it is: the old system never captured real
   field types either, so :any is not a regression, and this table upgrades
   exactly the fields cheap and safe to upgrade -- it does not pretend to
   have derived precise types from a system that had none.

   :barrier?/two-phase host preflight (the old engine's guarantee that a
   multi-step ability's actions either all apply or none do) is
   DELIBERATELY NOT replicated: cn.li.mcmod.runtime.effect-emit's :action
   instructions call the host's :command! immediately, not queued for a
   batched commit at the end of a dispatch. This is a real, deliberate
   simplification, not an oversight -- see the redesign notes for the
   tradeoff. Authors get most of the same practical safety by checking
   cost/resource guards with `when` before any action that should not run
   on insufficient resources, which is the idiomatic DSL shape anyway.")

(defn- node
  ([params returns effects] (node params returns effects nil 1))
  ([params returns effects capability] (node params returns effects capability 1))
  ([params returns effects capability cost]
   (cond-> {:params params :returns returns :effects effects :cost (long cost)}
     capability (assoc :capability capability))))

(defn- p
  "Shorthand for a required :any-typed param; override with p* for a real type."
  [] {:type :any})

(defn- p* [type] {:type type})

(defn- opt [type default] {:type type :default default})

(def nodes
  (merge
   ;; --- kernel/* : host-facing primitives composites call directly ------
   ;; The old system's :layer :kernel tier does not exist here (the redesign
   ;; plan replaces it with plain :effects tagging, not a hard authorability
   ;; boundary -- see this module's own docstring) -- these are ordinary
   ;; query nodes, findable and callable like any other, just unusually
   ;; complex/host-implemented ones.
   {:kernel/trace-beam
    (node {:origin (p* :vec3) :trace-origin (opt :vec3 nil) :direction (p* :vec3)
           :length (p* :double) :visual-length (opt :double nil) :radius (p* :double)
           :query-radius (opt :double nil) :entity-limit (opt :long 256)
           :damage (opt :double 0.0) :damage-type (opt :keyword :generic)
           :block-limit (opt :long 4096) :reflection-policy (opt :any nil) :step (opt :double nil)}
          :any #{:world-write} :kernel/trace-beam 4)

    :kernel/terrain-wave-plan
    (node {:origin (p* :vec3) :direction (p* :vec3) :initial-energy (p* :double)
           :max-iterations (p* :long) :seed (p* :long) :spread (p) :energy-cost (p)
           :block-transforms (p) :mastery (p* :double) :mastery-threshold (p* :double)
           :mastery-radius (p* :long) :mastery-hardness-cap (p* :double)
           :ground-break-probability (p* :double) :drop-probability (p* :double)
           :launch-base (p* :double) :launch-span (p* :double) :entity-search-radius (p* :double)}
          :any #{:world-read} :kernel/terrain-wave-plan 4)

    ;; A seeded probability roll needs the activation's own RNG cursor,
    ;; which only the host frame carries -- unlike node-core's pure ops
    ;; (cn.li.node.ops), which are deliberately seed-free (see that
    ;; namespace's docstring), so this is a :query, not a :pure op.
    :random/chance
    (node {:probability (p* :double)} :boolean #{} :random/chance 0)}

   ;; --- target/* : query, world-read -----------------------------------
   {:target/raycast
    (node {:origin (p* :vec3) :direction (p* :vec3) :distance (p* :double)
           :include-entities? (opt :boolean false) :include-blocks? (opt :boolean false)
           :living-only? (opt :boolean false) :policy (opt :any nil)}
          :hit-result #{:world-read} :raycast 2)

    :target/raycast-fan
    (node {:origin (p* :vec3) :direction (p* :vec3) :distance (p* :double)
           :yaw-range-degrees (opt :double 0.0) :pitch-angles (opt :any nil)
           :limit (opt :long 8) :seed (opt :long nil)}
          nil #{:world-read} :raycast 3)

    :target/entities
    (node {:shape (p) :filter (opt :any nil) :limit (opt :long 128)
           :sort (opt :any nil) :projection (opt :any nil)}
          [:list-of :entity-ref] #{:world-read} :entity/select 2)

    :target/blocks
    (node {:shape (p) :limit (opt :long 128) :projection (opt :any nil)}
          [:list-of :any] #{:world-read} :block/select 2)

    :target/entity-snapshot
    (node {:entity-id (p* :entity-ref) :projection (opt :any nil)}
          :any #{:world-read} :entity/snapshot 1)

    :target/item-held
    (node {:source (p)} :any #{:world-read} :item/held 1)

    :target/saved-location
    (node {:location-name (p* :keyword)} :any #{:owner-read} :saved-location 1)

    :target/resolve-destination
    (node {:hit (p) :origin (p* :vec3) :direction (p* :vec3) :distance (p* :double)
           :policy (opt :any nil)}
          :any #{:world-read} :raycast 2)

    :target/block-placement
    (node {:hit (p) :origin (p* :vec3) :direction (p* :vec3) :distance (p* :double)
           :policy (opt :any nil)}
          :any #{:world-read} :raycast 2)

    :target/directional-destination-query
    (node {:look (p* :vec3) :eye-y (p* :double) :origin (p* :vec3) :direction (p* :vec3)
           :distance (p* :double) :policy (opt :any nil)}
          :any #{:world-read} :raycast 2)

    :owner/snapshot
    (node {:projection (opt :any nil)} :any #{:owner-read} :owner/snapshot 1)
    :owner/can-fly
    (node {:enabled? (p* :boolean)} nil #{:owner-write})

    :energy/target
    (node {:hit (p)} :any #{:world-read} :energy/target 1)

    :data/random-item
    (node {:items (p)} :any #{} :data/random-item 1)}

   ;; --- combat/entity/world/block/motion/projectile/inventory : action --
   {:combat/damage
    (node {:target (p* :entity-ref) :amount (p* :double) :damage-type (opt :keyword :generic)
           :world-id (opt :string nil) :damage-pipeline (opt :any nil)
           :reset-invulnerable-time? (opt :boolean false)}
          nil #{:world-write} :entity/damage 3)

    :combat/status
    (node {:target (p* :entity-ref) :status-id (p* :keyword) :duration-ticks (p* :double)
           :amplifier (opt :long 0)}
          nil #{:world-write} :entity/status 2)

    :combat/impulse
    (node {:target (p* :entity-ref) :vector (p* :vec3)} nil #{:world-write} :entity/impulse 1)

    :entity/spawn
    (node {:entity-type (p* :keyword) :position (p* :vec3) :velocity (opt :vec3 nil)
           :world-id (opt :string nil) :life-ticks (opt :long nil) :owner (opt :any nil)
           :add-tags (opt :any nil) :barrier? (opt :boolean false)}
          :any #{:world-write} nil 2)
    :entity/configure
    (node {:entity (p* :entity-ref) :velocity (opt :vec3 nil) :block-id (opt :any nil)
           :add-tags (opt :any nil) :place-when-collide? (opt :boolean false)
           :projectile-damage (opt :double nil) :world-id (opt :string nil)}
          nil #{:world-write})
    :entity/discard
    (node {:entity (p* :entity-ref) :world-id (opt :string nil)} nil #{:world-write})
    :entity/mark
    (node {:target (p* :entity-ref) :mark-type (p* :keyword) :duration-ticks (p* :double)
           :requires-ability (opt :keyword nil)}
          nil #{:world-write})
    :entity/reset-fall-damage
    (node {:target (p* :entity-ref)} nil #{:world-write})
    :entity/teleport
    (node {:target (p* :entity-ref) :position (p* :vec3) :world-id (opt :string nil)
           :dismount? (opt :boolean false) :reset-fall-damage? (opt :boolean false)}
          nil #{:world-write})
    :entity/trigger-behavior
    (node {:entity (p* :entity-ref)} nil #{:world-write})

    :world/explosion
    (node {:position (p* :vec3) :radius (p* :double) :world-id (opt :string nil)
           :terrain? (opt :boolean false) :fire? (opt :boolean false) :owner (opt :any nil)}
          nil #{:world-write} :world/explosion 3)
    :world/lightning
    (node {:position (p* :vec3) :world-id (opt :string nil) :visual-only? (opt :boolean false)}
          nil #{:world-write} :world/lightning 2)
    :world/sound
    (node {:sound-id (p* :string) :position (p* :vec3) :world-id (opt :string nil)}
          nil #{:world-write} :world/sound 1)

    :block/break
    (node {:position (p* :vec3) :expected-block-id (opt :any nil) :fortune-level (opt :long 0)
           :tool-tier-capped? (opt :boolean false) :drop? (opt :boolean true)
           :barrier? (opt :boolean false)}
          :any #{:world-write} nil 2)
    :block/set
    (node {:position (p* :vec3) :block-id (p) :expected-block-ids (opt :any nil)} nil #{:world-write})

    :motion/entity-velocity
    (node {:target (p* :entity-ref) :velocity (p* :vec3)} nil #{:world-write})
    :motion/entity-velocity-add
    (node {:target (p* :entity-ref) :velocity (p* :vec3)} nil #{:world-write})
    :motion/velocity
    (node {:velocity (p* :vec3) :dismount? (opt :boolean false) :reset-fall-damage? (opt :boolean false)}
          nil #{:world-write})
    :motion/flight
    (node {:direction (p* :vec3) :speed (p* :double) :acceleration (opt :double nil)
           :world-id (opt :string nil) :reset-fall-damage? (opt :boolean false)
           :near-ground-distance (opt :double nil) :near-ground-eye-height (opt :double nil)
           :hover-near-ground-velocity (opt :double nil) :hover-air-velocity (opt :double nil)}
          nil #{:world-write})

    :projectile/redirect
    (node {:entity (p* :entity-ref) :target-position (p* :vec3) :velocity (opt :vec3 nil)
           :replacement-types (opt :any nil) :difficulty (opt :any nil)}
          nil #{:world-write})
    :projectile/schedule-beam
    (node {:origin (p* :vec3) :destination (p* :vec3) :damage (p* :double) :owner (p)
           :damage-type (opt :keyword :generic) :delay-ticks (opt :long 0)
           :origin-selector (opt :any nil) :destination-selector (opt :any nil)
           :world-id (opt :string nil) :seed (opt :long nil) :settlement-vfx (opt :any nil)
           :exclude-owner? (opt :boolean true) :instance-key (opt :any nil)}
          nil #{:world-write} nil 3)

    :inventory/consume
    (node {:source (p) :count (p* :long)} nil #{:inventory-write})
    :inventory/place-or-drop
    (node {:source (p) :count (p* :long) :plan (opt :any nil) :creative? (opt :boolean false)}
          nil #{:inventory-write})
    :inventory/settle
    (node {:source (p) :count (p* :long) :position (opt :vec3 nil) :drop? (opt :boolean false)
           :creative? (opt :boolean false)}
          nil #{:inventory-write})}

   ;; --- cost/cooldown/resource/progression/score : action, owner-write --
   {:cost/spend
    (node {:budget (p* :keyword) :scale (opt :double 1.0) :partial? (opt :boolean false)}
          :boolean #{:owner-write} :cost/spend 1)
    :cooldown/start
    (node {:name (p* :keyword) :ticks (p* :long)} nil #{:owner-write} :cooldown/start 1)
    :resource/add
    (node {:resource (p* :keyword) :amount (p* :double)} nil #{:owner-write})
    :resource/enforce-floor
    (node {:resource (p* :keyword) :minimum (p* :double)} nil #{:owner-write})}

   ;; --- effect/vfx does NOT appear here: it is a `vfx!` DSL statement
   ;; (cn.li.node.compile/compile-vfx), an outbound signal appended
   ;; straight to the frame's own outbox, not a host query/action needing
   ;; capability dispatch -- see :vfx in cn.li.node.ir's op set and
   ;; cn.li.mcmod.runtime.effect-emit's separate :vfx instruction compiler.

   ;; --- damage/* : used only inside damage-policy programs (cn.li.combat
   ;; .damage), never a main ability :do -- they mutate the in-flight
   ;; damage REQUEST context.damage.clj compiles against, not the world. --
   {:damage/multiply
    (node {:multiplier (p* :double)} nil #{:damage-context-write})
    :damage/reduce
    (node {:rate (p* :double) :max-cost (opt :double nil) :cost-resource (opt :keyword nil)
           :ignore-threshold (opt :double nil) :progression-scale (opt :any nil) :vfx (opt :any nil)}
          nil #{:damage-context-write :owner-write})
    :damage/reflect
    (node {:multiplier (p* :double) :cost-per-damage (opt :double nil) :minimum (opt :double nil)
           :max-depth (opt :long nil) :cost-resource (opt :keyword nil) :progression-scale (opt :any nil)}
          nil #{:damage-context-write :owner-write})
    :damage/absorb
    (node {:cap (opt :double nil) :cost (opt :double nil) :interval-ticks (opt :long nil)
           :front? (opt :boolean false) :last-tick-path (opt :any nil)
           :progression-scale (opt :any nil) :progression-tag (opt :keyword nil)}
          nil #{:damage-context-write :owner-write})
    :damage/critical
    (node {:levels (p) :damage-types (opt :any nil) :progression-mode (opt :any nil)
           :progression-per-level (opt :any nil) :events (opt :any nil) :events-by-level (opt :any nil)
           :feedback (opt :any nil) :vfx (opt :any nil)}
          nil #{:damage-context-write})}))
