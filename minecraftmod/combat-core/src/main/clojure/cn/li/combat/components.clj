(ns cn.li.combat.components
  "Data-only component registry and startup compiler hooks.

   Component implementations are ordinary Clojure functions.  Content never
   receives the functions; it only references the keyword component ids.

   Schema v2: a descriptor is a port contract, not just a required-field
   list. `:children` declares every nested-node position a component can
   hold and how it is traversed (`:single`, `:seq`, `:case-map`) -- this is
   what lets the compiler (recipe.clj/dataflow.clj) discover child nodes
   generically instead of scanning a hardcoded global key list, which is
   the mechanism that let new node shapes silently skip validation before.
   `:produces` declares the named, typed values a component binds into its
   enclosing scope (`:result`, `:to`, ...); `:inputs` declares what it reads
   by key (informational at this revision -- dataflow.clj checks *slot*
   references, not per-field input types yet). `:effects` marks whether a
   component is pure (safe inside a `guard/*`) or mutates/emits."
  )

(defonce ^:private registry*
  (atom {:frozen? false :components {}}))

(defn register!
  [{:keys [id revision schema children produces effects] :as descriptor}]
  (when (:frozen? @registry*)
    (throw (ex-info "combat component registry is frozen" {:id id})))
  (when-not (and (keyword? id)
                 (integer? revision)
                 (map? schema)
                 (or (nil? children) (map? children))
                 (or (nil? produces) (map? produces))
                 (or (nil? effects) (set? effects)))
    (throw (ex-info "invalid combat component descriptor"
                    {:descriptor descriptor})))
  (when (contains? (:components @registry*) id)
    (throw (ex-info "duplicate combat component" {:id id})))
  (swap! registry* update :components assoc id
         (-> descriptor
             (update :children #(or % {}))
             (update :produces #(or % {}))
             (update :effects #(or % #{}))))
  id)

(defn freeze! []
  (swap! registry* assoc :frozen? true)
  (:components @registry*))

(defn reset-for-test! []
  (reset! registry* {:frozen? false :components {}})
  nil)

(defn descriptor [id]
  (get-in @registry* [:components id]))

(defn descriptors []
  (:components @registry*))

(defn- identity-lower [compiler node]
  (update compiler :ir into [{:ir/op :component
                              :component (:component node)
                              :component-index 0
                              :data node}]))

;; child-port kinds:
;;   :single    -- data[key] is one optional/required child node
;;   :seq       -- data[key] is a vector of child nodes
;;   :case-map  -- data[key] is a map of case-value -> child node
(defn register-builtins!
  "Register the generic component surface with its schema-v2 port contracts.

   `:owner/patch`/`:session/patch`/`:guard/resource` remain registered (and
   content-visible) through the schema v2 migration: production abilities
   still run on schema-version 1 EDN using them directly until Phase 5
   rewrites every ability onto the `:cost/spend`/`:score/mark` vocabulary.
   They are the only components schema v2 authors should not reach for in
   new content -- see docs on `:costs`/`:progression`/`:invariants`."
  []
  (doseq [[id spec]
          {:flow/phases
           {:schema {:required #{:start :pulse :release :abort}}
            :children {:start {:kind :single :required? false}
                       :pulse {:kind :single :required? false}
                       :release {:kind :single :required? false}
                       :abort {:kind :single :required? false}
                       :events {:kind :case-map :required? false}}}

           :flow/sequence
           {:schema {:required #{:steps}}
            :children {:steps {:kind :seq :required? true}}}

           :flow/branch
           {:schema {:required #{:when :then}}
            :children {:then {:kind :single :required? true}
                       :else {:kind :single :required? false}}}

           :flow/foreach
           {:schema {:required #{:items :as :body :limit}}
            :children {:body {:kind :single :required? true}}
            ;; :as names the per-item binding; the iterated value is scoped
            ;; to :body only -- see dataflow.clj's :iterates handling.
            :produces {}}

           :data/random-item
           {:schema {:required #{:items :result}}
            :effects #{:pure}
            :produces {:item {:name-field :result :type :object}}}

           :flow/window
           {:schema {:required #{:value :on-pass :on-fail}}
            :children {:on-pass {:kind :single :required? true}
                       :on-fail {:kind :single :required? true}}}

           :flow/once
           {:schema {:required #{:scope :storage-path :key}}
            :children {:on-first {:kind :single :required? false}
                       :on-duplicate {:kind :single :required? false}}}

           :flow/finish
           {:schema {:required #{:outcome}}}

           ;; Loop-scoped control signal (schema v2 design 0(e)): distinct
           ;; from :flow/finish, which terminates the whole program.
           ;; :skip-item/:break-loop are only meaningful inside a
           ;; :flow/foreach body -- see vm.clj's :flow/foreach handling.
           :flow/control
           {:schema {:required #{:signal}}}

           :data/bind
           {:schema {:required #{:to :value}}
            :produces {:bound {:name-field :to}}}

           :session/patch
           {:schema {:required #{:entries}} :effects #{:mutate}}

           :owner/patch
           {:schema {:required #{:entries}} :effects #{:mutate}}

           :txn/atomic
           {:schema {:required #{:guards :reservations :body}}
            :children {:guards {:kind :seq :required? true}
                       :reservations {:kind :seq :required? true}
                       :body {:kind :single :required? true}
                       :on-success {:kind :single :required? false}
                       :on-fail {:kind :single :required? false}}}

           :guard/resource
           {:schema {:required #{:cost}} :effects #{}}

           ;; Generic pure value guard: does :value equal one of :one-of.
           ;; Replaces the old :guard/held-item, which read
           ;; [:context :held-item] -- a slot nothing ever bound (U1
           ;; violation). Callers now query the actual value through a
           ;; declared port (e.g. :target/item-held) and check it here.
           :guard/value-in
           {:schema {:required #{:value :one-of}} :effects #{}}

           :target/raycast
           {:schema {:required #{:origin :direction :distance}}
            :effects #{:query}
            :produces {:hit {:name-field :result :type :hit-result}}}

           ;; Bounded fan of block rays around a direction.  Angles, jitter,
           ;; range and result limits are supplied by EDN; the host only
           ;; performs neutral block-ray queries.
           :target/raycast-fan
           {:schema {:required #{:origin :direction :distance
                                 :pitch-angles :yaw-range-degrees
                                 :limit :result}}
            :effects #{:query}
            :produces {:hits {:name-field :result :type :hit-list}}}

           ;; A raycast may ask the host to resolve the neutral landing point
           ;; (entity feet/eye offset, block-face offset and head clearance)
           ;; in the same bounded query.  The policy is supplied by EDN; the
           ;; host only performs platform collision/raycast reads.
           :target/resolve-destination
           {:schema {:required #{:hit :origin :direction :distance :result}}
            :effects #{:query}
            :produces {:destination {:name-field :result :type :destination}}}

           ;; Neutral block placement/drop planning.  The host resolves the
           ;; hit-face offset and platform permissions; the ability supplies
           ;; only the placement policy and consumes the returned plan.
           :target/block-placement
           {:schema {:required #{:hit :origin :direction :distance :result}}
            :effects #{:query}
            :produces {:destination {:name-field :result :type :block-placement}}}

           ;; Directional movement landing is a neutral query: the host owns
           ;; raycast/collision reads while the EDN supplies the direction and
           ;; landing policy.  It is deliberately separate from the generic
           ;; hit resolver because feet-to-eye rays and strafe directions are
           ;; useful to more than one movement ability.
           :target/directional-destination-query
           {:schema {:required #{:origin :look :eye-y :direction :distance :result}}
            :effects #{:query}
            :produces {:destination {:name-field :result :type :destination}}}

           :target/entities
           {:schema {:required #{:shape :projection :limit :result}}
            :effects #{:query}
            :produces {:entities {:name-field :result :type :entity-list}}}

           :target/entity-snapshot
           {:schema {:required #{:entity-id :projection :result}}
            :effects #{:query}
            :produces {:snapshot {:name-field :result :type :entity-snapshot}}}

           :owner/snapshot
           {:schema {:required #{:projection :result}}
            :effects #{:query}
            :produces {:snapshot {:name-field :result :type :owner-snapshot}}}

           :target/item-held
           {:schema {:required #{:source :result}}
            :effects #{:query}
            :produces {:held-item {:name-field :result :type :item-snapshot}}}

           :target/saved-location
           {:schema {:required #{:location-name :result}}
            :effects #{:query}
            :produces {:location {:name-field :result :type :world-position}}}

           :energy/target
           {:schema {:required #{:hit :result}}
            :effects #{:query}
            :produces {:energy-target {:name-field :result :type :energy-target}}}

           :target/blocks
           {:schema {:required #{:shape :projection :limit :result}}
            :effects #{:query}
            :produces {:blocks {:name-field :result :type :block-list}}}

           ;; Generic bounded terrain propagation. The host returns a
           ;; deterministic mutation plan; EDN decides how to apply it.
           :terrain/propagate
           {:schema {:required #{:origin :direction :max-iterations
                                 :initial-energy :spread :energy-cost
                                 :result}}
            :effects #{:query}
            :produces {:plan {:name-field :result :type :terrain-plan}}}

           :combat/damage
           {:schema {:required #{:target :amount}} :effects #{:mutate}}

           ;; Generic behavior-driven entities (projectiles, scripted bodies,
           ;; etc.) expose a neutral trigger port.  The behavior key is data
           ;; supplied by EDN; Combat Core never knows a concrete entity or
           ;; skill name.
           :entity/trigger-behavior
           {:schema {:required #{:entity}} :effects #{:mutate}}

           :entity/mark
           {:schema {:required #{:target :mark-type}} :effects #{:mutate}}

           :energy/charge
           {:schema {:required #{:mode :target :amount}} :effects #{:mutate}}

           :damage/reflect
           {:schema {:required #{:multiplier :cost-per-damage :minimum :max-depth}}
            :effects #{:mutate}}

           :damage/multiply
           {:schema {:required #{:multiplier}}
            :effects #{:mutate}}

           ;; Generic attacker-side critical transform. Levels, chances,
           ;; multipliers, progression and presentation signals are all data
           ;; supplied by the reaction document; Combat Core only performs
           ;; the bounded deterministic roll and preserves the original
           ;; damage components/type/target.
           :damage/critical
           {:schema {:required #{:levels :damage-types :exp-per-level}}
            :effects #{:mutate :emit}}

           ;; Generic incoming-damage absorption.  The AC boundary supplies
           ;; session state/resources; Combat Core only validates the neutral
           ;; reaction shape and never knows a skill id.
           :damage/absorb
           {:schema {:required #{:cap :interval-ticks :last-tick-path :cost}}
            :effects #{:mutate}}

           ;; Generic active damage reduction.  The reaction evaluator owns
           ;; the arithmetic and bounded partial resource payment; the
           ;; ability supplies only rates, thresholds and optional VFX data.
           :damage/reduce
           {:schema {:required #{:rate :max-cost :ignore-threshold :exp-scale}}
            :effects #{:mutate :emit}}

           ;; :block-limit is a plain scalar cap on how many discovered blocks
           ;; beam.clj/trace! returns -- not a :block/break-budget node. The
           ;; ability's own top-level :block/break-budget step (a real,
           ;; separately-executed component) is what actually commits the
           ;; discovered blocks, using its own :energy/:drop-chance/:seed.
           :host/beam-trace
           {:schema {:required #{:origin :direction :length :radius :damage :result}}
            :effects #{:query :mutate}
            :produces {:beam {:name-field :result :type :beam-result}}}

           :combat/impulse
           {:schema {:required #{:target :vector}} :effects #{:mutate}}

           :entity/radial-impulse
           {:schema {:required #{:center :radius :speed-min :speed-max :seed}}
            :effects #{:mutate}}

           :motion/flight
           {:schema {:required #{:direction :speed :acceleration
                                 :hover-near-ground-velocity
                                 :hover-air-velocity
                                 :near-ground-distance
                                 :near-ground-eye-height}}
            :effects #{:mutate}}

            :motion/velocity
            {:schema {:required #{:velocity}}
             ;; Optional flags are forwarded to the neutral host so movement
             ;; abilities can request the same safe physics boundary without
             ;; introducing a skill-specific movement component.
             :effects #{:mutate}}

           :owner/can-fly
           {:schema {:required #{:enabled?}} :effects #{:mutate}}

           :combat/status
           {:schema {:required #{:target :status-id :duration-ticks}}
            :effects #{:mutate}}

           :entity/teleport
           {:schema {:required #{:target :position}}
            :effects #{:mutate}}

           :entity/teleport-group
           {:schema {:required #{:position :radius}}
            :effects #{:mutate}}

           :entity/reset-fall-damage
           {:schema {:required #{:target}}
            :effects #{:mutate}}

           :interaction/dispatch
           {:schema {:required #{:kind :target :result}}
            :effects #{:mutate}
            :produces {:outcome {:name-field :result :type :object}}}

           :inventory/consume
           {:schema {:required #{:source :count}} :effects #{:mutate}}

           ;; Generic main-hand settlement: drop a copy at a world position,
           ;; or consume one item when the drop policy is false.  Creative
           ;; mode is an explicit data input, never a skill rule.
           :inventory/settle
           {:schema {:required #{:source :count :position :drop? :creative?}}
            :effects #{:mutate}}

           ;; Atomically place the held block at the planned face, or drop it
           ;; at the neutral fallback point.  This is reusable for any
           ;; ability that relocates a placeable main-hand item.
           :inventory/place-or-drop
           {:schema {:required #{:source :count :plan :creative?}}
            :effects #{:mutate}}

           :entity/spawn
           {:schema {:required #{:entity-type :owner :position}} :effects #{:mutate}}

           :entity/discard
           {:schema {:required #{:entity}} :effects #{:mutate}}

           :entity/configure
           {:schema {:required #{:entity}} :effects #{:mutate}}

           :motion/entity-velocity
           {:schema {:required #{:target :velocity}} :effects #{:mutate}}

           :motion/entity-velocity-add
           {:schema {:required #{:target :velocity}} :effects #{:mutate}}

           :projectile/schedule-beam
           {:schema {:required #{:origin :destination :damage :damage-type :delay-ticks}}
            :effects #{:mutate :emit}}

           :block/break-budget
           {:schema {:required #{:blocks :energy :limit}} :effects #{:mutate}}

           :block/break
           {:schema {:required #{:position}} :effects #{:mutate}}

           :block/set
           {:schema {:required #{:position :block-id}} :effects #{:mutate}}

           :block/random-break
           {:schema {:required #{:origin :attempts :radius :hardness-max :seed}}
            :effects #{:mutate}}

           ;; Deterministic bounded cube scan with EDN-supplied hardness and
           ;; drop policies.  This is distinct from random sampling because
           ;; callers that promise area coverage must not silently miss
           ;; eligible blocks due to duplicate random coordinates.
           :block/area-break
           {:schema {:required #{:origin :radius :hardness-max :seed}}
            :effects #{:mutate}}

           :world/sound
           {:schema {:required #{:sound-id :position}} :effects #{:emit}}

           :world/lightning
           {:schema {:required #{:position}} :effects #{:mutate}}

           ;; Neutral terrain/entity explosion primitive. Radius and flags are
           ;; supplied by the ability document; the host only validates the
           ;; bounded request and delegates the world mutation.
           :world/explosion
           {:schema {:required #{:position :radius}} :effects #{:mutate}}

           :projectile/redirect
           {:schema {:required #{:entity :target-position :velocity :difficulty}}
            :effects #{:mutate}}

           :resource/enforce-floor
           {:schema {:required #{:resource :minimum}} :effects #{:mutate}}

           :resource/add
           {:schema {:required #{:resource :amount}} :effects #{:mutate}}

           :effect/vfx
           {:schema {:required #{:effect-id :operation :payload}} :effects #{:emit}}

           :domain/event
           {:schema {:required #{:event-type :payload}} :effects #{:emit}}

           ;; --- schema v2 policy vocabulary (Design A) ---
           :cost/spend
           {:schema {:required #{:budget}} :effects #{:mutate}
            :children {:on-insufficient {:kind :single :required? false}}}

           :score/mark
           {:schema {:required #{:tag}} :effects #{:mutate}}

           :cooldown/start
           {:schema {:required #{:name}} :effects #{:mutate}}}]
    (when-not (descriptor id)
      (register! (assoc spec :id id :revision 1 :lower identity-lower))))
  nil)
