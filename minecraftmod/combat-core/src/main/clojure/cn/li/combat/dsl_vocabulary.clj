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
   on insufficient resources, which is the idiomatic DSL shape anyway."
  (:require [clojure.string :as str]))

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

;; --- editor palette presentation (:category/:i18n), attached below ---------
;;
;; Derived from each node id's own namespace rather than a 6th positional
;; arg on every `node` call site above: touching ~54 call sites to add a
;; presentation-only field would be pure edit-surface risk against code
;; that already has real test coverage, for data that has nothing to do
;; with what a node DOES. This table is the single place category
;; assignment can drift, and it can only drift by omission -- a node
;; whose namespace is missing here keeps :uncategorized, which
;; schema-export_test.clj asserts never happens (see that test for why
;; this is a stronger completeness guarantee than a per-call-site
;; argument would have been: one exhaustive table beats 54 scattered ones).
(def ^:private category-by-namespace
  {"kernel" :kernel "random" :flow "target" :targeting "owner" :resource
   "energy" :resource "data" :flow "combat" :combat "entity" :combat
   "world" :world "block" :world "motion" :movement "projectile" :combat
   "inventory" :resource "cost" :resource "cooldown" :resource
   "resource" :resource "damage" :combat "terrain" :world})

(defn category-for
  "Public (not category-by-namespace itself): cn.li.node.schema-export/
   export-fns takes this as its category-for callback for cn.li.combat.
   lib/fns, since node-core cannot itself know combat-specific namespace
   groupings (see export-fns's own docstring)."
  [id]
  (get category-by-namespace (namespace id) :uncategorized))

(defn- i18n-for
  "editor.node.combat.<ns>.<name>, name's hyphens folded to underscores to
   match Minecraft's own translation-key convention (the same
   hyphen->underscore folding ac's own skill name-key/description-key
   generation already applies)."
  [id]
  (str "editor.node.combat." (namespace id) "." (str/replace (name id) "-" "_")))

(defn- attach-presentation
  "id->spec map -> the same map with :category/:i18n merged into every
   spec. The editor palette (cn.li.node.schema-export/export-vocab) and
   the player-effects grey-out derivation (export-player-effects) both
   read these off the SAME node map every other consumer uses -- there is
   no separate 'presentation vocabulary' to keep in sync."
  [nodes-map]
  (into {} (map (fn [[id spec]] [id (assoc spec :category (category-for id) :i18n (i18n-for id))])) nodes-map))

(def ^:private raw-nodes
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

    ;; Real content (scatter_bomb.edn, S6) referenced :vec3/scatter-end as
    ;; a :vec3/* pure op, but a random scatter deviation needs the
    ;; activation's own RNG cursor -- same reasoning as :random/chance
    ;; above, so this cannot be a :pure op no matter what its old
    ;; namespace implied. An exhaustive search of both combat-core and ac
    ;; found no implementation of the old opcode anywhere (the same class
    ;; of never-wired-up reference :vec3/launch turned out to be), so this
    ;; is a new node, not a port: a random point within a cone of
    ;; :angle-degrees around :direction from :origin, at :range.
    :kernel/scatter-end
    (node {:origin (p* :vec3) :direction (p* :vec3) :range (p* :double)
           :angle-degrees (p* :double)}
          :vec3 #{} :kernel/scatter-end 1)

    ;; A seeded probability roll needs the activation's own RNG cursor,
    ;; which only the host frame carries -- unlike node-core's pure ops
    ;; (cn.li.node.ops), which are deliberately seed-free (see that
    ;; namespace's docstring), so this is a :query, not a :pure op.
    :random/chance
    (node {:probability (p* :double)} :boolean #{} :random/chance 0)

    ;; Same reasoning as :random/chance above: cn.li.node.expr's
    ;; :random/int opcode reads the activation's RNG cursor, so it can
    ;; only ever be seeded correctly through the host frame -- ops/invoke
    ;; calls expr/evaluate with a hard-coded seed 0, which would make
    ;; every roll identical if this were a :pure op instead (a real bug
    ;; class this table exists to avoid, not a hypothetical one -- see
    ;; the :random/chance comment this mirrors). S6's ray_barrage.edn
    ;; (a randomized particle-fan count) is the first real caller.
    :random/int
    (node {:min (p* :long) :max (p* :long)} :long #{} :random/int 0)

    ;; Same reasoning as :random/int/:random/chance above.
    ;; body_intensify.edn's (S6) status-effect duration jitter
    ;; ((1 + uniform(0,1)) * effective-ticks) is the first real caller.
    :random/uniform
    (node {:min (p* :double) :max (p* :double)} :double #{} :random/uniform 0)}

   ;; --- target/* : query, world-read -----------------------------------
   {:target/raycast
    (node {:origin (p* :vec3) :direction (p* :vec3) :distance (p* :double)
           :include-entities? (opt :boolean false) :include-blocks? (opt :boolean false)
           :living-only? (opt :boolean false) :policy (opt :any nil)}
          :hit-result #{:world-read} :raycast 2)

    ;; :yaw-range-degrees is a [min max] range pair, not a scalar --
    ;; every real call site (blood_retrograde.edn, S6) passes a 2-element
    ;; vector, matching :pitch-angles' own already-:any shape. Same
    ;; mistake class as the earlier :direction/:entity-type fixes.
    ;;
    ;; :returns was also wrong -- shipped as nil (void/action), but a
    ;; raycast fan obviously produces a value (the old composite's own
    ;; :result :spray-hits, read back later as :surface-hits); every real
    ;; call site would have hit :void-let-rhs the moment anyone tried to
    ;; bind its result. Fixed to :any, matching target/raycast's own
    ;; :returns shape.
    :target/raycast-fan
    (node {:origin (p* :vec3) :direction (p* :vec3) :distance (p* :double)
           :yaw-range-degrees (opt :any 0.0) :pitch-angles (opt :any nil)
           :limit (opt :long 8) :seed (opt :long nil)}
          :any #{:world-read} :raycast 3)

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

    ;; :direction here is NOT a spatial vector despite the field-name
    ;; heuristic this whole table otherwise follows (see this namespace's
    ;; own docstring: ":direction -> :vec3") -- it is the old composite's
    ;; neutral movement-direction ENUM (:forward/:back/:left/:right
    ;; relative to :look), a real exception the mechanical derivation
    ;; missed. Caught by combat-core/lib_test.clj's directional-destination-
    ;; test failing at compile time (type-mismatch, wants :vec3 got
    ;; :keyword) when porting cn.li.combat.lib's target/directional-
    ;; destination composite -- no ability had exercised this node end to
    ;; end before that.
    :target/directional-destination-query
    (node {:look (p* :vec3) :eye-y (p* :double) :origin (p* :vec3) :direction (p* :keyword)
           :distance (p* :double) :policy (opt :any nil)}
          :any #{:world-read} :raycast 2)

    :owner/snapshot
    (node {:projection (opt :any nil)} :any #{:owner-read} :owner/snapshot 1)
    :owner/can-fly
    (node {:enabled? (p* :boolean)} nil #{:owner-write})

    :energy/target
    (node {:hit (p)} :any #{:world-read} :energy/target 1)

    ;; current_charging.edn's (S6) counterpart to :energy/target: push
    ;; :amount energy units into either a held item (:mode :item, :target
    ;; the item-held snapshot) or a targeted block's energy store
    ;; (:mode :block, :target the energy/target snapshot). No node
    ;; anywhere returned or consumed a result from this old component
    ;; (never :bind-bound), so :returns nil -- an action, not a query.
    :energy/charge
    (node {:mode (p* :keyword) :world-id (opt :string nil) :target (p) :amount (p* :double)}
          nil #{:world-write})

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

    ;; :entity-type is a Minecraft resource-location STRING ("academy:
    ;; entity_md_ball"), not a keyword despite the field-name heuristic
    ;; this table otherwise follows -- every real spawn site (electron_
    ;; bomb/electron_missile/light_shield/mag_manip/scatter_bomb.edn) uses
    ;; a string, and a keyword's namespace/name separator (/) does not
    ;; even round-trip to the same text as a resource location's (:).
    ;; Same mistake class as :target/directional-destination-query's
    ;; :direction fix; caught converting electron_bomb.edn for S6.
    :entity/spawn
    (node {:entity-type (p* :string) :position (p* :vec3) :velocity (opt :vec3 nil)
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
   ;; :budget is a RESOLVED resources descriptor ({:resources {resource-key
   ;; amount ...}}, or a bare {resource-key amount ...} map -- final_engine
   ;; .clj's own spend-budget handles both via
   ;; (or (:resources budget) budget {})), with resource keys as data, not
   ;; combat-core source (verifyCombatResourceAgnostic's own gate),
   ;; NOT a keyword name: the old engine's :ability/budget source read
   ;; already-materialized amounts straight out of :input, and real
   ;; content (location_teleport.edn, S6) also builds one ad hoc at
   ;; runtime for a distance-scaled cost -- a bare name has nowhere to put
   ;; either. A static per-ability budget now reads via the ?budget/name
   ;; sigil (cn.li.combat.run's capability-type types every ?budget/* as
   ;; :any for exactly this shape) and gets passed straight through.
   ;; Originally shipped as :keyword (wrong on both counts -- caught
   ;; converting location_teleport.edn for S6, after mine_detect.edn's
   ;; own conversion had already made the same mistake and had to be
   ;; fixed alongside this).
   {:cost/spend
    (node {:budget (p* :any) :scale (opt :double 1.0) :partial? (opt :boolean false)}
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

(def nodes (attach-presentation raw-nodes))
