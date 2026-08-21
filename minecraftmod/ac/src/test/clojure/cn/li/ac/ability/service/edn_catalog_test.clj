(ns cn.li.ac.ability.service.edn-catalog-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.service.combat-catalog :as catalog]
            [cn.li.ac.ability.service.combat-runtime-bridge :as execution]
             [cn.li.ac.ability.service.combat-sessions :as sessions]
            [cn.li.vfx.install :as vfx-install]
            [cn.li.vfx.runtime :as vfx-runtime])
  (:import [cn.li.mcmod.runtime.effect CompiledProgram]))

(deftest first-phase-catalog-is-authoritative
  (let [state (catalog/initialize!)]
    (is (true? (:initialized? state)))
    (is (catalog/available? :railgun))
    (is (catalog/available? :arc-gen))
    (is (catalog/available? :thunder-clap))
    (is (catalog/available? :vec-deviation))
    (is (catalog/available? :vec-accel))
    (is (catalog/available? :blood-retrograde))
    (is (catalog/available? :scatter-bomb))
    (is (catalog/available? :mark-teleport))
    (is (catalog/available? :penetrate-teleport))
    (is (catalog/available? :flashing))
    (is (catalog/available? :plasma-cannon))
    (is (catalog/available? :storm-wing))
    (is (catalog/available? :thunder-bolt))
    (is (catalog/available? :mine-detect))
    (is (catalog/available? :mag-movement))
    (is (catalog/available? :mag-manip))
    (is (catalog/available? :body-intensify))
    (is (catalog/available? :light-shield))
    (is (catalog/available? :meltdowner))
    (is (catalog/available? :electron-bomb))
    (is (catalog/available? :electron-missile))
    (is (catalog/available? :rad-intensify))
    (is (catalog/available? :mine-ray-basic))
    (is (catalog/available? :mine-ray-expert))
    (is (catalog/available? :mine-ray-luck))
    (is (= :migrated (catalog/migration-status :thunder-bolt)))
    (is (= :migrated (catalog/migration-status :vec-deviation)))
    (is (= :migrated (catalog/migration-status :vec-accel)))
    (is (= :migrated (catalog/migration-status :blood-retrograde)))
    (is (= :migrated (catalog/migration-status :mine-detect)))
    (is (= :migrated (catalog/migration-status :mag-movement)))
    (is (= :migrated (catalog/migration-status :mag-manip)))
    (is (= :migrated (catalog/migration-status :body-intensify)))
    (is (= :migrated (catalog/migration-status :light-shield)))
    (is (= :migrated (catalog/migration-status :meltdowner)))
    (is (= :migrated (catalog/migration-status :rad-intensify)))
    (is (= :railgun (get-in state [:combat :abilities :railgun :id])))
    (let [parameters (get-in state [:combat :abilities :railgun :parameters])]
      (is (every? #(contains? % :value) (vals parameters)))
      (is (not-any? #(contains? % :source) (vals parameters)))
      (is (not-any? #(contains? % :path) (vals parameters))))
    (is (contains? (get-in state [:combat :composites]) :combat/impact-strike))
    (is (contains? (get-in state [:combat :composites]) :combat/area-damage))
    (is (contains? (get-in state [:combat :composites]) :combat/charged-area-damage))
    (is (contains? (get-in state [:combat :composites]) :target/raycast-destination))
    (is (contains? (get-in state [:combat :composites]) :target/hold-destination))
    (is (contains? (get-in state [:combat :composites]) :target/penetration-destination))
    (is (contains? (get-in state [:combat :composites]) :target/directional-destination))
    (is (contains? (get-in state [:vfx :composites]) :vfx/beam-arc-fade))
    (is (contains? (get-in state [:vfx :composites]) :vfx/humanoid-marker))
    (is (= :beam-arc-fade (get-in state [:vfx :effects :beam-arc-fade :id])))
    (is (= :arc-strike-transient
           (get-in state [:vfx :effects :arc-strike-transient :id])))
    (is (= :block-scan-transient
           (get-in state [:vfx :effects :block-scan-transient :id])))
    (is (= :endpoint-burst (get-in state [:vfx :effects :endpoint-burst :id])))
    (is (= :thunder-clap (get-in state [:combat :abilities :thunder-clap :id])))
    (is (= :vec-deviation (get-in state [:combat :abilities :vec-deviation :id])))
    (is (= :vec-accel (get-in state [:combat :abilities :vec-accel :id])))
    (is (= :blood-retrograde (get-in state [:combat :abilities :blood-retrograde :id])))
    (is (pos? (count (get-in state [:combat :abilities :blood-retrograde :compiled-ir]))))
    (is (nil? (get-in state [:combat :errors :blood-retrograde])))
    (is (pos? (count (get-in state [:combat :abilities :vec-accel :compiled-ir]))))
    (is (nil? (get-in state [:combat :errors :vec-accel])))
    (is (pos? (count (get-in state [:combat :abilities :vec-deviation :compiled-ir]))))
    (is (nil? (get-in state [:combat :errors :vec-deviation])))
    (is (= :electron-bomb (get-in state [:combat :abilities :electron-bomb :id])))
    (is (pos? (count (get-in state [:combat :abilities :electron-bomb :compiled-ir]))))
    (is (= :electron-missile (get-in state [:combat :abilities :electron-missile :id])))
    (is (pos? (count (get-in state [:combat :abilities :electron-missile :compiled-ir]))))
    (is (= :rad-intensify (get-in state [:combat :abilities :rad-intensify :id])))
    (is (= [1.4 1.8]
           (get-in state [:combat :abilities :rad-intensify :tunables :damage-rate :value])))
    (is (= 60
           (get-in state [:combat :abilities :rad-intensify :tunables :mark-duration-ticks :value])))
    (is (pos? (count (get-in state [:combat :abilities :rad-intensify :compiled-ir]))))
    (is (= :mine-ray-basic (get-in state [:combat :abilities :mine-ray-basic :id])))
    (is (= :mine-ray-expert (get-in state [:combat :abilities :mine-ray-expert :id])))
    (is (= :mine-ray-luck (get-in state [:combat :abilities :mine-ray-luck :id])))
    (is (= :basic (get-in state [:combat :abilities :mine-ray-basic :runtime :variant])))
    (is (= :expert (get-in state [:combat :abilities :mine-ray-expert :runtime :variant])))
    (is (= :luck (get-in state [:combat :abilities :mine-ray-luck :runtime :variant])))
    (is (pos? (count (get-in state [:combat :abilities :mine-ray-basic :compiled-ir]))))
    (is (pos? (count (get-in state [:combat :abilities :mine-ray-expert :compiled-ir]))))
    (is (pos? (count (get-in state [:combat :abilities :mine-ray-luck :compiled-ir]))))
    (is (= :mark-teleport (get-in state [:combat :abilities :mark-teleport :id])))
    (is (= :penetrate-teleport (get-in state [:combat :abilities :penetrate-teleport :id])))
    (is (= :flashing (get-in state [:combat :abilities :flashing :id])))
    (is (= :plasma-cannon (get-in state [:combat :abilities :plasma-cannon :id])))
    (is (= :storm-wing (get-in state [:combat :abilities :storm-wing :id])))
    (is (pos? (count (get-in state [:combat :abilities :storm-wing :compiled-ir]))))
    (is (pos? (count (get-in state [:combat :abilities :mag-movement :compiled-ir]))))
    (is (pos? (count (get-in state [:combat :abilities :mag-manip :compiled-ir]))))
    (is (pos? (count (get-in state [:combat :abilities :body-intensify :compiled-ir]))))
    (is (pos? (count (get-in state [:combat :abilities :light-shield :compiled-ir]))))
    (is (pos? (count (get-in state [:combat :abilities :plasma-cannon :compiled-ir]))))
    (is (pos? (count (get-in state [:combat :abilities :flashing :compiled-ir]))))
    (is (pos? (count (get-in state [:combat :abilities :penetrate-teleport :compiled-ir]))))
    (is (= :audio-one-shot
           (get-in state [:vfx :effects :audio-one-shot :id])))
    (is (= :blood-retrograde-charge
           (get-in state [:vfx :effects :blood-retrograde-charge :id])))
    (is (= :blood-retrograde-impact
           (get-in state [:vfx :effects :blood-retrograde-impact :id])))
    (is (= :teleport-marker (get-in state [:vfx :effects :teleport-marker :id])))
    (is (= :energy-orb-session (get-in state [:vfx :effects :energy-orb-session :id])))
    (is (= :vortex-column-session (get-in state [:vfx :effects :vortex-column-session :id])))
    (is (= :camera-fov-session (get-in state [:vfx :effects :camera-fov-session :id])))
    (is (= :particle-session (get-in state [:vfx :effects :particle-session :id])))
    (is (= :audio-loop-session (get-in state [:vfx :effects :audio-loop-session :id])))
    (is (= :target-mark-session (get-in state [:vfx :effects :target-mark-session :id])))
    (is (nil? (get-in state [:vfx :effects :particle-audio-session])))
    (is (= :arc-ring-session (get-in state [:vfx :effects :arc-ring-session :id])))
    (let [marker (get-in state [:vfx :effects :teleport-marker])
          model-node (some (fn [{:keys [node]}]
                             (when (= :vfx/model-marker (:component node)) node))
                           (get-in marker [:graph :children]))]
      (is (some? model-node))
      (is (= 7 (:frame-count model-node)))
      (is (= 2.5 (:frame-period-ticks model-node)))
    (is (= 7 (count (:parts model-node)))))))

(deftest meltdowner-edn-program-includes-complete-beam-contract
  (catalog/initialize!)
  (let [ability (get-in (catalog/catalog) [:combat :abilities :meltdowner])
        beam-node (some (fn [node]
                          (when (and (map? node)
                                     (= :host/beam-trace (:component node)))
                            node))
                        (tree-seq coll? seq
                                  (first (:compiled-ir ability))))]
    (is (= :meltdowner (:id ability)))
    (is (pos? (count (:compiled-ir ability))))
    (is (some? beam-node))
    (is (= :caster/body (get-in beam-node [:trace-origin :from])))
    (is (contains? beam-node :reflection-policy))
    (is (= :beam-arc-fade
           (get-in (catalog/catalog) [:vfx :effects :beam-arc-fade :id])))
    (is (= :ray-beam-transient
           (get-in (catalog/catalog) [:vfx :effects :ray-beam-transient :id])))
    ;; The ability's own :vfx metadata block (a hand-maintained duplicate of
    ;; each referenced VFX effect's :inputs, and never read by anything --
    ;; see combat_catalog.clj's vfx-contract-errors) is gone; :program's own
    ;; :effect/vfx nodes are the only source of truth now.
    (is (some #(and (map? %) (= :camera-fov-session (:effect-id %)))
              (tree-seq coll? seq (:program ability))))))

(deftest light-shield-start-executes-compiled-program
  (catalog/initialize!)
  (let [result (execution/execute!
                :light-shield "owner-shield"
                {:action :start
                 :context {:world-id "world"
                           :resources {:cp 1000.0 :overload 500.0}
                           :skill-exp 0.0}
                 :from {:caster/id "owner-shield"
                        :caster/body {:x 0.0 :y 64.0 :z 0.0}
                        :caster/eye {:x 0.0 :y 65.62 :z 0.0}
                        :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                        :caster/creative? false
                        :world/id "world"}
                 :tunables {:touch-damage 2.0 :touch-radius 3.0
                            :absorb-damage 15.0 :absorb-interval-ticks 18
                            :front-cone-degrees 60.0 :max-active-ticks 120.0
                            :slowness-duration-ticks 100 :slowness-amplifier 1
                            :activate-overload 110.0 :tick-cp 9.0
                            :touch-cp 50.0 :touch-overload 5.0
                            :absorb-cp 50.0 :absorb-overload 5.0
                            :exp-tick 0.000001 :exp-touch 0.001
                            :exp-attacked 0.001}})]
    (is (= :accepted (:status result)))
    (is (= :started (:outcome result)))
    (is (some #(= :session-patch (:type %)) (:actions result)))
    (is (some #(= :entity/spawn (:capability %)) (:actions result)))))

(deftest migrated-entry-executes-compiled-program
  (catalog/initialize!)
  (let [compiled (get-in (catalog/catalog) [:combat :abilities :railgun])
        _ (do
            (is (pos? (count (:compiled-ir compiled))))
            (is (pos? (alength ^objects (.-objectConstants ^CompiledProgram (:compiled-program compiled))))))
        result (execution/execute! :railgun "owner-1"
                                   {:action :start
                                    :from {:caster/eye {:x 0.0 :y 0.0 :z 0.0}
                                           :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                                           :caster/id "owner-1"}
                                    :tunables {:charge-ticks 20}})]
    (is (= :accepted (:status result)))
    (is (= :railgun (:ability-id result)))
    (is (= :started (:outcome result)))))

(deftest mag-movement-start-executes-no-target-path
  (catalog/initialize!)
  (let [result (execution/execute!
                :mag-movement "owner-mag"
                {:action :start
                 :context {:world-id "world"
                           :resources {:cp 100.0 :overload 100.0}}
                 :from {:caster/id "owner-mag"
                        :caster/body {:x 0.0 :y 0.0 :z 0.0}
                        :caster/eye {:x 0.0 :y 1.62 :z 0.0}
                        :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                        :caster/creative? false
                        :world/id "world"
                        :targeting/normal-metal-blocks ["minecraft:iron_block"]
                        :targeting/weak-metal-blocks []
                        :targeting/metal-entities []}
                 :tunables {:targeting-range 25.0
                            :acceleration 0.08
                            :weak-metal-exp-threshold 0.6
                            :cost-down-overload 30.0
                            :cost-tick-cp 8.0
                            :exp-min 0.005
                            :exp-distance-scale 0.0011}})]
    (is (= :accepted (:status result)))
    (is (= :no-target (:outcome result)))
    (is (some #(= :owner-patch (:type %)) (:actions result)))))

(deftest mag-manip-start-executes-no-target-path
  (catalog/initialize!)
  (let [result (execution/execute!
                :mag-manip "owner-mag"
                {:action :start
                 :context {:world-id "world"
                           :resources {:cp 1000.0 :overload 100.0}}
                 :from {:caster/id "owner-mag"
                        :caster/body {:x 0.0 :y 0.0 :z 0.0}
                        :caster/eye {:x 0.0 :y 1.62 :z 0.0}
                        :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                        :caster/creative? false
                        :world/id "world"
                        :targeting/normal-metal-blocks ["minecraft:iron_block"]
                        :targeting/weak-metal-blocks []}
                 :tunables {:targeting-grab-range 10.0
                            :targeting-throw-range 20.0
                            :targeting-max-hold-distance 5.0
                            :movement-hold-distance 2.0
                            :movement-hold-head-y-offset 0.1
                            :movement-throw-speed 0.5
                            :cost-up-cp 140.0
                            :cost-up-overload 35.0
                            :cooldown-ticks 60.0
                            :progression-exp-throw 0.005}})]
    (is (= :accepted (:status result)))
    (is (= :no-target (:outcome result)))))

(deftest body-intensify-start-executes-compiled-program
  (catalog/initialize!)
  (let [result (execution/execute!
                :body-intensify "owner-body"
                {:action :start
                 :context {:world-id "world"
                           :resources {:cp 1000.0 :overload 500.0}}
                 :from {:caster/id "owner-body"
                        :caster/eye {:x 0.0 :y 1.62 :z 0.0}
                        :caster/creative? false
                        :world/id "world"}
                 :tunables {:charge-min-ticks 10
                            :charge-max-ticks 40
                            :charge-max-tolerant-ticks 100
                            :effect-probability-offset-ticks 10.0
                            :effect-probability-divisor 18.0
                            :effect-duration-multiplier 1.5
                            :effect-hunger-multiplier 1.25
                            :effect-hunger-amplifier 2
                            :effect-available-effects ["speed:3"]
                            :cost-down-overload 200.0
                            :cost-tick-cp 20.0
                            :cooldown-ticks 900.0
                            :progression-exp-use 0.01}})]
    (is (= :accepted (:status result)))
    (is (= :started (:outcome result)))
    (is (some #(= :owner-patch (:type %)) (:actions result)))
    (is (= 2 (count (:vfx-signals result))))))

(deftest session-index-is-neutral-and-tickable
  (catalog/initialize!)
  (sessions/reset-for-test!)
  (let [entry (sessions/start! "owner-1" :railgun
                               {:context {:world-id "world"}
                                :parameter-snapshot {:charge-ticks 20}
                                :activation-seed 7})]
    (is (= :railgun (:ability-id entry)))
    (is (sessions/active? "owner-1"))
    (is (= 12 (:tick (second (first (sessions/tick! 12))))))
    (is (= "world" (get-in (sessions/context-for "owner-1" {})
                            [:context :world-id])))
    (sessions/remove! "owner-1")
    (is (not (sessions/active? "owner-1")))))

(deftest every-real-vfx-effect-compiles-and-installs
  (let [state (catalog/initialize!)
        vfx-catalog (:vfx state)
        rt (vfx-runtime/create-runtime)]
    ;; Zero compile errors: every effect ac/vfx/effects/*.edn ships must
    ;; actually compile, not just the ones a handful of abilities happen
    ;; to reference.
    (is (empty? (:errors vfx-catalog)))
    ;; Before install-catalog! existed, an EDN effect compiled fine but had
    ;; no registered effect-id to spawn -- every ability's :effect/vfx
    ;; signal reached effect_controller.clj and was silently dropped as
    ;; "unregistered". This is the regression test for that gap.
    (let [registered (vfx-install/install-catalog! rt vfx-catalog)]
      (is (= (set (keys (:effects vfx-catalog))) registered))
      (is (contains? registered :beam-arc-fade))
      (is (contains? registered :arc-ring-session)))))

(deftest a-real-vfx-effect-actually-spawns-ticks-and-samples
  (let [state (catalog/initialize!)
        rt (vfx-runtime/create-runtime)]
    (vfx-install/install-catalog! rt (:vfx state))
    (vfx-runtime/freeze-registry! rt)
    ;; billboard-session (railgun's charge VFX): :spawn only needs :anchor
    ;; and the timing/texture fields it declares, all supplied here.
    (let [id (vfx-runtime/spawn!
              rt :billboard-session
              {:owner "owner"
               :params {:anchor {:vec3 [0.0 1.6 0.0]} :duration-ticks 64
                        :texture-pattern "academy:textures/effects/arc_burst/%d.png"
                        :frame-count 40 :frame-duration-ms 40 :half-size 0.4}})]
      (is (some? id))
      (vfx-runtime/tick! rt {:tick-id 1 :delta-seconds 0.05})
      (let [frame (vfx-runtime/sample-frame! rt {:frame-id 1 :partial-tick 0.0})
            batches (get-in frame [:stages :world-after-translucent])]
        (is (= 1 (count batches)))
        (is (= :billboard (:primitive (first batches))))))))
