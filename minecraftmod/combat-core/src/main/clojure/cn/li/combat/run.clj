(ns cn.li.combat.run
  "Combat's executable pipeline: surface DSL -> IR (cn.li.node.compile) ->
   CompiledProgram (cn.li.mcmod.runtime.effect-emit) -> dispatch, wired
   together with combat's own vocabulary (cn.li.combat.dsl-vocabulary), a
   capability TYPE table, and the pure-op table. S8 cutover: this is now
   the live production dispatch path -- wired into cn.li.combat.api
   (compile-skill-doc!/compile-skill-program/dispatch-skill!) and reached
   by every real player action via cn.li.ac.ability.service.combat-
   runtime/dispatch-intent-v2!. No legacy graph engine is loaded or retained;
   this single V3 path is exercised by the catalog/runtime test suite -- see
   NODE_LANGUAGE.md §0 for the language boundary. All AC V3 skills
   (ac/skills-v3/*.edn) compile through this path.

   capability-type is a FUNCTION, not a static map: ?budget/fire,
   ?cooldown/main, ?progression/effective etc are named per-ability by its
   own :costs/:cooldown/:progression declarations, not fixed repo-wide, so
   their type is derived from the capability's NAMESPACE (every ?budget/*
   is :double, every ?cooldown/* is :long, ...) rather than enumerated one
   name at a time. Only the caster/world capabilities that are the same
   for every ability (?caster/eye, ?world/id, ...) are a fixed table."
  (:require [cn.li.node.compile :as compile]
            [cn.li.node.ops :as ops]
            [cn.li.node.surface :as surface]
            [cn.li.combat.dsl-vocabulary :as vocab]
            [cn.li.mcmod.runtime.effect-emit :as emit]))

(def ^:private fixed-capabilities
  "Capabilities present on every activation regardless of which ability
   declared what -- the old system's caster-capability-values/entry-frame
   ports (the V3 capability source nodes folded here
   into the ?cap sigil uniformly, and combat_runtime.clj's :capabilities
   construction for the real name list)."
  {:caster/eye :vec3 :caster/aim :vec3 :caster/body :vec3
   ;; :entity-ref, not :string: real content (mine_detect.edn's
   ;; combat/status :target, S6) uses ?caster/id as a self-target for
   ;; entity-taking nodes far more often than as opaque string data
   ;; (a UUID for network/storage is the underlying repr, but that never
   ;; surfaces as a DSL-visible :string operation anywhere in real
   ;; content) -- and cn.li.node.types/assignable? has no :string<->
   ;; :entity-ref conversion, so typing it :string made every real
   ;; self-target call site a compile error.
   :caster/id :entity-ref :caster/creative? :boolean
   :world/id :string
   :charge/ticks :double
   :progression/mastery :double :progression/level :long
   :rng/seed :long
   ;; A server-side region/permission gate, not per-ability-named like
   ;; ?budget/* or ?cooldown/* -- mine_ray.edn (S6) is the first real
   ;; content to read it, guarding every block-mining branch.
   :ability/destroy-blocks? :boolean
   ;; Server-configured magnetic-material allowlists, not real per-player
   ;; state despite living under the :caster/* namespace in the old
   ;; content's own ability/caster bind -- mag_movement.edn (S6) is the
   ;; first real content to read them, each a list of normalized block/
   ;; entity-type ids checked via collection/contains?.
   :caster/normal-metal-blocks :any :caster/weak-metal-blocks :any
   :caster/metal-entities :any
   ;; The caster's eye height as a scalar offset (distinct from
   ;; :caster/eye's full world-space vec3) -- flashing.edn (S6) is the
   ;; first real content to read it, feeding
   ;; target/directional-destination's own :eye-y param.
   :caster/eye-y :double})

(defn capability-type [key]
  (or (get fixed-capabilities key)
      (case (namespace key)
        ;; :any, not :double: a ?budget/name capability is the WHOLE
        ;; already-materialized resources descriptor (resource-key ->
        ;; amount, or {:resources {...}}), the same shape cn.li.combat.dsl-
        ;; vocabulary's :cost/spend :budget param takes -- see that
        ;; node's own docstring for why. A single number had no field to
        ;; carry more than one resource's cost, which every real
        ;; multi-resource budget (S6's mine_detect.edn and
        ;; location_teleport.edn both spend two resources per activation)
        ;; needs.
        "budget" :any
        "cooldown" :long
        "progression" :double
        ;; ?invariant/name (the V3 :ability/invariant source,
        ;; e.g. a toggle ability's per-tick resource floor, S6) is always
        ;; a single tunable-derived number in every real ability seen so
        ;; far -- unlike ?budget/*, which genuinely needs a whole
        ;; multi-resource descriptor.
        "invariant" :double
        "context" :any
        "targeting" :any
        ;; A speculative placeholder with no real content behind it until
        ;; storm_wing.edn (S6): every ?movement/name it reads
        ;; (forward/right/back/left) is a direction VECTOR consumed both
        ;; as a motion/flight :direction and as vfx orientation fields,
        ;; never a boolean -- the first real usage revealed the original
        ;; guess was wrong, the same class of fix already applied to
        ;; :target/raycast-fan's :yaw-range-degrees and :entity/spawn's
        ;; :entity-type.
        "movement" :vec3
        nil)))

(def invoke-op
  "cn.li.node.ops/invoke directly: every :pure op this vocabulary's
   :params/:returns signatures declare is exactly an cn.li.node.expr
   opcode, so there is no combat-specific extension to layer on here."
  ops/invoke)

(defn compile-doc!
  "text -> IR. fns: {fn-id normalized-:defn-doc} for reusable functions
   (cn.li.node.surface/parse's shape), keyed by :id."
  ([text] (compile-doc! text {}))
  ([text fns]
   (compile/compile! (surface/parse text)
                     {:vocab vocab/nodes :capabilities capability-type :fns fns})))

(defn compile-program
  "IR -> CompiledProgram, wired to `host` (see
   cn.li.mcmod.runtime.effect-emit's namespace docstring for the exact
   {:query! :command! :flush!} shape a real host must satisfy)."
  [ir host]
  (emit/compile-program ir {:invoke-op invoke-op :host host}))

(defn dispatch!
  "Run `program` from `entry` against a fresh frame built for `input`.
   Returns the ExecutionFrame after dispatch -- .-result/.-actions/.-vfx/
   .-events/.-stateWrites carry the outcome, per
   cn.li.mcmod.runtime.effect.ExecutionFrame's docstring."
  [program entry input]
  (emit/dispatch! program entry (emit/new-frame program input)))
