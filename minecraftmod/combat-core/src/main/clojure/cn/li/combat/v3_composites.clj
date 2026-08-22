(ns cn.li.combat.v3-composites
  "The first real combat :layer :mid composite (R4 pulled forward, mossy-
   wren plan): :fx/lightning-strike, the exact 'release lightning' example
   NODE_LANGUAGE.md section 8 used to motivate the whole node-language
   rework -- a single call combining a cost check, world lightning, damage,
   and a VFX signal, instead of an ability document hand-writing all four
   steps plus their guard logic inline every time.

   v1 targets a single entity (not an area): the area-damage composite
   this would naturally build on (:combat/area-damage, one of the R2 audit's
   6 downgraded components -- :target/entities + :flow/foreach +
   :combat/damage) is proper R4 scope, not yet built. Keeping this version
   honest about what's real rather than stubbing an area primitive that
   doesn't exist yet.

   ADDITIVE ONLY at this revision -- see host_primitives.clj's docstring."
  (:require [cn.li.node.descriptor :as node]))

(defn install!
  "Register :fx/lightning-strike. Call once per registry lifetime, after
   host_primitives.clj/policy_primitives.clj/structural_primitives.clj
   have installed the primitives this composite's body calls, before
   node/freeze!."
  []
  (node/register-composite!
   {:id :fx/lightning-strike :revision 1 :layer :mid
    :doc "Summon lightning at a position, damage one target, and spawn an impact VFX signal -- one call instead of hand-writing the cost check, :world/lightning, :combat/damage and :effect/vfx steps inline in every ability that wants this."
    :inputs {:target {:type :entity-ref} :position {:type :vec3}
             :power {:type :double :min 0.0 :doc "Damage dealt"}
             :budget {:type :map :doc "Resolved cost spec from :ability/budget"}}
    :outputs {}
    :body
    {:component :flow/sequence
     :steps
     [{:component :cost/spend :budget {:ref [:input :budget]} :bind {:insufficient? :insufficient?}}
      {:component :flow/branch :when {:ref [:local :insufficient?]}
       :then {:component :flow/finish :outcome :insufficient-resource}
       :else
       {:component :flow/sequence
        :steps
        [{:component :world/lightning :position {:ref [:input :position]}}
         {:component :combat/damage :target {:ref [:input :target]} :amount {:ref [:input :power]}}
         {:component :effect/vfx :effect-id :lightning-impact :operation :spawn
          :audience {:type :nearby :radius 64.0}
          :payload {:position {:ref [:input :position]}}}]}}]}}))
