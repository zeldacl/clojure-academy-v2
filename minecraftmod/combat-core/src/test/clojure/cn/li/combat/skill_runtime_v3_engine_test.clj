(ns cn.li.combat.skill-runtime-v3-engine-test
  "Proves cn.li.combat.skill-runtime/execute! itself is engine-pluggable:
   an ability compiled with :engine :v3 runs its :compiled-program (a raw
   node tree) through cn.li.node.flow/execute! instead of the v2 opcode
   VM, but comes out wrapped in the EXACT SAME result contract
   (:schema-version/:vfx-signals/:actions/:events/:status) execute!'s own
   post-processing already applies uniformly -- so
   cn.li.ac.ability.service.combat-runtime needs ZERO changes to run a v3
   ability: dispatch!/execute-combat-intent!/finalize-result! all stay as
   they are, engine selection happens entirely inside combat-core based
   on the ability's own :engine field. No ability currently sets that
   field, so every v2 ability's behavior is provably unchanged (see
   cn.li.combat.host-primitives-test and friends, unmodified, still
   green) -- this test is the other half: an ability that DOES set it."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.node.descriptor :as node]
            [cn.li.node.flow :as node-flow]
            [cn.li.combat.source-nodes :as source-nodes]
            [cn.li.combat.host-primitives :as host-primitives]
            [cn.li.combat.policy-primitives :as policy-primitives]
            [cn.li.combat.structural-primitives :as structural]
            [cn.li.combat.skill-runtime :as skill-runtime]
            [cn.li.combat.components :as components]
            [cn.li.combat.recipe :as recipe]
            [cn.li.mcmod.runtime.capabilities :as capabilities]))

(defn- fresh-node-registry! []
  (node/reset-for-test!)
  (node-flow/install!)
  (source-nodes/install!)
  (host-primitives/install!)
  (policy-primitives/install!)
  (structural/install!))

(def ^:private v3-ability
  {:engine :v3
   :costs {:release {:resources {:cp 3.0}}}
   :compiled-program
   {:component :flow/phases
    :start
    {:component :flow/sequence
     :steps [{:component :ability/tunable :name :damage :bind {:value :dmg}}
             {:component :ability/budget :name :release :bind {:budget :budget}}
             {:component :cost/spend :budget {:ref [:local :budget]} :bind {:insufficient? :insufficient?}}
             {:component :flow/branch
              :when {:ref [:local :insufficient?]}
              :then {:component :flow/finish :outcome :insufficient-resource}
              :else {:component :flow/sequence
                     :steps [{:component :combat/damage :target "zombie-1" :amount {:ref [:local :dmg]}}
                             {:component :effect/vfx :effect-id :impact :operation :spawn :payload {}}
                             {:component :flow/finish :outcome :performed}]}}]}
    :pulse {:component :flow/finish :outcome :ticked}
    :release {:component :flow/finish :outcome :released}
    :abort {:component :flow/finish :outcome :aborted}
    :events {}}})

(defn- catalog-with [ability]
  {:combat {:abilities {:test/v3-ability ability}}})

(deftest v3-ability-runs-through-execute-with-the-v2-compatible-result-shape-test
  (fresh-node-registry!)
  (let [result (skill-runtime/execute!
                (catalog-with v3-ability) :test/v3-ability "owner-1"
                {:action :start
                 :from {:caster/id "owner-1"}
                 :tunables {:damage 12.0}
                 :context {:resources {:cp 10.0}}
                 :activation-seed 5})]
    (is (= 2 (:schema-version result)))
    (is (= :accepted (:status result)))
    (is (= :test/v3-ability (:ability-id result)))
    (is (= "owner-1" (:owner result)))
    (is (some #(and (= :entity/damage (:capability %)) (= "zombie-1" (:target %)) (= 12.0 (:amount %)))
              (:actions result)))
    (is (= 1 (count (:vfx-signals result))))
    (is (= :impact (:effect-id (first (:vfx-signals result)))))
    (is (empty? (:events result)))))

(deftest v3-ability-branches-to-insufficient-resource-and-collects-no-damage-action-test
  (fresh-node-registry!)
  (let [previous-cp-cost (get-in v3-ability [:costs :release :resources :cp])
        result (skill-runtime/execute!
                (catalog-with v3-ability) :test/v3-ability "owner-1"
                {:action :start
                 :from {:caster/id "owner-1"}
                 :tunables {:damage 12.0}
                 :context {:resources {:cp 0.0}}
                 :activation-seed 5})]
    (is (= :accepted (:status result)) "execute! itself always reports :accepted for v3 -- see execute-v3!'s docstring")
    (is (nil? (some #(= :entity/damage (:capability %)) (:actions result)))
        "the branch never reached :combat/damage, so nothing was collected to apply")
    (is (empty? (:vfx-signals result)))
    (is (some? previous-cp-cost) "sanity: the ability really does declare a cp cost")))

(def ^:private v3-ability-with-expr-progression
  {:engine :v3
   :progression {:hit {:per-mark {:expr :math/mul :args [{:tunable :exp-base} {:tunable :exp-hit-factor}]}}}
   :compiled-program
   {:component :flow/sequence
    :steps [{:component :ability/progression :name :hit :bind {:progression :hit-progression}}
            {:component :score/mark :progression {:ref [:local :hit-progression]}}
            {:component :flow/finish :outcome :performed}]}})

(deftest v3-ability-resolves-an-expr-wrapped-tunable-ref-inside-progression-test
  ;; :costs/:progression/:cooldown are static document metadata, not
  ;; :program node trees -- v2's own opcode VM resolves both a bare
  ;; {:tunable k} AND a computed {:expr op :args [...]} found inside one
  ;; of these tables inline; a real ability (threatening_teleport.edn)
  ;; declares exactly this shape for its per-mark formula. Regression for
  ;; skill_runtime.clj's resolve-tunable-refs, which used to only replace
  ;; bare {:tunable k} maps and left a wrapping {:expr ...} untouched,
  ;; reaching :score/mark as a map where a number was expected.
  (fresh-node-registry!)
  (let [result (skill-runtime/execute!
                (catalog-with v3-ability-with-expr-progression) :test/v3-ability "owner-1"
                {:action :start :from {:caster/id "owner-1"}
                 :tunables {:exp-base 0.1 :exp-hit-factor 2.0}})]
    (is (= :accepted (:status result)))
    (is (= :performed (:outcome result)))
    (is (some #(and (= :owner-patch (:type %))
                    (= 0.2 (:value (first (:entries %)))))
              (:actions result))
        "0.1 * 2.0 = 0.2, not a raw {:expr ...} map")))

(deftest v2-ability-execution-is-unaffected-by-the-engine-branch-test
  ;; A minimal, ordinary v2 ability (no :engine key) proves the v2 branch
  ;; (execution-frame/host construction, vm/execute!) still runs exactly
  ;; as before -- execute!'s (when-not v3? ...) guards don't accidentally
  ;; skip anything a v2 ability needs.
  (components/reset-for-test!)
  (let [ability {:schema-version 1 :kind :ability :id :test/v2-ability :revision 1
                 :activation :instant
                 :program {:component :flow/finish :outcome :done}}
        result (skill-runtime/execute!
                {:combat {:abilities {:test/v2-ability (recipe/compile-ability ability)}}}
                :test/v2-ability "owner-1" {:action :start})]
    (is (= :accepted (:status result)))))
