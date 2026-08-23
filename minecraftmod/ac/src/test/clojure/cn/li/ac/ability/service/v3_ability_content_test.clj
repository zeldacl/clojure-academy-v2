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
