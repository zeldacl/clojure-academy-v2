(ns cn.li.combat.source-nodes
  "The six :layer :source descriptors an ability document's own top level
   may use to read environment state it does not own (NODE_LANGUAGE.md
   section 5). Registered into node-core's shared registry -- this is where
   the neutral capability table AC's `caster-facade`
   (ac/.../combat_runtime.clj) currently exposes through the old
   `{:from :caster/...}` value form becomes an explicit, typed, enumerable
   node instead of an untyped keyword lookup into a flat map.

   ADDITIVE ONLY at this revision (R2 in-progress): registering these
   descriptors does not yet change what vm.clj executes -- no ability
   document references them yet, and the old `{:from}`/`{:tunable}`/
   `{:invariant}` value forms keep working unchanged until every ability is
   rewritten (R5) and the old forms are deleted in one cutover commit. See
   the mossy-wren plan's R2 section for why an immediate cutover would leave
   all 39 abilities failing to compile for the length of R2-R4."
  (:require [cn.li.node.descriptor :as node]))

(defn install!
  "Register the six source-node descriptors. Call once per registry
   lifetime, before node/freeze!."
  []
  (node/register-composite!
   {:id :ability/caster :revision 1 :layer :source
    :doc "The caster's position/orientation/progression snapshot at activation -- the typed replacement for the old flat {:from :caster/...} facade lookup."
    :category :source
    :outputs
    {:eye {:type :vec3 :doc "Eye position"}
     :eye-y {:type :double :doc "Eye Y coordinate alone, for ability math that only needs height"}
     :body {:type :vec3 :doc "Feet/body position"}
     :aim {:type :vec3 :doc "Look direction (unit vector)"}
     :id {:type :string :doc "Caster's owner id"}
     :world-id {:type :string :doc "World the caster is currently in"}
     :creative? {:type :boolean}
     :forward {:type :vec3 :doc "Unit vector along :aim, horizontal plane ignored"}
     :back {:type :vec3}
     :left {:type :vec3}
     :right {:type :vec3}
     :charge-ticks {:type :long :doc "Ticks the current activation has been held, for hold-to-charge abilities"}
     :normal-metal-blocks {:type [:list-of :keyword] :doc "Configured target registry snapshot, never read from AC config paths directly by EDN"}
     :weak-metal-blocks {:type [:list-of :keyword]}
     :metal-entities {:type [:list-of :keyword]}
     :mastery {:type :double :doc "Raw (pre-curve) skill-exp for this ability -- the v3 rename of the old {:from :progression/mastery} facade lookup."}
     :level {:type :long :doc "Owner's current ability level -- the v3 rename of the old {:from :progression/level} facade lookup."}
     :seed {:type :long :doc "This activation's seed, for deterministic random content -- the v3 rename of the old {:from :rng/seed} facade lookup."}}})

  (node/register-composite!
   {:id :ability/tunable :revision 1 :layer :source
    :doc "One tunable value already curve-resolved for this activation (see skill_config's mastery-lerp/affine/const/pair curves). Compile-time checked against the ability document's own :tunables declaration -- referencing an undeclared name is a compile error here, not a runtime throw."
    :category :source
    :inputs {:name {:type :keyword :doc "Must match a key in this document's own :tunables"}}
    :outputs {:value {:type :any}}
    :reads-environment #{:tunables}})

  (node/register-composite!
   {:id :ability/budget :revision 1 :layer :source
    :doc "One cost budget declared in this document's own :costs, for use with :cost/spend."
    :category :source
    :inputs {:name {:type :keyword :doc "Must match a key in this document's own :costs"}}
    :outputs {:budget {:type :map}}
    :reads-environment #{:costs}})

  (node/register-composite!
   {:id :ability/progression :revision 1 :layer :source
    :doc "One progression entry declared in this document's own :progression, for use with :score/mark."
    :category :source
    :inputs {:name {:type :keyword :doc "Must match a key in this document's own :progression"}}
    :outputs {:progression {:type :map}}
    :reads-environment #{:progression}})

  (node/register-composite!
   {:id :ability/cooldown :revision 1 :layer :source
    :doc "One cooldown entry declared in this document's own :cooldown, for use with :cooldown/start."
    :category :source
    :inputs {:name {:type :keyword :doc "Must match a key in this document's own :cooldown"}}
    :outputs {:cooldown {:type :map}}
    :reads-environment #{:cooldown}})

  (node/register-composite!
   {:id :ability/invariant :revision 1 :layer :source
    :doc "One constant declared in this document's own :invariants block."
    :category :source
    :inputs {:name {:type :keyword :doc "Must match a key in this document's own :invariants"}}
    :outputs {:value {:type :any}}
    :reads-environment #{:invariants}})

  (node/register-composite!
   {:id :ability/context :revision 1 :layer :source
    :doc "One ad hoc field from this activation's caller-supplied :context map (e.g. a UI-selected :location-name, the old v2 {:ref [:context ...]} value form) -- unlike :tunable/:budget/:progression/:cooldown/:invariant, :context has no static per-ability schema (the caller may pass different keys on different activations), so an absent name resolves to nil instead of being a compile-time or runtime error."
    :category :source
    :inputs {:name {:type :keyword :doc "A key the caller's own :context map may or may not provide"}}
    :outputs {:value {:type :any}}
    :reads-environment #{:context}}))
