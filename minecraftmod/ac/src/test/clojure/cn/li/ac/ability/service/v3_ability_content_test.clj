(ns cn.li.ac.ability.service.v3-ability-content-test
  "Proves the full v3 pipeline works with REAL shipped content, not just
   this session's own test fixtures: :electromaster/brain-course
   (ac/combat/abilities/brain_course.edn) is the first real ability
   marked :engine :v3, loaded through the real manifest and
   combat-catalog/initialize! exactly like every v2 ability, and its
   :program runs through cn.li.combat.skill-runtime/execute!'s v3 branch
   to a real :accepted result -- proving cn.li.combat.recipe/compile-ability's
   engine branch, cn.li.combat.skill-runtime/execute!'s engine branch, and
   the whole AC bootstrap path all compose correctly end to end.

   brain_course.edn's :program is deliberately trivial (every phase is
   just :flow/finish :outcome :passive, unchanged from what a v2 program
   for this ability already looked like -- see NODE_LANGUAGE.md's
   :flow/phases/:flow/finish, node-core builtins shared by both engines)
   -- its real gameplay effect is :passive-effects, a separate mechanism
   cn.li.ac.ability.registry.category/combat-passives already applies
   independently of :program execution. This is intentionally the
   lowest-risk possible first real conversion: proving the pipeline
   itself, not exercising the v3 primitive/composite library (already
   covered thoroughly by cn.li.combat.skill-runtime-v3-engine-test and
   friends with synthetic content)."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.mcmod.runtime.capabilities :as capabilities]
            [cn.li.node.flow :as node-flow]
            [cn.li.combat.structural-primitives :as structural]
            [cn.li.ac.ability.service.combat-catalog :as catalog]
            [cn.li.combat.skill-runtime :as skill-runtime]))

(defn- with-fake-raycast-handler [hit f]
  (let [previous (get (:queries (capabilities/snapshot)) :raycast)]
    (try
      (capabilities/register-query! :raycast (fn [_request _frame] hit))
      (f)
      (finally
        (when previous
          (capabilities/register-query! :raycast previous))))))

(deftest brain-course-compiles-with-engine-v3-and-is-available-test
  (let [state (catalog/initialize!)
        ability (get-in state [:combat :abilities :electromaster/brain-course])]
    (is (nil? (get-in state [:combat :errors :electromaster/brain-course])))
    (is (= :v3 (:engine ability)))
    (is (map? (:compiled-program ability)))
    (is (catalog/available? :electromaster/brain-course))))

(deftest brain-course-v3-program-executes-to-accepted-test
  (let [state (catalog/initialize!)
        result (skill-runtime/execute!
                state :electromaster/brain-course "owner-1" {:action :start})]
    (is (= :accepted (:status result)))
    (is (empty? (:actions result)))
    (is (empty? (:vfx-signals result)))))

;; --- :mine-detect: a substantial real conversion (cost/spend + branch,
;; combat/status, effect/vfx, score/mark, cooldown/start, source nodes) ---

(deftest mine-detect-compiles-with-engine-v3-test
  (let [state (catalog/initialize!)
        ability (get-in state [:combat :abilities :mine-detect])]
    (is (nil? (get-in state [:combat :errors :mine-detect])))
    (is (= :v3 (:engine ability)))
    (is (catalog/available? :mine-detect))))

(deftest mine-detect-v3-program-spends-budget-and-applies-effects-when-affordable-test
  (let [state (catalog/initialize!)
        result (skill-runtime/execute!
                state :mine-detect "owner-1"
                {:action :start
                 :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
                        :world/id "overworld" :progression/mastery 0.9 :progression/level 5}
                 :tunables {:blindness-duration-ticks 40 :blindness-amplifier 1 :targeting-range 20.0
                            :cost-down-cp 5.0 :cost-down-overload 0.0 :cooldown-ticks 200 :exp-cast 0.02}
                 :context {:resources {:cp 10.0}}})]
    (is (= :accepted (:status result)))
    (is (some #(and (= :entity/status (:capability %)) (= "owner-1" (:target %))
                    (= :blindness (:status-id %)) (= 1 (:amplifier %)))
              (:actions result)))
    (is (some #(= :owner-patch (:type %)) (:actions result))
        "cost/spend + score/mark + cooldown/start all emit owner-patch actions")
    (is (= 1 (count (:vfx-signals result))))
    (let [signal (first (:vfx-signals result))]
      (is (= :block-scan-transient (:effect-id signal)))
      (is (true? (get-in signal [:params :advanced?])) "mastery 0.9 > 0.5 and level 5 >= 4")
      (is (= 20.0 (get-in signal [:params :range]))))))

;; --- three more all-passive :engine :v3 conversions (rad-intensify,
;; dim-folding-theorem, space-fluct): same trivial :flow/phases/:flow/finish
;; :passive shape as brain-course, their real gameplay is :reactions /
;; :mark-policies, mechanisms independent of :program execution ---

(deftest rad-intensify-v3-program-executes-to-accepted-test
  (let [state (catalog/initialize!)
        ability (get-in state [:combat :abilities :rad-intensify])
        result (skill-runtime/execute!
                state :rad-intensify "owner-1" {:action :start})]
    (is (nil? (get-in state [:combat :errors :rad-intensify])))
    (is (= :v3 (:engine ability)))
    (is (= :accepted (:status result)))))

(deftest dim-folding-theorem-v3-program-executes-to-accepted-test
  (let [state (catalog/initialize!)
        ability (get-in state [:combat :abilities :dim-folding-theorem])
        result (skill-runtime/execute!
                state :dim-folding-theorem "owner-1" {:action :start})]
    (is (nil? (get-in state [:combat :errors :dim-folding-theorem])))
    (is (= :v3 (:engine ability)))
    (is (= :accepted (:status result)))))

(deftest space-fluct-v3-program-executes-to-accepted-test
  (let [state (catalog/initialize!)
        ability (get-in state [:combat :abilities :space-fluct])
        result (skill-runtime/execute!
                state :space-fluct "owner-1" {:action :start})]
    (is (nil? (get-in state [:combat :errors :space-fluct])))
    (is (= :v3 (:engine ability)))
    (is (= :accepted (:status result)))))

;; --- :arc-gen: raycast query + entity/block branch + :combat/impact-strike
;; composite (:on-impact callback) + :domain/event ---

(def ^:private arc-gen-fixture
  {:action :start
   :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
          :caster/aim {:x 0.0 :y 0.0 :z 1.0} :world/id "overworld" :progression/mastery 0.5}
   :tunables {:damage 12.0 :max-distance 32.0 :ignite-probability 0.2
              :fishing-probability 0.1 :fishing-exp-threshold 0.5 :creeper-charge-chance 0.0
              :cost-cp 3.0 :cost-overload 0.0 :cooldown-ticks 40 :exp-entity 0.05 :exp-block 0.02}
   :context {:resources {:cp 10.0}}})

(deftest arc-gen-v3-program-strikes-an-entity-when-raycast-hits-one-test
  (with-fake-raycast-handler
    {:entity-id "target-1" :position {:vec3 [0.0 65.6 5.0]} :creeper? false}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute! state :arc-gen "owner-1" arc-gen-fixture)]
        (is (= :accepted (:status result)))
        (is (some #(and (= :entity/damage (:capability %)) (= "target-1" (:target %)) (= 12.0 (:amount %)))
                  (:actions result)))
        (is (some #(= :owner-patch (:type %)) (:actions result)))
        (is (= 1 (count (:vfx-signals result))))
        (is (= :achievement/trigger (:type (first (filter #(= :achievement/trigger (:type %)) (:events result))))))))))

(deftest arc-gen-v3-program-triggers-block-impact-when-raycast-misses-entities-test
  (with-fake-raycast-handler
    {:entity-id nil :position {:vec3 [0.0 65.6 5.0]} :block-position {:vec3 [0 65 5]} :water? false}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute! state :arc-gen "owner-1" arc-gen-fixture)]
        (is (= :accepted (:status result)))
        (is (not (some #(= :entity/damage (:capability %)) (:actions result))))
        (is (some #(= :world/block-impact (:type %)) (:events result)))
        (is (some #(= :owner-patch (:type %)) (:actions result)))))))

;; --- :combat/beam-strike: the real shipped R4 composite completing the
;; R2 audit's last downgraded component (:host/beam-trace) -- proven
;; directly here since no ability has been converted to use it yet
;; (railgun.edn, the real caller, also needs :guard/resource/:txn/atomic
;; item-consumption handling ported first; left for a follow-up) ---

(deftest combat-beam-strike-composite-damages-in-axis-entities-through-real-manifest-test
  (catalog/initialize!)
  (let [actions (atom [])
        dispatch-query!
        (fn [capability _request]
          (case capability
            :entity/select [{:id "zombie-1" :type "zombie" :position {:x 0.0 :y 64.0 :z 5.0} :eye-height 0.0}]
            :block/select []
            nil))
        ctx {:locals {} :seed 0 :dispatch structural/dispatch
             :world-id "overworld" :owner "owner-1" :ability-id :test-beam :activation-seed 1
             :dispatch-action! (fn [capability request] (swap! actions conj [capability request]))
             :dispatch-query! dispatch-query!}
        result (node-flow/execute!
                {:component :combat/beam-strike
                 :origin {:vec3 [0.0 64.0 0.0]} :direction {:vec3 [0.0 0.0 1.0]}
                 :length 10.0 :radius 1.0 :damage 15.0 :damage-type :skill
                 :bind {:beam :beam}}
                ctx)]
    (is (= "zombie-1" (get-in result [:locals :beam :entities 0 :id])))
    (is (some #(and (= :entity/damage (first %)) (= "zombie-1" (:target (second %)))
                    (= 15.0 (:amount (second %))))
              @actions))))

;; --- :railgun: a :session ability exercising :flow/phases, :txn/atomic
;; (item-guard + :inventory/consume reservation), :ability/budget +
;; explicit :cost/spend branching inside the txn body, and the new
;; :combat/beam-strike + :combat/break-budget composites together ---

(def ^:private railgun-fixture
  {:from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
          :caster/aim {:x 0.0 :y 0.0 :z 1.0} :world/id "overworld"}
   :tunables {:beam-damage 25.0 :beam-radius 1.0 :beam-query-radius 10.0
              :beam-step 0.9 :beam-block-energy 5.0 :beam-visual-distance 30.0
              :max-distance 30.0 :charge-ticks 20 :cost-down-cp 6.0 :cost-down-overload 0.0}})

(defn- with-fake-railgun-queries [{:keys [held-item beam-entities blocks]} f]
  (let [previous-item (get (:queries (capabilities/snapshot)) :item/held)
        previous-entity (get (:queries (capabilities/snapshot)) :entity/select)
        previous-block (get (:queries (capabilities/snapshot)) :block/select)]
    (try
      (capabilities/register-query! :item/held (fn [_request _frame] held-item))
      (capabilities/register-query!
       :entity/select
       (fn [request _frame]
         ;; The coin query at :start (projection [:id :age-ms :motion-progress])
         ;; and the beam-trace entity query inside :combat/beam-strike
         ;; (projection [:id :type :position :eye-height]) both hit
         ;; :entity/select -- discriminate on :projection like a real host
         ;; would discriminate on the request shape.
         (if (= [:id :type :position :eye-height] (:projection request))
           beam-entities
           []))
       )
      (capabilities/register-query! :block/select (fn [_request _frame] blocks))
      (f)
      (finally
        (when previous-item (capabilities/register-query! :item/held previous-item))
        (when previous-entity (capabilities/register-query! :entity/select previous-entity))
        (when previous-block (capabilities/register-query! :block/select previous-block))))))

(deftest railgun-v3-start-phase-spawns-charge-vfx-test
  (with-fake-railgun-queries {:held-item nil :beam-entities [] :blocks []}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :railgun "owner-1" (assoc railgun-fixture :action :start))]
        (is (= :accepted (:status result)))
        (is (= :started (:outcome result)))
        (is (= 1 (count (:vfx-signals result))))))))

(deftest railgun-v3-release-phase-fires-when-affordable-test
  (with-fake-railgun-queries
    {:held-item {:item-id "minecraft:iron_ingot"}
     :beam-entities [{:id "zombie-1" :type "zombie" :position {:x 0.0 :y 65.6 :z 10.0} :eye-height 0.0}]
     :blocks []}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :railgun "owner-1"
                    (assoc railgun-fixture :action :release
                           :context {:resources {:cp 10.0}}))]
        (is (= :accepted (:status result)))
        (is (= :committed (:outcome result)))
        (is (some #(and (= :entity/damage (:capability %)) (= "zombie-1" (:target %))) (:actions result)))
        (is (some #(and (= :inventory/consume (:capability %)) (= :main-hand (:source %))) (:actions result)))
        (is (some #(= :world/sound (:capability %)) (:actions result)))))))

(deftest railgun-v3-release-phase-rejects-when-insufficient-resource-test
  (with-fake-railgun-queries
    {:held-item {:item-id "minecraft:iron_ingot"} :beam-entities [] :blocks []}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :railgun "owner-1"
                    (assoc railgun-fixture :action :release
                           :context {:resources {:cp 0.0}}))]
        (is (= :accepted (:status result)))
        (is (= :insufficient-resource (:outcome result)))
        (is (not (some #(= :entity/damage (:capability %)) (:actions result))))))))

(deftest railgun-v3-release-phase-rejects-when-wrong-item-held-test
  (with-fake-railgun-queries
    {:held-item {:item-id "minecraft:stick"} :beam-entities [] :blocks []}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :railgun "owner-1"
                    (assoc railgun-fixture :action :release
                           :context {:resources {:cp 10.0}}))]
        (is (= :accepted (:status result)))
        (is (= :insufficient-resource (:outcome result)))
        (is (not (some #(= :inventory/consume (:capability %)) (:actions result))))))))

;; --- :location-teleport: :costs/:progression/:cooldown are entirely
;; program-computed (depend on :distance, only known after :destination
;; resolves), so the program bypasses :ability/budget|progression|cooldown
;; and builds those maps inline -- also the first real content to use the
;; new :ability/context source node for {:ref [:context :location-name]} ---

(defn- with-fake-teleport-queries [{:keys [owner-position destination]} f]
  (let [previous-owner (get (:queries (capabilities/snapshot)) :owner/snapshot)
        previous-location (get (:queries (capabilities/snapshot)) :saved-location)
        previous-entity (get (:queries (capabilities/snapshot)) :entity/select)]
    (try
      (capabilities/register-query! :owner/snapshot (fn [_request _frame] {:position owner-position}))
      (capabilities/register-query! :saved-location (fn [_request _frame] destination))
      (capabilities/register-query! :entity/select (fn [_request _frame] []))
      (f)
      (finally
        (when previous-owner (capabilities/register-query! :owner/snapshot previous-owner))
        (when previous-location (capabilities/register-query! :saved-location previous-location))
        (when previous-entity (capabilities/register-query! :entity/select previous-entity))))))

(deftest location-teleport-compiles-with-engine-v3-test
  (let [state (catalog/initialize!)
        ability (get-in state [:combat :abilities :location-teleport])]
    (is (nil? (get-in state [:combat :errors :location-teleport])))
    (is (= :v3 (:engine ability)))
    (is (catalog/available? :location-teleport))))

(deftest location-teleport-v3-teleports-when-destination-found-and-affordable-test
  (with-fake-teleport-queries
    {:owner-position {:x 0.0 :y 64.0 :z 0.0 :world-id "overworld"}
     ;; :value/eq's raw equality (both here and in v2's own opcode VM) makes
     ;; the ability's own :cross-dimension? local true exactly when the two
     ;; world-ids ARE equal -- inverted from what the name suggests, but
     ;; this is v2's real, existing formula (ac/combat/abilities/
     ;; location_teleport.edn ported byte-for-byte), not something to
     ;; silently "fix" while porting. Using a different world-id here
     ;; makes :cross-dimension? false, satisfying the branch2 guard
     ;; ((not cross-dimension?) or mastery > threshold) unconditionally
     ;; rather than depending on the mastery/threshold tunable values.
     :destination {:x 100.0 :y 70.0 :z 100.0 :world-id "the_nether"}}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :location-teleport "owner-1"
                    {:action :start
                     :from {:caster/id "owner-1" :world/id "overworld" :progression/mastery 0.5}
                     :tunables {:cross-dimension-exp-threshold 0.9 :teleport-radius 3.0
                                :cp-base [2.0 8.0] :overload 0.0 :cross-dimension-multiplier 2.0
                                :min-distance-multiplier 1.0 :distance-cap 200.0
                                :cooldown-ticks [40.0 100.0] :long-distance-threshold 50.0
                                :exp-short 0.01 :exp-long 0.03}
                     :context {:location-name "home" :resources {:cp 100.0 :overload 100.0}}})]
        (is (= :accepted (:status result)))
        (is (= :teleported (:outcome result)))
        (is (some #(= :owner-patch (:type %)) (:actions result))
            "cost/spend + score/mark + cooldown/start all emit owner-patch actions")
        (is (= 1 (count (:vfx-signals result))))))))

(deftest location-teleport-v3-rejects-when-location-not-found-test
  (with-fake-teleport-queries
    {:owner-position {:x 0.0 :y 64.0 :z 0.0 :world-id "overworld"} :destination nil}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :location-teleport "owner-1"
                    {:action :start
                     :from {:caster/id "owner-1" :world/id "overworld" :progression/mastery 0.5}
                     :tunables {:cross-dimension-exp-threshold 0.9 :teleport-radius 3.0
                                :cp-base [2.0 8.0] :overload 0.0 :cross-dimension-multiplier 2.0
                                :min-distance-multiplier 1.0 :distance-cap 200.0
                                :cooldown-ticks [40.0 100.0] :long-distance-threshold 50.0
                                :exp-short 0.01 :exp-long 0.03}
                     :context {:location-name "nowhere" :resources {}}})]
        (is (= :accepted (:status result)))
        (is (= :location-not-found (:outcome result)))
        (is (empty? (:actions result)))))))

;; --- :vec-accel: a :session ability using :session/read|write (not raw
;; :session/patch) and the new :vec3/launch domain expr op ---

(defn- with-fake-ground-raycast [hit f]
  (let [previous (get (:queries (capabilities/snapshot)) :raycast)]
    (try
      (capabilities/register-query! :raycast (fn [_request _frame] hit))
      (f)
      (finally
        (when previous (capabilities/register-query! :raycast previous))))))

(def ^:private vec-accel-tunables
  {:max-charge-ticks 40 :max-velocity 3.0 :speed-progress [0.1 1.0]
   :pitch-offset-radians 0.0 :ground-check-distance 5.0 :groundless-exp-threshold 0.9
   :release-cp 4.0 :release-overload 0.0 :cooldown-ticks 60 :exp-use 0.02})

(deftest vec-accel-v3-start-phase-initializes-session-and-spawns-vfx-test
  (let [state (catalog/initialize!)
        result (skill-runtime/execute!
                state :vec-accel "owner-1"
                {:action :start
                 :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.0 :z 0.0}
                        :caster/aim {:x 0.0 :y 0.0 :z 1.0} :world/id "overworld"}
                 :tunables vec-accel-tunables})]
    (is (= :accepted (:status result)))
    (is (= :started (:outcome result)))
    (is (some #(= :session-patch (:type %)) (:actions result)))
    (is (= 1 (count (:vfx-signals result))))))

(deftest vec-accel-v3-pulse-phase-charges-and-updates-session-test
  (with-fake-ground-raycast nil
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :vec-accel "owner-1"
                    {:action :pulse
                     :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.0 :z 0.0}
                            :caster/body {:x 0.0 :y 64.0 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                            :world/id "overworld" :progression/mastery 0.95}
                     :tunables vec-accel-tunables
                     :session-state {:charge-ticks 0 :can-perform? true}})]
        (is (= :accepted (:status result)))
        (is (= :charging (:outcome result)))
        (is (some #(= :session-patch (:type %)) (:actions result)))))))

(deftest vec-accel-v3-release-phase-launches-when-affordable-test
  (let [state (catalog/initialize!)
        result (skill-runtime/execute!
                state :vec-accel "owner-1"
                {:action :release
                 :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.0 :z 0.0} :world/id "overworld"}
                 :tunables vec-accel-tunables
                 :context {:resources {:cp 10.0}}
                 :session-state {:can-perform? true :init-vel {:vec3 [0.0 0.5 1.0]}}})]
    (is (= :accepted (:status result)))
    (is (= :launched (:outcome result)))
    (is (some #(= :motion/velocity (:capability %)) (:actions result)))
    (is (= 2 (count (:vfx-signals result)))
        "audio-one-shot spawn + trajectory-ribbon-session destroy")))

(deftest vec-accel-v3-release-phase-rejects-when-not-performable-test
  (let [state (catalog/initialize!)
        result (skill-runtime/execute!
                state :vec-accel "owner-1"
                {:action :release
                 :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.0 :z 0.0} :world/id "overworld"}
                 :tunables vec-accel-tunables
                 :context {:resources {:cp 10.0}}
                 :session-state {:can-perform? false :init-vel {:vec3 [0.0 0.5 1.0]}}})]
    (is (= :accepted (:status result)))
    (is (= :not-performable (:outcome result)))
    (is (not (some #(= :motion/velocity (:capability %)) (:actions result))))))

;; --- :ray-barrage: raycast + two-stage :target/entities (sphere then
;; cone) + a behavior-triggered fan branch vs. a plain single-target
;; branch, all using the ordinary static :ability/budget|progression|
;; cooldown source nodes (unlike location-teleport's dynamic case) ---

(defn- with-fake-ray-barrage-queries [{:keys [hit silbarn-candidates scatter-targets]} f]
  (let [previous-raycast (get (:queries (capabilities/snapshot)) :raycast)
        previous-entity (get (:queries (capabilities/snapshot)) :entity/select)]
    (try
      (capabilities/register-query! :raycast (fn [_request _frame] hit))
      (capabilities/register-query!
       :entity/select
       (fn [request _frame]
         (if (= :sphere (get-in request [:shape :type])) silbarn-candidates scatter-targets))
       )
      (f)
      (finally
        (when previous-raycast (capabilities/register-query! :raycast previous-raycast))
        (when previous-entity (capabilities/register-query! :entity/select previous-entity))))))

(def ^:private ray-barrage-fixture
  {:action :start
   :from {:caster/id "owner-1" :caster/body {:x 0.0 :y 64.0 :z 0.0} :caster/eye {:x 0.0 :y 65.6 :z 0.0}
          :caster/aim {:x 0.0 :y 0.0 :z 1.0} :world/id "overworld"}
   :tunables {:plain-damage 10.0 :scattered-damage 4.0 :targeting-range 32.0 :scatter-cone-angle 20.0
              :cost-down-cp 3.0 :cost-down-overload 0.0 :cooldown-ticks 60 :exp-hit 0.02}
   :context {:resources {:cp 10.0}}})

(deftest ray-barrage-v3-triggers-fan-branch-when-silbarn-found-and-not-behavior-hit-test
  (with-fake-ray-barrage-queries
    {:hit {:entity-id "silbarn-1" :position {:vec3 [0.0 65.6 10.0]}}
     :silbarn-candidates [{:id "silbarn-1" :type "academy:entity_silbarn" :position {:vec3 [0.0 65.6 10.0]}
                           :behavior-hit? false}]
     :scatter-targets [{:id "victim-1" :type "zombie" :position {:vec3 [1.0 65.6 10.0]} :eye-height 0.0}]}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute! state :ray-barrage "owner-1" ray-barrage-fixture)]
        (is (= :accepted (:status result)))
        (is (= :performed (:outcome result)))
        (is (some #(and (= :entity/trigger-behavior (:capability %))) (:actions result)))
        (is (some #(and (= :entity/damage (:capability %)) (= "victim-1" (:target %)) (= 4.0 (:amount %)))
                  (:actions result)))
        (is (some #(= :entity/mark (:capability %)) (:actions result)))
        (is (= 4 (count (:vfx-signals result)))
            "beam + audio + fan + audio")))))

(deftest ray-barrage-v3-falls-back-to-plain-damage-when-no-silbarn-test
  (with-fake-ray-barrage-queries
    {:hit {:entity-id "zombie-1" :position {:vec3 [0.0 65.6 10.0]}}
     :silbarn-candidates []
     :scatter-targets []}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute! state :ray-barrage "owner-1" ray-barrage-fixture)]
        (is (= :accepted (:status result)))
        (is (= :performed (:outcome result)))
        (is (not (some #(= :entity/trigger-behavior (:capability %)) (:actions result))))
        (is (some #(and (= :entity/damage (:capability %)) (= "zombie-1" (:target %)) (= 10.0 (:amount %)))
                  (:actions result)))
        (is (= 2 (count (:vfx-signals result))) "beam + audio, no fan")))))

;; --- :directed-shock: charge/punch session animation state + a release-
;; time vec3 knockback-math chain, gated on mastery, using :session/
;; read|write for both charge-ticks and the punched?/punch-ticks pair ---

(def ^:private directed-shock-tunables
  {:charge-min-ticks 2 :charge-max-accepted-ticks 40 :charge-max-tolerant-ticks 60
   :punch-animation-ticks 6 :targeting-distance 5.0 :target-eye-height 1.6
   :hit-impulse 0.4 :knockback-y-adjust 0.1 :knockback-scale 0.6 :knockback-exp-threshold 0.9
   :damage 8.0 :release-cp 3.0 :release-overload 0.0 :cooldown-ticks 40 :exp-hit 0.02 :exp-miss 0.01})

(deftest directed-shock-v3-start-phase-initializes-session-and-spawns-vfx-test
  (let [state (catalog/initialize!)
        result (skill-runtime/execute!
                state :directed-shock "owner-1"
                {:action :start
                 :from {:caster/id "owner-1" :world/id "overworld"}
                 :tunables directed-shock-tunables})]
    (is (= :accepted (:status result)))
    (is (= :started (:outcome result)))
    (is (some #(= :session-patch (:type %)) (:actions result)))
    (is (= 1 (count (:vfx-signals result))))))

(deftest directed-shock-v3-pulse-phase-charges-test
  (let [state (catalog/initialize!)
        result (skill-runtime/execute!
                state :directed-shock "owner-1"
                {:action :pulse
                 :from {:caster/id "owner-1" :world/id "overworld"}
                 :tunables directed-shock-tunables
                 :session-state {:charge-ticks 0 :punched? false :punch-ticks 0}})]
    (is (= :accepted (:status result)))
    (is (= :charging (:outcome result)))
    (is (some #(= :session-patch (:type %)) (:actions result)))))

(deftest directed-shock-v3-release-phase-hits-with-knockback-when-mastery-above-threshold-test
  (with-fake-raycast-handler
    {:entity-id "zombie-1" :position {:vec3 [0.0 65.0 5.0]} :eye-height 1.6}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :directed-shock "owner-1"
                    {:action :release
                     :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
                            :caster/aim {:x 0.0 :y 0.0 :z 1.0} :caster/body {:x 0.0 :y 64.0 :z 0.0}
                            :world/id "overworld" :progression/mastery 0.95}
                     :tunables directed-shock-tunables
                     :context {:resources {:cp 10.0}}
                     :session-state {:charge-ticks 10}})]
        (is (= :accepted (:status result)))
        (is (= :punched (:outcome result)))
        (is (some #(and (= :entity/damage (:capability %)) (= "zombie-1" (:target %))) (:actions result)))
        (is (some #(= :entity/teleport (:capability %)) (:actions result))
            "high mastery routes through the teleport + full-velocity knockback branch")
        (is (some #(= :session-patch (:type %)) (:actions result)))))))

(deftest directed-shock-v3-release-phase-misses-when-raycast-finds-no-entity-test
  (with-fake-raycast-handler
    {:entity-id nil}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :directed-shock "owner-1"
                    {:action :release
                     :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
                            :caster/aim {:x 0.0 :y 0.0 :z 1.0} :caster/body {:x 0.0 :y 64.0 :z 0.0}
                            :world/id "overworld" :progression/mastery 0.1}
                     :tunables directed-shock-tunables
                     :context {:resources {:cp 10.0}}
                     :session-state {:charge-ticks 10}})]
        (is (= :accepted (:status result)))
        (is (= :miss (:outcome result)))
        (is (not (some #(= :entity/damage (:capability %)) (:actions result))))))))

;; --- :body-intensify: 3 v2 :fragments (:stop-charge, :fail, :apply-buffs)
;; inlined at their call sites (no v3 local-composite mechanism exists),
;; plus the new :value/status-id/:value/status-max-amplifier domain expr
;; ops and a nested {:ref [:context :resources :overload]} multi-segment
;; path (read via :ability/context :name :resources then an ordinary
;; nested :local ref, not a change to :ability/context itself) ---

(def ^:private body-intensify-tunables
  {:charge-min-ticks 5 :charge-max-ticks 100 :charge-max-tolerant-ticks 140
   :effect-probability-offset-ticks 20.0 :effect-probability-divisor 20.0
   :effect-duration-multiplier 1.0 :effect-hunger-multiplier 0.5 :effect-hunger-amplifier 0
   :effect-available-effects ["speed:2"]
   :cost-down-overload 5.0 :cost-tick-cp 0.1 :cooldown-ticks 40.0 :progression-exp-use 0.01})

(deftest body-intensify-v3-start-phase-initializes-session-and-spawns-vfx-test
  (let [state (catalog/initialize!)
        result (skill-runtime/execute!
                state :body-intensify "owner-1"
                {:action :start
                 :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.0 :z 0.0} :world/id "overworld"}
                 :tunables body-intensify-tunables
                 :context {:resources {:overload 20.0}}})]
    (is (= :accepted (:status result)))
    (is (= :started (:outcome result)))
    (is (some #(= :session-patch (:type %)) (:actions result)))
    (is (= 2 (count (:vfx-signals result))) "arc-channel-session + audio-loop-session spawn")))

(deftest body-intensify-v3-pulse-phase-charges-when-affordable-test
  (let [state (catalog/initialize!)
        result (skill-runtime/execute!
                state :body-intensify "owner-1"
                {:action :pulse
                 :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.0 :z 0.0} :world/id "overworld"}
                 :tunables body-intensify-tunables
                 :context {:resources {:cp 10.0 :overload 20.0}}
                 :session-state {:hold-ticks 0 :overload-floor 15.0}})]
    (is (= :accepted (:status result)))
    (is (= :continue (:outcome result)))
    (is (some #(= :session-patch (:type %)) (:actions result)))))

(deftest body-intensify-v3-pulse-phase-rejects-when-insufficient-cp-test
  (let [state (catalog/initialize!)
        result (skill-runtime/execute!
                state :body-intensify "owner-1"
                {:action :pulse
                 :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.0 :z 0.0} :world/id "overworld"}
                 :tunables body-intensify-tunables
                 :context {:resources {:cp 0.0 :overload 20.0}}
                 :session-state {:hold-ticks 0 :overload-floor 15.0}})]
    (is (= :accepted (:status result)))
    (is (= :insufficient-resource (:outcome result)))
    (is (true? (:finish-session? result))
        "a :pulse-phase early termination must set :finish-session? or the session lingers forever")))

(deftest body-intensify-v3-release-phase-applies-hunger-and-scores-when-charged-enough-test
  (let [state (catalog/initialize!)
        result (skill-runtime/execute!
                state :body-intensify "owner-1"
                {:action :release
                 :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.0 :z 0.0} :world/id "overworld"}
                 :tunables body-intensify-tunables
                 :session-state {:hold-ticks 30}})]
    (is (= :accepted (:status result)))
    (is (= :performed (:outcome result)))
    (is (some #(and (= :entity/status (:capability %)) (= :hunger (:status-id %))) (:actions result)))
    (is (some #(= :owner-patch (:type %)) (:actions result)) "score/mark + cooldown/start")
    (is (= 3 (count (:vfx-signals result))) "arc-channel destroy + audio-loop destroy + endpoint-burst spawn")))

(deftest body-intensify-v3-release-phase-rejects-when-not-charged-enough-test
  (let [state (catalog/initialize!)
        result (skill-runtime/execute!
                state :body-intensify "owner-1"
                {:action :release
                 :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.0 :z 0.0} :world/id "overworld"}
                 :tunables body-intensify-tunables
                 :session-state {:hold-ticks 1}})]
    (is (= :accepted (:status result)))
    (is (= :not-ready (:outcome result)))
    (is (not (some #(= :entity/status (:capability %)) (:actions result))))))

;; --- :electron-bomb: :entity/spawn (:velocity/:life-ticks fields, the
;; 9th real host-primitive gap this session) + :projectile/schedule-beam's
;; selector fields (fixed earlier), no :costs at all (a free cast) ---

(deftest electron-bomb-v3-program-spawns-ball-and-schedules-beam-test
  (let [state (catalog/initialize!)
        result (skill-runtime/execute!
                state :electron-bomb "owner-1"
                {:action :start
                 :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
                        :caster/aim {:x 0.0 :y 0.0 :z 1.0} :world/id "overworld"
                        :progression/mastery 0.5 :rng/seed 7}
                 :tunables {:damage 15.0 :cooldown-ticks 100 :exp-hit 0.02
                            :settle-ticks 20 :settle-ticks-improved 10 :improved-exp-threshold 0.9}})]
    (is (= :accepted (:status result)))
    (is (= :performed (:outcome result)))
    (is (some #(and (= :entity/spawn (:capability %)) (= "academy:entity_md_ball" (:entity-type %))
                    (= {:vec3 [0.0 0.0 0.0]} (:velocity %)) (= 20 (:life-ticks %)))
              (:actions result))
        "mastery 0.5 < improved-exp-threshold 0.9 -> plain settle-ticks (20)")
    (let [beam-action (first (filter #(= :projectile/schedule-beam (:capability %)) (:actions result)))]
      (is (some? beam-action))
      (is (true? (:exclude-owner? beam-action)))
      (is (== 18.0 (:delay-ticks beam-action)))
      (is (some? (:origin-selector beam-action)))
      (is (some? (:destination-selector beam-action)))
      (is (some? (:settlement-vfx beam-action))))
    (is (some #(= :owner-patch (:type %)) (:actions result)) "score/mark + cooldown/start")))

;; --- :mine-ray: another session ability with 1 v2 :fragment (:cleanup,
;; used 3x) inlined, exercising :target/blocks + :block/break's newly
;; fixed fields, and a nested {:ref [:context :ability-runtime ...]}
;; multi-segment path read the same way as :resources earlier ---

(def ^:private mine-ray-tunables
  {:targeting-range 20.0 :break-speed 5.0 :cost-down-overload 3.0 :cost-tick-cp 0.05
   :cooldown-ticks 40 :exp-block 0.02})

(def ^:private mine-ray-runtime
  {:beam-style {:texture "x"} :progress-color [255 255 255 255] :loop-sound-id "loop"
   :startup-sound-id "start" :particle {:material :additive} :fortune-level 0 :tool-tier-capped? false})

(defn- with-fake-mine-ray-blocks [blocks f]
  (let [previous (get (:queries (capabilities/snapshot)) :block/select)]
    (try
      (capabilities/register-query! :block/select (fn [_request _frame] blocks))
      (f)
      (finally (when previous (capabilities/register-query! :block/select previous))))))


(deftest mine-ray-v3-start-phase-initializes-session-and-spawns-vfx-test
  (let [state (catalog/initialize!)
        result (skill-runtime/execute!
                state :mine-ray-basic "owner-1"
                {:action :start
                 :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.0 :z 0.0}
                        :caster/aim {:x 0.0 :y 0.0 :z 1.0} :caster/body {:x 0.0 :y 64.0 :z 0.0}
                        :world/id "overworld"}
                 :tunables mine-ray-tunables
                 :context {:resources {:overload 20.0} :ability-runtime mine-ray-runtime}})]
    (is (= :accepted (:status result)))
    (is (= :started (:outcome result)))
    (is (= 4 (count (:vfx-signals result))) "beam + block-progress + audio-loop + audio-one-shot")))

(deftest mine-ray-v3-pulse-phase-starts-targeting-a-new-breakable-block-test
  (with-fake-mine-ray-blocks
    [{:position {:x 1.0 :y 65.0 :z 5.0} :hardness 30.0 :block-id "minecraft:stone"
      :breakable? true :requires-high-tier-tool? false}]
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :mine-ray-basic "owner-1"
                    {:action :pulse
                     :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.0 :z 0.0}
                            :caster/aim {:x 0.0 :y 0.0 :z 1.0} :caster/body {:x 0.0 :y 64.0 :z 0.0}
                            :world/id "overworld"}
                     :tunables mine-ray-tunables
                     :context {:resources {:cp 10.0} :ability-runtime mine-ray-runtime}
                     :session-state {:overload-floor 0.0 :target nil :hardness-left nil :starting-hardness nil}})]
        (is (= :accepted (:status result)))
        (is (= :continue (:outcome result)))
        (is (some #(= :session-patch (:type %)) (:actions result)))
        (is (not (some #(= :block/break (:capability %)) (:actions result))))))))

(deftest mine-ray-v3-pulse-phase-breaks-the-same-target-when-hardness-runs-out-test
  (with-fake-mine-ray-blocks
    [{:position {:x 1.0 :y 65.0 :z 5.0} :hardness 30.0 :block-id "minecraft:stone"
      :breakable? true :requires-high-tier-tool? false}]
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :mine-ray-basic "owner-1"
                    {:action :pulse
                     :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.0 :z 0.0}
                            :caster/aim {:x 0.0 :y 0.0 :z 1.0} :caster/body {:x 0.0 :y 64.0 :z 0.0}
                            :world/id "overworld"}
                     :tunables mine-ray-tunables
                     :context {:resources {:cp 10.0} :ability-runtime mine-ray-runtime}
                     :session-state {:overload-floor 0.0 :target {:x 1.0 :y 65.0 :z 5.0}
                                     :hardness-left 3.0 :starting-hardness 30.0}})]
        (is (= :accepted (:status result)))
        (is (= :continue (:outcome result)))
        (is (some #(and (= :block/break (:capability %)) (= "minecraft:stone" (:expected-block-id %)))
                  (:actions result)))
        (is (some #(= :owner-patch (:type %)) (:actions result)) "score/mark")))))

(deftest mine-ray-v3-release-phase-cleans-up-and-starts-cooldown-test
  (let [state (catalog/initialize!)
        result (skill-runtime/execute!
                state :mine-ray-basic "owner-1"
                {:action :release
                 :from {:caster/id "owner-1" :world/id "overworld"}
                 :tunables mine-ray-tunables})]
    (is (= :accepted (:status result)))
    (is (= :released (:outcome result)))
    (is (= 3 (count (:vfx-signals result))) "beam + block-progress + audio-loop destroy")
    (is (some #(= :owner-patch (:type %)) (:actions result)) "cooldown/start")))

;; --- :current-charging: 4 v2 :fragments (:stop-channel, :insufficient,
;; :update-audio, :update-channel) all inlined by hand at every call site
;; (this one has the most call sites of any ability converted so far) ---

(def ^:private current-charging-tunables
  {:visual-max-ticks 100 :targeting-range 20.0 :charge-amount 10.0
   :cost-down-overload 3.0 :cost-tick-cp 0.05 :exp-effective 0.02 :exp-ineffective 0.01})

(defn- with-fake-item-held [held-item f]
  (let [previous (get (:queries (capabilities/snapshot)) :item/held)]
    (try
      (capabilities/register-query! :item/held (fn [_request _frame] held-item))
      (f)
      (finally (when previous (capabilities/register-query! :item/held previous))))))

(defn- with-fake-energy-target [energy-target f]
  (let [previous (get (:queries (capabilities/snapshot)) :energy/target)]
    (try
      (capabilities/register-query! :energy/target (fn [_request _frame] energy-target))
      (f)
      (finally (when previous (capabilities/register-query! :energy/target previous))))))

(deftest current-charging-v3-start-phase-item-mode-test
  (with-fake-item-held {:present? true}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :current-charging "owner-1"
                    {:action :start
                     :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.0 :z 0.0}
                            :caster/aim {:x 0.0 :y 0.0 :z 1.0} :world/id "overworld"}
                     :tunables current-charging-tunables
                     :context {:resources {:overload 20.0}}})]
        (is (= :accepted (:status result)))
        (is (= :started (:outcome result)))
        (is (= 2 (count (:vfx-signals result))))))))

(deftest current-charging-v3-pulse-phase-item-mode-charges-supported-item-test
  (with-fake-item-held {:present? true :supported? true}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :current-charging "owner-1"
                    {:action :pulse
                     :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.0 :z 0.0}
                            :caster/aim {:x 0.0 :y 0.0 :z 1.0} :world/id "overworld"}
                     :tunables current-charging-tunables
                     :context {:resources {:cp 10.0}}
                     :session-state {:overload-floor 0.0 :charge-ticks 0 :is-item true :style {:beam {}}}})]
        (is (= :accepted (:status result)))
        (is (= :continue (:outcome result)))
        (is (some #(and (= :energy/charge (:capability %)) (= :item (:mode %))) (:actions result)))
        (is (some #(= :owner-patch (:type %)) (:actions result)) "score/mark")))))

(deftest current-charging-v3-pulse-phase-item-mode-stops-when-item-dropped-test
  (with-fake-item-held {:present? false}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :current-charging "owner-1"
                    {:action :pulse
                     :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.0 :z 0.0}
                            :caster/aim {:x 0.0 :y 0.0 :z 1.0} :world/id "overworld"}
                     :tunables current-charging-tunables
                     :context {:resources {:cp 10.0}}
                     :session-state {:overload-floor 0.0 :charge-ticks 0 :is-item true :style {:beam {}}}})]
        (is (= :accepted (:status result)))
        (is (= 2 (count (:vfx-signals result))) "arc-channel + audio-loop destroy, no :flow/finish reached")))))

(deftest current-charging-v3-pulse-phase-block-mode-charges-when-chargeable-test
  (with-fake-raycast-handler
    {:entity-id nil :position {:vec3 [1.0 65.0 5.0]}}
    (fn []
      (with-fake-energy-target
        {:chargeable? true}
        (fn []
          (let [state (catalog/initialize!)
                result (skill-runtime/execute!
                        state :current-charging "owner-1"
                        {:action :pulse
                         :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.0 :z 0.0}
                                :caster/aim {:x 0.0 :y 0.0 :z 1.0} :world/id "overworld"}
                         :tunables current-charging-tunables
                         :context {:resources {:cp 10.0}}
                         :session-state {:overload-floor 0.0 :charge-ticks 0 :is-item false :style {:beam {}}}})]
            (is (= :accepted (:status result)))
            (is (= :continue (:outcome result)))
            (is (some #(and (= :energy/charge (:capability %)) (= :block (:mode %))) (:actions result)))))))))

(deftest current-charging-v3-release-phase-cleans-up-test
  (let [state (catalog/initialize!)
        result (skill-runtime/execute!
                state :current-charging "owner-1"
                {:action :release :from {:caster/id "owner-1" :world/id "overworld"}
                 :tunables current-charging-tunables})]
    (is (= :accepted (:status result)))
    (is (= :released (:outcome result)))
    (is (= 2 (count (:vfx-signals result))))))

;; --- :plasma-cannon: the largest ability converted this session -- 9 v2
;; :fragments inlined (some, like :charge-vfx-update, called 3 times), a
;; multi-tick session-state flight simulation (:advance re-invoked every
;; :pulse while :mode=1), :target/resolve-destination, :combat/area-damage
;; (extended with :damage-type), :world/explosion (extended with :owner/
;; :fire?/:terrain?) -- built with the Python EDN constructor, not by hand ---

(def ^:private plasma-cannon-tunables
  {:charge-time 20 :cost-tick-cp 0.05 :overload-keep 5.0 :targeting-distance 30.0
   :block-hit-extra-distance 0.5 :max-flight-ticks 100 :damage 20.0 :damage-radius 3.0
   :explosion-radius 2.0 :cooldown-ticks 60 :exp-use 0.02 :eye-height 1.6
   :spawn-y-offset 1.0 :destination-epsilon 1.5 :sync-interval-ticks 5 :ground-search-distance 10.0})

(defn- with-fake-plasma-raycast [{:keys [basic resolve-destination]} f]
  (let [previous (get (:queries (capabilities/snapshot)) :raycast)]
    (try
      (capabilities/register-query!
       :raycast (fn [request _frame]
                  (if (= :resolve-destination (:query-kind request)) resolve-destination basic)))
      (f)
      (finally (when previous (capabilities/register-query! :raycast previous))))))

(deftest plasma-cannon-v3-start-phase-charges-and-spawns-vfx-test
  (with-fake-plasma-raycast {:basic {:hit? true :position {:vec3 [0.0 60.0 0.0]}}}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :plasma-cannon "owner-1"
                    {:action :start
                     :from {:caster/id "owner-1" :caster/body {:x 0.0 :y 64.0 :z 0.0} :world/id "overworld"
                            :rng/seed 3}
                     :tunables plasma-cannon-tunables
                     :context {:resources {:overload 10.0}}})]
        (is (= :accepted (:status result)))
        (is (= :started (:outcome result)))
        (is (some #(= :resource/add (:capability %)) (:actions result)))
        (is (= 3 (count (:vfx-signals result))) "energy-orb + vortex-column + audio-loop spawn")))))

(deftest plasma-cannon-v3-pulse-phase-charging-continues-when-affordable-test
  (let [state (catalog/initialize!)
        result (skill-runtime/execute!
                state :plasma-cannon "owner-1"
                {:action :pulse
                 :from {:caster/id "owner-1" :world/id "overworld" :charge/ticks 5}
                 :tunables plasma-cannon-tunables
                 :context {:resources {:cp 10.0}}
                 :session-state {:mode 0}})]
    (is (= :accepted (:status result)))
    (is (= :charging (:outcome result)))))

(deftest plasma-cannon-v3-release-phase-fires-when-fully-charged-test
  (with-fake-plasma-raycast
    {:basic {:entity-id "zombie-1" :position {:vec3 [0.0 65.0 10.0]}}
     :resolve-destination {:position {:vec3 [0.0 65.0 10.0]}}}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :plasma-cannon "owner-1"
                    {:action :release
                     :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
                            :caster/aim {:x 0.0 :y 0.0 :z 1.0} :world/id "overworld" :charge/ticks 25}
                     :tunables plasma-cannon-tunables
                     :session-state {:mode 0}})]
        (is (= :accepted (:status result)))
        (is (= :released (:outcome result)))
        (is (some #(= :owner-patch (:type %)) (:actions result)) "score/mark + cooldown/start")
        (is (some #(= :session-patch (:type %)) (:actions result)) "session/write mode/destination/etc")))))

(deftest plasma-cannon-v3-pulse-phase-flight-advances-without-impact-when-far-from-target-test
  (with-fake-plasma-raycast {:basic {:hit? false}}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :plasma-cannon "owner-1"
                    {:action :pulse
                     :from {:caster/id "owner-1" :world/id "overworld"}
                     :tunables plasma-cannon-tunables
                     :session-state {:mode 1 :position {:vec3 [0.0 65.0 0.0]}
                                     :destination {:position {:vec3 [0.0 65.0 50.0]}}
                                     :flight-ticks 0 :sync-ticks 5}})]
        (is (= :accepted (:status result)))
        (is (= :flight (:outcome result)))
        (is (some #(= :session-patch (:type %)) (:actions result)))))))

(deftest plasma-cannon-v3-pulse-phase-flight-impacts-when-close-to-destination-test
  (with-fake-plasma-raycast {:basic {:hit? false}}
    (fn []
      (let [previous-entities (get (:queries (capabilities/snapshot)) :entity/select)]
        (try
          (capabilities/register-query!
           :entity/select (fn [_request _frame] [{:id "zombie-1"}]))
          (let [state (catalog/initialize!)
                result (skill-runtime/execute!
                        state :plasma-cannon "owner-1"
                        {:action :pulse
                         :from {:caster/id "owner-1" :world/id "overworld"}
                         :tunables plasma-cannon-tunables
                         :session-state {:mode 1 :position {:vec3 [0.0 65.0 0.0]}
                                         :destination {:position {:vec3 [0.0 65.0 0.5]}}
                                         :flight-ticks 0 :sync-ticks 5}})]
            (is (= :accepted (:status result)))
            (is (= :performed (:outcome result)))
            (is (some #(and (= :entity/damage (:capability %)) (= "zombie-1" (:target %))) (:actions result)))
            (is (some #(= :world/explosion (:capability %)) (:actions result))))
          (finally
            (when previous-entities (capabilities/register-query! :entity/select previous-entities))))))))

;; --- :jet-engine: a two-phase (:marking then :triggering) session state
;; machine, 3 v2 :fragments inlined, a nested {:ref [:context :resources
;; :cp]} multi-segment path, and :owner/snapshot used inside :pulse itself
;; (not just :start/:release) ---

(def ^:private jet-engine-tunables
  {:target-range 20.0 :damage 12.0 :hold-required-cp 1.0 :release-cp 5.0
   :release-overload 0.0 :cooldown-ticks 60 :progression-exp-use 0.02})

(defn- with-fake-owner-snapshot [snapshot f]
  (let [previous (get (:queries (capabilities/snapshot)) :owner/snapshot)]
    (try
      (capabilities/register-query! :owner/snapshot (fn [_request _frame] snapshot))
      (f)
      (finally (when previous (capabilities/register-query! :owner/snapshot previous))))))

(deftest jet-engine-v3-start-phase-marks-a-target-test
  (with-fake-raycast-handler
    {:position {:vec3 [0.0 65.0 10.0]}}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :jet-engine "owner-1"
                    {:action :start
                     :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
                            :caster/aim {:x 0.0 :y 0.0 :z 1.0} :world/id "overworld"}
                     :tunables jet-engine-tunables})]
        (is (= :accepted (:status result)))
        (is (= :started (:outcome result)))
        (is (some #(= :session-patch (:type %)) (:actions result)))
        (is (= 1 (count (:vfx-signals result))))))))

(deftest jet-engine-v3-pulse-phase-marking-holds-when-affordable-test
  (with-fake-raycast-handler
    {:position {:vec3 [0.0 65.0 10.0]}}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :jet-engine "owner-1"
                    {:action :pulse
                     :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
                            :caster/aim {:x 0.0 :y 0.0 :z 1.0} :world/id "overworld"}
                     :tunables jet-engine-tunables
                     :context {:resources {:cp 5.0}}
                     :session-state {:phase :marking :hold-ticks 0}})]
        (is (= :accepted (:status result)))
        (is (= :continue (:outcome result)))))))

(deftest jet-engine-v3-pulse-phase-marking-stops-when-insufficient-cp-test
  (with-fake-raycast-handler
    {:position {:vec3 [0.0 65.0 10.0]}}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :jet-engine "owner-1"
                    {:action :pulse
                     :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
                            :caster/aim {:x 0.0 :y 0.0 :z 1.0} :world/id "overworld"}
                     :tunables jet-engine-tunables
                     :context {:resources {:cp 0.0}}
                     :session-state {:phase :marking :hold-ticks 0}})]
        (is (= :accepted (:status result)))
        (is (= :insufficient-resource (:outcome result)))
        (is (true? (:finish-session? result)))))))

(deftest jet-engine-v3-release-phase-marking-fires-when-affordable-test
  (with-fake-owner-snapshot {:position {:vec3 [0.0 64.0 0.0]}}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :jet-engine "owner-1"
                    {:action :release
                     :from {:caster/id "owner-1" :world/id "overworld"}
                     :tunables jet-engine-tunables
                     :context {:resources {:cp 10.0}}
                     :session-state {:phase :marking :target-position {:vec3 [0.0 65.0 10.0]}}})]
        (is (= :accepted (:status result)))
        (is (= :triggering (:outcome result)))
        (is (some #(= :session-patch (:type %)) (:actions result)))
        (is (some #(= :owner-patch (:type %)) (:actions result)) "score/mark + cooldown/start")
        (is (= 6 (count (:vfx-signals result)))
            "stop-mark destroy + trail/particles/shield/impact/flash spawn")))))

(deftest jet-engine-v3-pulse-phase-triggering-damages-entity-hit-test
  (with-fake-owner-snapshot {:position {:vec3 [0.0 64.0 0.0]}}
    (fn []
      (with-fake-raycast-handler
        {:hit-type :entity :entity-id "zombie-1"}
        (fn []
          (let [state (catalog/initialize!)
                result (skill-runtime/execute!
                        state :jet-engine "owner-1"
                        {:action :pulse
                         :from {:caster/id "owner-1" :world/id "overworld"}
                         :tunables jet-engine-tunables
                         :session-state {:phase :triggering :start-position {:vec3 [0.0 64.0 0.0]}
                                         :last-position {:vec3 [0.0 64.0 0.0]} :velocity {:vec3 [0.0 0.0 1.0]}
                                         :trigger-ticks 0}})]
            (is (= :accepted (:status result)))
            (is (= :continue (:outcome result)))
            (is (some #(and (= :entity/damage (:capability %)) (= "zombie-1" (:target %))) (:actions result)))
            (is (some #(= :motion/velocity (:capability %)) (:actions result)))))))))

(deftest jet-engine-v3-pulse-phase-triggering-completes-after-15-ticks-test
  (let [state (catalog/initialize!)
        result (skill-runtime/execute!
                state :jet-engine "owner-1"
                {:action :pulse
                 :from {:caster/id "owner-1" :world/id "overworld"}
                 :tunables jet-engine-tunables
                 :session-state {:phase :triggering :trigger-ticks 15}})]
    (is (= :accepted (:status result)))
    (is (= :completed (:outcome result)))
    (is (true? (:finish-session? result)))
    (is (= 5 (count (:vfx-signals result))))))

(deftest jet-engine-v3-abort-phase-cleans-up-based-on-phase-test
  (let [state (catalog/initialize!)
        result (skill-runtime/execute!
                state :jet-engine "owner-1"
                {:action :abort :from {:caster/id "owner-1" :world/id "overworld"}
                 :tunables jet-engine-tunables
                 :session-state {:phase :marking}})]
    (is (= :accepted (:status result)))
    (is (= :aborted (:outcome result)))
    (is (= 1 (count (:vfx-signals result))) "only :stop-mark, phase was :marking")))

;; --- :shift-teleport: 2 v2 :fragments inlined (:refresh-trace called 3x,
;; :destroy-target-markers called 4x), :target/block-placement +
;; :inventory/place-or-drop, and another dynamic (program-computed)
;; :progression -- :score/mark's :progression built inline like
;; location-teleport's, not via :ability/progression ---

(def ^:private shift-teleport-tunables
  {:maximum-range 30.0 :damage 6.0 :release-cp 4.0 :release-overload 0.0
   :cooldown-ticks 60 :exp-base 0.01})

(defn- with-fake-shift-teleport-queries [{:keys [held-item block-hit block-placement entities]} f]
  (let [previous-item (get (:queries (capabilities/snapshot)) :item/held)
        previous-raycast (get (:queries (capabilities/snapshot)) :raycast)
        previous-entity (get (:queries (capabilities/snapshot)) :entity/select)]
    (try
      (capabilities/register-query! :item/held (fn [_request _frame] held-item))
      (capabilities/register-query!
       :raycast (fn [request _frame]
                  (case (:query-kind request)
                    :block-placement block-placement
                    block-hit)))
      (capabilities/register-query! :entity/select (fn [_request _frame] entities))
      (f)
      (finally
        (when previous-item (capabilities/register-query! :item/held previous-item))
        (when previous-raycast (capabilities/register-query! :raycast previous-raycast))
        (when previous-entity (capabilities/register-query! :entity/select previous-entity))))))

(deftest shift-teleport-v3-start-phase-marks-destination-when-item-placeable-test
  (with-fake-shift-teleport-queries
    {:held-item {:present? true :placeable? true}
     :block-hit {:position {:vec3 [0.0 65.0 10.0]}}
     :block-placement {:valid? true :position {:vec3 [0.0 65.0 10.0]}}
     :entities []}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :shift-teleport "owner-1"
                    {:action :start
                     :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
                            :caster/body {:x 0.0 :y 64.0 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                            :world/id "overworld"}
                     :tunables shift-teleport-tunables})]
        (is (= :accepted (:status result)))
        (is (= :started (:outcome result)))
        (is (some #(= :session-patch (:type %)) (:actions result)))
        (is (= 1 (count (:vfx-signals result))))))))

(deftest shift-teleport-v3-start-phase-rejects-when-no-placeable-item-test
  (with-fake-shift-teleport-queries
    {:held-item {:present? false} :block-hit {:position {:vec3 [0.0 65.0 10.0]}}
     :block-placement {:valid? false :position {:vec3 [0.0 65.0 10.0]}} :entities []}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :shift-teleport "owner-1"
                    {:action :start
                     :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
                            :caster/body {:x 0.0 :y 64.0 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                            :world/id "overworld"}
                     :tunables shift-teleport-tunables})]
        (is (= :accepted (:status result)))
        (is (= :no-item (:outcome result)))
        (is (true? (:finish-session? result)))))))

(deftest shift-teleport-v3-release-phase-places-and-damages-line-targets-test
  (with-fake-shift-teleport-queries
    {:held-item {:present? true :placeable? true}
     :block-hit {:position {:vec3 [0.0 65.0 10.0]}}
     :block-placement {:valid? true :position {:vec3 [0.0 65.0 10.0]} :line-position {:vec3 [0.0 65.0 9.0]}}
     :entities [{:id "zombie-1" :position {:vec3 [0.0 65.0 5.0]} :width 0.6 :height 1.8}]}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :shift-teleport "owner-1"
                    {:action :release
                     :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
                            :caster/body {:x 0.0 :y 64.0 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                            :caster/creative? false :world/id "overworld"}
                     :tunables shift-teleport-tunables
                     :context {:resources {:cp 10.0}}})]
        (is (= :accepted (:status result)))
        ;; :performed, not :released -- the ability's own final top-level
        ;; {:flow/finish :outcome :released} is unreachable dead code in
        ;; both v2 and v3 (every branch of the guarded release already
        ;; finishes with :performed/:insufficient-resource/:no-item before
        ;; reaching it), ported byte-for-byte rather than "fixed".
        (is (= :performed (:outcome result)))
        (is (some #(and (= :entity/damage (:capability %)) (= "zombie-1" (:target %))) (:actions result)))
        (is (some #(= :inventory/place-or-drop (:capability %)) (:actions result)))
        (is (some #(= :owner-patch (:type %)) (:actions result)) "score/mark + cooldown/start")))))

(deftest shift-teleport-v3-abort-phase-cleans-up-test
  (let [state (catalog/initialize!)
        result (skill-runtime/execute!
                state :shift-teleport "owner-1"
                {:action :abort :from {:caster/id "owner-1" :world/id "overworld"}
                 :tunables shift-teleport-tunables
                 :session-state {:targets []}})]
    (is (= :accepted (:status result)))
    (is (= :aborted (:outcome result)))
    (is (= 1 (count (:vfx-signals result))))))

;; --- :mark-teleport: 2 v2 :fragments inlined (:refresh-destination
;; called 3x), :target/hold-destination composite, a program-computed
;; :costs entry (depends on :destination :distance) bypassing
;; :ability/budget, and :entity/teleport's newly-fixed :dismount?/
;; :reset-fall-damage? fields ---

(def ^:private mark-teleport-tunables
  {:minimum-distance 1.0 :maximum-range 30.0 :range-per-hold-tick 1.0 :cp-per-block 0.5
   :release-overload 0.0 :cooldown-ticks 40 :exp-per-distance 0.01 :entity-eye-height 1.6})

(deftest mark-teleport-v3-start-phase-marks-when-destination-valid-test
  (with-fake-raycast-handler
    {:valid? true :position {:vec3 [0.0 65.0 10.0]} :distance 10.0}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :mark-teleport "owner-1"
                    {:action :start
                     :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
                            :caster/aim {:x 0.0 :y 0.0 :z 1.0} :world/id "overworld" :charge/ticks 0}
                     :tunables mark-teleport-tunables
                     :context {:resources {:cp 10.0}}})]
        (is (= :accepted (:status result)))
        (is (= :started (:outcome result)))
        (is (= 1 (count (:vfx-signals result))))))))

(deftest mark-teleport-v3-release-phase-teleports-when-affordable-test
  (with-fake-raycast-handler
    {:valid? true :position {:vec3 [0.0 65.0 10.0]} :distance 10.0}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :mark-teleport "owner-1"
                    {:action :release
                     :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
                            :caster/aim {:x 0.0 :y 0.0 :z 1.0} :world/id "overworld" :charge/ticks 0}
                     :tunables mark-teleport-tunables
                     :context {:resources {:cp 10.0}}})]
        (is (= :accepted (:status result)))
        (is (= :teleported (:outcome result)))
        (is (some #(and (= :entity/teleport (:capability %)) (true? (:dismount? %))) (:actions result)))
        (is (some #(= :owner-patch (:type %)) (:actions result)) "score/mark + cooldown/start")))))

(deftest mark-teleport-v3-release-phase-rejects-when-insufficient-cp-test
  (with-fake-raycast-handler
    {:valid? true :position {:vec3 [0.0 65.0 10.0]} :distance 100.0}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :mark-teleport "owner-1"
                    {:action :release
                     :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
                            :caster/aim {:x 0.0 :y 0.0 :z 1.0} :world/id "overworld" :charge/ticks 0}
                     :tunables mark-teleport-tunables
                     :context {:resources {:cp 1.0}}})]
        (is (= :accepted (:status result)))
        (is (= :insufficient-resource (:outcome result)))
        (is (not (some #(= :entity/teleport (:capability %)) (:actions result))))))))

(deftest mark-teleport-v3-release-phase-too-close-when-destination-invalid-test
  (with-fake-raycast-handler
    {:valid? false :position {:vec3 [0.0 65.0 1.0]} :distance 1.0}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :mark-teleport "owner-1"
                    {:action :release
                     :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
                            :caster/aim {:x 0.0 :y 0.0 :z 1.0} :world/id "overworld" :charge/ticks 0}
                     :tunables mark-teleport-tunables
                     :context {:resources {:cp 10.0}}})]
        (is (= :accepted (:status result)))
        (is (= :too-close (:outcome result)))))))

;; --- :penetrate-teleport: 2 v2 :fragments inlined (:refresh-destination
;; called 3x, :marker-signal called 4x with different args), the
;; :target/penetration-destination composite ---

(def ^:private penetrate-teleport-tunables
  {:max-distance 30.0 :cp-per-block 0.5 :release-overload 0.0 :cooldown-ticks 40
   :exp-per-distance 0.01 :scan-step 0.5})

(deftest penetrate-teleport-v3-start-phase-marks-available-destination-test
  (with-fake-raycast-handler
    {:available? true :distance 10.0 :marker-position {:vec3 [0.0 65.0 10.0]} :position {:vec3 [0.0 65.0 10.0]}}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :penetrate-teleport "owner-1"
                    {:action :start
                     :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
                            :caster/aim {:x 0.0 :y 0.0 :z 1.0} :world/id "overworld"}
                     :tunables penetrate-teleport-tunables
                     :context {:resources {:cp 10.0}}})]
        (is (= :accepted (:status result)))
        (is (= :started (:outcome result)))
        (is (= 1 (count (:vfx-signals result))))
        (is (= [255 255 255 255] (get-in (first (:vfx-signals result)) [:params :color])))))))

(deftest penetrate-teleport-v3-release-phase-teleports-when-affordable-test
  (with-fake-raycast-handler
    {:available? true :distance 10.0 :marker-position {:vec3 [0.0 65.0 10.0]} :position {:vec3 [0.0 65.0 10.0]}}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :penetrate-teleport "owner-1"
                    {:action :release
                     :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
                            :caster/aim {:x 0.0 :y 0.0 :z 1.0} :world/id "overworld"}
                     :tunables penetrate-teleport-tunables
                     :context {:resources {:cp 10.0}}})]
        (is (= :accepted (:status result)))
        (is (= :teleported (:outcome result)))
        (is (some #(= :entity/teleport (:capability %)) (:actions result)))
        (is (some #(= :owner-patch (:type %)) (:actions result)))))))

(deftest penetrate-teleport-v3-release-phase-unavailable-when-not-teleportable-test
  (with-fake-raycast-handler
    {:available? false :distance 0.0 :marker-position {:vec3 [0.0 65.0 0.5]} :position {:vec3 [0.0 65.0 0.5]}}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :penetrate-teleport "owner-1"
                    {:action :release
                     :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
                            :caster/aim {:x 0.0 :y 0.0 :z 1.0} :world/id "overworld"}
                     :tunables penetrate-teleport-tunables
                     :context {:resources {:cp 10.0}}})]
        (is (= :accepted (:status result)))
        (is (= :unavailable (:outcome result)))
        (is (not (some #(= :entity/teleport (:capability %)) (:actions result))))))))

;; --- :threatening-teleport: 1 v2 :fragment inlined (:refresh-trace
;; called 3x), :inventory/settle, and the FIRST real content to exercise
;; the just-fixed {:expr ...}-wrapping-{:tunable} case in
;; resolve-tunable-refs (its :progression :hit/:miss entries are exactly
;; that shape) ---

(def ^:private threatening-teleport-tunables
  {:maximum-range 20.0 :damage 10.0 :needle-damage-multiplier 2.0 :release-cp 4.0
   :release-overload 0.0 :cooldown-ticks 40 :exp-base 0.1 :exp-hit-factor 1.0 :exp-miss-factor 0.5
   :drop-prob-hit 0.5 :drop-prob-miss 0.9})

(defn- with-fake-threatening-teleport [{:keys [held-item trace]} f]
  (let [previous-item (get (:queries (capabilities/snapshot)) :item/held)
        previous-raycast (get (:queries (capabilities/snapshot)) :raycast)]
    (try
      (capabilities/register-query! :item/held (fn [_request _frame] held-item))
      (capabilities/register-query! :raycast (fn [_request _frame] trace))
      (f)
      (finally
        (when previous-item (capabilities/register-query! :item/held previous-item))
        (when previous-raycast (capabilities/register-query! :raycast previous-raycast))))))

(deftest threatening-teleport-v3-start-phase-marks-when-item-present-test
  (with-fake-threatening-teleport
    {:held-item {:present? true :item-id "minecraft:trident"}
     :trace {:attacked? true :position {:vec3 [0.0 65.0 10.0]} :target-width 0.6 :target-height 1.8}}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :threatening-teleport "owner-1"
                    {:action :start
                     :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
                            :caster/aim {:x 0.0 :y 0.0 :z 1.0} :caster/body {:x 0.0 :y 64.0 :z 0.0}
                            :world/id "overworld"}
                     :tunables threatening-teleport-tunables})]
        (is (= :accepted (:status result)))
        (is (= :started (:outcome result)))
        (is (= 1 (count (:vfx-signals result))))))))

(deftest threatening-teleport-v3-release-phase-hits-and-damages-when-attacked-test
  (with-fake-threatening-teleport
    {:held-item {:present? true :item-id "academy:needle"}
     :trace {:attacked? true :target-id "zombie-1" :position {:vec3 [0.0 65.0 10.0]}
             :target-width 0.6 :target-height 1.8 :drop-position {:vec3 [0.0 65.0 9.5]}}}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :threatening-teleport "owner-1"
                    {:action :release
                     :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
                            :caster/aim {:x 0.0 :y 0.0 :z 1.0} :caster/body {:x 0.0 :y 64.0 :z 0.0}
                            :caster/creative? false :world/id "overworld"}
                     :tunables threatening-teleport-tunables
                     :context {:resources {:cp 10.0}}})]
        (is (= :accepted (:status result)))
        (is (= :performed (:outcome result)))
        (is (some #(and (= :entity/damage (:capability %)) (= "zombie-1" (:target %)) (= 20.0 (:amount %)))
                  (:actions result))
            "needle multiplies base damage 10.0 by 2.0")
        (is (some #(= :inventory/settle (:capability %)) (:actions result)))
        (is (some #(= :owner-patch (:type %)) (:actions result)) "score/mark + cooldown/start")))))

(deftest threatening-teleport-v3-release-phase-misses-and-scores-miss-when-not-attacked-test
  (with-fake-threatening-teleport
    {:held-item {:present? true :item-id "minecraft:trident"}
     :trace {:attacked? false :position {:vec3 [0.0 65.0 10.0]}
             :target-width 0.6 :target-height 1.8 :drop-position {:vec3 [0.0 65.0 9.5]}}}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :threatening-teleport "owner-1"
                    {:action :release
                     :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
                            :caster/aim {:x 0.0 :y 0.0 :z 1.0} :caster/body {:x 0.0 :y 64.0 :z 0.0}
                            :caster/creative? false :world/id "overworld"}
                     :tunables threatening-teleport-tunables
                     :context {:resources {:cp 10.0}}})]
        (is (= :accepted (:status result)))
        (is (= :performed (:outcome result)))
        (is (not (some #(= :entity/damage (:capability %)) (:actions result))))))))

(deftest mine-detect-v3-program-rejects-blindness-when-insufficient-resource-test
  (let [state (catalog/initialize!)
        result (skill-runtime/execute!
                state :mine-detect "owner-1"
                {:action :start
                 :from {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0}
                        :world/id "overworld" :progression/mastery 0.1 :progression/level 1}
                 :tunables {:blindness-duration-ticks 40 :blindness-amplifier 1 :targeting-range 20.0
                            :cost-down-cp 5.0 :cost-down-overload 0.0 :cooldown-ticks 200 :exp-cast 0.02}
                 :context {:resources {:cp 0.0}}})]
    (is (= :accepted (:status result)) "execute! itself is always :accepted for v3, see execute-v3!")
    (is (empty? (:actions result)))
    (is (empty? (:vfx-signals result)))))

;; --- :mag-movement: no :fragments (v2 :begin/:move/:finalize/
;; :finalize-no-target are 4 v2 fragments, all inlined at every call site),
;; :ability/caster's :normal-metal-blocks/:weak-metal-blocks/:metal-entities
;; output ports (replacing v2's {:from :targeting/...} facade reads),
;; :value/normalize-id (a new combat-only expr op ported from v2's own
;; (non-shared) evaluator -- this ability is its first real user), a
;; resource-floor pattern where a tunable only clamps a resource rather
;; than being spent via :cost/spend, and another dynamic (program-computed)
;; :progression (per-mark depends on {:ref [:local :traveled]}, built
;; inline like location-teleport's, not via :ability/progression) ---

(def ^:private mag-movement-tunables
  {:targeting-range 20.0 :acceleration 0.3 :weak-metal-exp-threshold 0.5
   :cost-down-overload 5.0 :cost-tick-cp 0.5 :exp-min 0.01 :exp-distance-scale 0.002})

(defn- with-fake-entity-snapshot [snapshot f]
  (let [previous (get (:queries (capabilities/snapshot)) :entity/snapshot)]
    (try
      (capabilities/register-query! :entity/snapshot (fn [_request _frame] snapshot))
      (f)
      (finally (when previous (capabilities/register-query! :entity/snapshot previous))))))

(def ^:private mag-movement-caster-facade
  {:caster/id "owner-1" :caster/eye {:x 0.0 :y 65.6 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}
   :caster/creative? false :world/id "overworld"
   :targeting/normal-metal-blocks ["minecraft:iron_block"]
   :targeting/weak-metal-blocks ["minecraft:copper_block"]
   :targeting/metal-entities ["minecraft:iron_golem"]})

(deftest mag-movement-compiles-with-engine-v3-test
  (let [state (catalog/initialize!)
        ability (get-in state [:combat :abilities :mag-movement])]
    (is (nil? (get-in state [:combat :errors :mag-movement])))
    (is (= :v3 (:engine ability)))
    (is (catalog/available? :mag-movement))))

(deftest mag-movement-v3-start-phase-locks-onto-a-normal-metal-block-test
  (with-fake-raycast-handler
    {:hit-type :block :block-id "minecraft:iron_block" :position {:vec3 [0.0 65.0 10.0]}}
    (fn []
      (with-fake-owner-snapshot {:position {:vec3 [0.0 64.0 0.0]} :velocity {:vec3 [0.0 0.0 0.0]}}
        (fn []
          (let [state (catalog/initialize!)
                result (skill-runtime/execute!
                        state :mag-movement "owner-1"
                        {:action :start :from mag-movement-caster-facade
                         :tunables mag-movement-tunables
                         :context {:resources {:overload 0.0}}})]
            (is (= :accepted (:status result)))
            (is (= :started (:outcome result)))
            (is (some #(= :session-patch (:type %)) (:actions result)))
            (is (= 2 (count (:vfx-signals result))) "arc-channel-session spawn + audio-loop-session spawn")))))))

(deftest mag-movement-v3-start-phase-no-target-when-block-is-not-metal-test
  (with-fake-raycast-handler
    {:hit-type :block :block-id "minecraft:stone" :position {:vec3 [0.0 65.0 10.0]}}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :mag-movement "owner-1"
                    {:action :start :from mag-movement-caster-facade
                     :tunables mag-movement-tunables})]
        (is (= :accepted (:status result)))
        (is (= :no-target (:outcome result)))
        (is (true? (:finish-session? result)))
        (is (empty? (:vfx-signals result)))))))

(deftest mag-movement-v3-pulse-phase-block-target-moves-owner-test
  (with-fake-owner-snapshot {:position {:vec3 [0.0 64.0 0.0]} :velocity {:vec3 [0.0 0.0 0.0]}}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :mag-movement "owner-1"
                    {:action :pulse :from mag-movement-caster-facade
                     :tunables mag-movement-tunables
                     :context {:resources {:cp 5.0}}
                     :session-state {:target-kind :block :target-position {:vec3 [0.0 65.0 10.0]}
                                      :motion {:vec3 [0.0 0.0 0.0]} :movement-ticks 3 :overload-floor 0.0}})]
        (is (= :accepted (:status result)))
        (is (= :continue (:outcome result)))
        (is (some #(= :motion/velocity (:capability %)) (:actions result)))
        (is (= 2 (count (:vfx-signals result))) "arc-channel-session update + audio-loop-session update")))))

(deftest mag-movement-v3-pulse-phase-entity-target-lost-when-dead-test
  (with-fake-entity-snapshot {:id "zombie-1" :alive? false}
    (fn []
      (with-fake-owner-snapshot {:position {:vec3 [0.0 64.0 0.0]}}
        (fn []
          (let [state (catalog/initialize!)
                result (skill-runtime/execute!
                        state :mag-movement "owner-1"
                        {:action :pulse :from mag-movement-caster-facade
                         :tunables mag-movement-tunables
                         :session-state {:target-kind :entity :target-id "zombie-1"
                                          :start-position {:vec3 [0.0 64.0 0.0]}
                                          :motion {:vec3 [0.0 0.0 0.0]} :movement-ticks 5 :overload-floor 0.0}})]
            (is (= :accepted (:status result)))
            (is (= :target-lost (:outcome result)))
            (is (true? (:finish-session? result)))))))))

(deftest mag-movement-v3-release-phase-finalizes-and-scores-distance-test
  (with-fake-owner-snapshot {:position {:vec3 [3.0 64.0 4.0]}}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :mag-movement "owner-1"
                    {:action :release :from mag-movement-caster-facade
                     :tunables mag-movement-tunables
                     :session-state {:start-position {:vec3 [0.0 64.0 0.0]}}})]
        (is (= :accepted (:status result)))
        (is (= :released (:outcome result)))
        (is (true? (:finish-session? result)))
        (is (some #(= :owner-patch (:type %)) (:actions result)) "score/mark emits an owner-patch")))))

(deftest mag-movement-v3-abort-phase-cleans-up-test
  (with-fake-owner-snapshot {:position {:vec3 [0.0 64.0 0.0]}}
    (fn []
      (let [state (catalog/initialize!)
            result (skill-runtime/execute!
                    state :mag-movement "owner-1"
                    {:action :abort :from mag-movement-caster-facade
                     :tunables mag-movement-tunables
                     :session-state {:start-position {:vec3 [0.0 64.0 0.0]}}})]
        (is (= :accepted (:status result)))
        (is (= :aborted (:outcome result)))
        (is (true? (:finish-session? result)))
        (is (= 2 (count (:vfx-signals result))) "arc-channel-session destroy + audio-loop-session destroy")))))
