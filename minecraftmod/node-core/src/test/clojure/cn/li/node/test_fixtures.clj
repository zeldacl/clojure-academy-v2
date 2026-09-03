(ns cn.li.node.test-fixtures
  "Shared vocab/capabilities/fns fixture for the surface-DSL compiler tests
   (compile_test, diagnostics_test, pretty_test, cost_test). Deliberately
   named without a _test.clj suffix so cn.li.test-support.pure-auto-test-
   runner does not try to run it as a test namespace on its own -- it is
   real vocabulary data, not a domain vocab (that is combat-core/vfx-core's
   own job once they are rewritten off the old engine), just enough of one
   to exercise every compiler feature end to end.")

(def capabilities
  {:caster/eye :vec3
   :caster/aim :vec3
   :world/id :string})

(def vocab
  {:target/raycast
   {:params {:from {:type :vec3} :dir {:type :vec3} :distance {:type :double}}
    :returns :hit-result :effects #{:world-read} :capability :raycast
    :barrier? true :cost 2}

   :target/entities
   {:params {:center {:type :vec3} :radius {:type :double}
             :limit {:type :long :default 24}}
    :returns [:list-of :entity-ref] :effects #{:world-read}
    :capability :entity/select :cost 2}

   :combat/damage
   {:params {:target {:type :entity-ref} :amount {:type :double}}
    :returns nil :effects #{:world-write} :capability :entity/damage :cost 3}

   :cooldown/start
   {:params {:name {:type :keyword} :ticks {:type :long}}
    :returns nil :effects #{:owner-write} :capability :cooldown/start :cost 1}})

(def fns
  {:ac/strike
   {:params [{:name 'target :type :entity-ref} {:name 'amount :type :double}]
    :body '[(combat/damage {:target target :amount amount})]}})

(def opts {:vocab vocab :capabilities capabilities :fns fns})

(def thunder-bolt-text
  "{:ability :thunder-bolt
    :activation :instant
    :tunables {:range {:type :double} :damage {:type :double} :aoe {:type :double}}
    :do
    [(let hit (target/raycast {:from ?caster/eye :dir ?caster/aim :distance $range}))
     (let end (vec3/add ?caster/eye (vec3/scale ?caster/aim $range)))
     (when (:entity-id hit)
       (ac/strike (:entity-id hit) $damage))
     (let targets (target/entities {:center (:position hit) :radius $aoe :limit 24}))
     (each t targets
       (ac/strike t $damage))
     (cooldown/start {:name :main :ticks 40})
     (finish {:outcome :performed :end-ability? true})]}")
