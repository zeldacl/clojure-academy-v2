(ns cn.li.combat.v3-composites
  "combat's :layer :mid composites (R4, mossy-wren plan).

   :fx/lightning-strike -- the exact 'release lightning' example
   NODE_LANGUAGE.md section 8 used to motivate the whole node-language
   rework -- a single call combining a cost check, world lightning, damage,
   and a VFX signal, instead of an ability document hand-writing all four
   steps plus their guard logic inline every time. v1 targets a single
   entity (not an area).

   :combat/area-damage, :combat/radial-impulse, :combat/teleport-group,
   :combat/area-break -- 4 of the R2 audit's 6 downgraded components
   rebuilt as real composites (:host/beam-trace, :block/random-break,
   :block/break-budget remain -- the first needs a new pure query
   primitive this pass didn't build, the other two need a 'repeat N times'
   primitive combat's vocabulary doesn't have yet, since :flow/foreach
   only iterates a supplied items list, not a bare count; deferred rather
   than guessed at). Each of the 4 built here is the same shape: a single
   opaque host call that bundled 'find entities/blocks in a shape, then
   loop applying an effect to each' is now genuinely :target/entities (or
   :target/blocks) + :flow/foreach + one existing per-item primitive,
   visible composition instead of one black box.

   :target/entities' element shape is an assumption this composite bakes
   in ({:id ...} snapshot maps, read via {:ref [:local :target :id]}) --
   the real host query handler's actual :entity-list element shape is
   platform-defined and unverified in this environment; tests here
   exercise the composition mechanics against a self-consistent fake query
   response, not real host integration.

   ADDITIVE ONLY at this revision -- see host_primitives.clj's docstring."
  (:require [cn.li.node.descriptor :as node]))

(defn install!
  "Register combat's v3 composites. Call once per registry lifetime, after
   host_primitives.clj/policy_primitives.clj/structural_primitives.clj
   have installed the primitives these composites' bodies call, before
   node/freeze!."
  []
  (node/register-composite!
   {:id :combat/area-damage :revision 1 :layer :mid
    :doc "Damage every entity within :radius of :center. Rebuilds :entity/radial-impulse-style bundled query+loop as visible composition: :target/entities finds the entities, :flow/foreach applies :combat/damage to each."
    :inputs {:center {:type :vec3} :radius {:type :double :min 0.0}
             :amount {:type :double :min 0.0} :limit {:type :long :default 128}}
    :outputs {}
    :body
    {:component :flow/sequence
     :steps
     [{:component :target/entities
       :shape {:type :sphere :center {:ref [:input :center]} :radius {:ref [:input :radius]}}
       :projection [:id] :limit {:ref [:input :limit]}
       :bind {:entities :entities}}
      {:component :flow/foreach :items {:ref [:local :entities]} :as :target
       :body {:component :combat/damage
              :target {:ref [:local :target :id]} :amount {:ref [:input :amount]}}}]}})
  (node/register-composite!
   {:id :combat/radial-impulse :revision 1 :layer :mid
    :doc "Push every entity within :radius of :center outward with :speed. Rebuilds :entity/radial-impulse's bundled query+loop as visible composition."
    :inputs {:center {:type :vec3} :radius {:type :double :min 0.0} :speed {:type :double} :limit {:type :long :default 128}}
    :outputs {}
    :body
    {:component :flow/sequence
     :steps
     [{:component :target/entities
       :shape {:type :sphere :center {:ref [:input :center]} :radius {:ref [:input :radius]}}
       :projection [:id :position] :limit {:ref [:input :limit]}
       :bind {:entities :entities}}
      {:component :flow/foreach :items {:ref [:local :entities]} :as :target
       :body
       {:component :combat/impulse
        :target {:ref [:local :target :id]}
        :vector {:expr :vec3/scale
                 :args [{:expr :vec3/normalize
                         :args [{:expr :vec3/sub :args [{:ref [:local :target :position]} {:ref [:input :center]}]}]}
                        {:ref [:input :speed]}]}}}]}})
  (node/register-composite!
   {:id :combat/teleport-group :revision 1 :layer :mid
    :doc "Teleport every entity within :radius of :position to :position. Rebuilds :entity/teleport-group's bundled query+loop as visible composition."
    :inputs {:position {:type :vec3} :radius {:type :double :min 0.0} :limit {:type :long :default 128}}
    :outputs {}
    :body
    {:component :flow/sequence
     :steps
     [{:component :target/entities
       :shape {:type :sphere :center {:ref [:input :position]} :radius {:ref [:input :radius]}}
       :projection [:id] :limit {:ref [:input :limit]}
       :bind {:entities :entities}}
      {:component :flow/foreach :items {:ref [:local :entities]} :as :target
       :body {:component :entity/teleport
              :target {:ref [:local :target :id]} :position {:ref [:input :position]}}}]}})
  (node/register-composite!
   {:id :combat/area-break :revision 1 :layer :mid
    :doc "Break every block within :radius of :origin whose hardness is at most :hardness-max. Rebuilds :block/area-break's bundled deterministic-scan+loop as visible composition."
    :inputs {:origin {:type :vec3} :radius {:type :double :min 0.0}
             :hardness-max {:type :double} :limit {:type :long :default 256}}
    :outputs {}
    :body
    {:component :flow/sequence
     :steps
     [{:component :target/blocks
       :shape {:type :sphere :center {:ref [:input :origin]} :radius {:ref [:input :radius]}}
       :projection {:max-hardness {:ref [:input :hardness-max]}} :limit {:ref [:input :limit]}
       :bind {:blocks :blocks}}
      {:component :flow/foreach :items {:ref [:local :blocks]} :as :block
       :body {:component :block/break :position {:ref [:local :block :position]}}}]}})
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
