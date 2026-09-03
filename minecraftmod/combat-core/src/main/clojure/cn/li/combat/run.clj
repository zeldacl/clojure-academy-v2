(ns cn.li.combat.run
  "Combat's executable pipeline: surface DSL -> IR (cn.li.node.compile) ->
   CompiledProgram (cn.li.mcmod.runtime.effect-emit) -> dispatch, wired
   together with combat's own vocabulary (cn.li.combat.dsl-vocabulary), a
   capability TYPE table, and the pure-op table. ADDITIVE alongside the old
   final_engine.clj -- see the redesign plan's staging notes; nothing here
   is wired into cn.li.combat.api yet, and none of ac's 39 abilities
   compile through this path until they are rewritten to the new DSL.

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
   ports (see final_engine.clj's :ability/caster source node, folded here
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
   :rng/seed :long})

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
        "context" :any
        "targeting" :any
        "movement" :boolean
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
