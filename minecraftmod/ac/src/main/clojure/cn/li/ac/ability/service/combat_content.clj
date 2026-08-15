(ns cn.li.ac.ability.service.combat-content
  "Neutral combat content registration owned by AC's composition root.

   This namespace contains only Clojure data/DSL. It does not reach a
   Minecraft object, packet, presentation host or VFX runtime.")

(defn- scale
  "Return a bounded skill-exp interpolation expression for Combat Core.

   Keeping the interpolation as data lets the compiler hash and validate it
   once while the authoritative runtime supplies the owner's immutable exp
   snapshot at execution time."
  [min-value max-value]
  {:op :scale :min (double min-value) :max (double max-value)})

(defn- session-value [path]
  {:op :session :path path})

(defn- charge-ratio
  "Thunder Clap overcharge ratio for the documented 40..60 tick window."
  []
  {:op :clamp :min 0.0 :max 1.0
   :value {:op :add
           :values [{:op :multiply
                     :values [(session-value [:charge-ticks]) 0.05]}
                    -2.0]}})

(defn- overcharge-multiplier []
  {:op :add
   :values [1.0 {:op :multiply :values [0.2 (charge-ratio)]}]})

(def provider
  {:provider-id :academy/base
   :revision 1
   :abilities
   [{:id :arc-gen
     :revision 1
     :activation :instant
     :cost {:cp 18}
     :cooldown {:ticks 10}
     :program {:op :sequence
               :steps [{:op :query :query-type :raycast
                        :distance 12.0 :result-ref :hit}
                       {:op :require :predicate :hit}
                       {:op :damage :amount 7.0 :type :electric
                        :target-ref :hit}
                       {:op :vfx :effect-id :arc-gen
                        :event :impact :params {:strength 1.0}}]}}
    {:id :current-charging
     :revision 1
     :activation :session
     :period-ticks 5
     :cost {:cp 2}
     :program {:op :sequence
               :steps [{:op :query :query-type :charge-target
                        :result-ref :charge-target}
                       {:op :require :predicate :charge-target}
                       {:op :world-effect :effect-type :charge-energy
                        :target-ref :charge-target :amount 1.0}
                       {:op :patch :entries [[:resource :cp -1.0]]}
                       {:op :vfx :effect-id :current-charging
                        :event :pulse :params {:strength 0.6}}]}}
    {:id :body-intensify
     :revision 1
     :activation :toggle
     :period-ticks 10
     :cost {:cp 1}
     :program {:op :sequence
               :steps [{:op :patch :entries [[:resource :cp -1.0]]}
                       {:op :vfx :effect-id :body-intensify
                        :event :active :params {:strength 0.35}}]}}
    {:id :vec-reflection
     :revision 1
     :activation :passive
     :program {:op :sequence
               :steps [{:op :require :predicate :damage-event}
                       {:op :domain-event :event-type :reflect-damage
                        :metadata {:source :vec-reflection}}]}}
    {:id :dim-folding-theorem
     :revision 1
     :activation :passive
     :program {:op :sequence
               :steps [{:op :require :predicate :critical-event}
                       {:op :domain-event :event-type :critical-modifier
                        :metadata {:source :dim-folding-theorem
                                   :level 0}}]}}
    {:id :space-fluct
     :revision 1
     :activation :passive
     :program {:op :sequence
               :steps [{:op :require :predicate :critical-event}
                       {:op :domain-event :event-type :critical-modifier
                        :metadata {:source :space-fluct}}]}}
    {:id :rad-intensify
     :revision 1
     :activation :passive
     :program {:op :sequence
               :steps [{:op :require :predicate :damage-request}
                       {:op :domain-event :event-type :damage-modifier
                        :metadata {:source :rad-intensify
                                   :multiplier-source :skill-exp}}]}}
    {:id :location-teleport
     :revision 1
     :activation :instant
     :cost {:cp 12}
     :cooldown {:ticks 20}
     :program {:op :sequence
               :steps [{:op :query :query-type :saved-location
                        :result-ref :destination}
                       {:op :require :predicate :destination}
                       {:op :world-effect :effect-type :teleport-approved
                        :target-ref :destination
                        :radius 5.0}
                       {:op :vfx :effect-id :location-teleport
                        :event :release :params {:strength 1.0}}]}}
    {:id :railgun
     :revision 1
     :activation :instant
     :cost {:cp 30}
     :cooldown {:ticks 40}
     :program {:op :sequence
               :steps [{:op :query :query-type :raycast
                        :distance 64.0 :result-ref :hit}
                       {:op :require :predicate :hit}
                       {:op :damage :amount 28.0 :type :electric
                        :target-ref :hit}
                      {:op :vfx :effect-id :railgun
                        :event :release :params {:strength 1.0}}]}}
    {:id :mine-detect
     :revision 1
     :activation :instant
     :cost {:cp 1000}
     :cooldown {:ticks 400}
     :program {:op :sequence
               :steps [{:op :query :query-type :block-scan
                        :distance 30.0 :result-ref :scan}
                       {:op :require :predicate :scan}
                       {:op :world-effect :effect-type :mine-detect
                        :scan-ref :scan :blindness-ticks 100}
                       {:op :vfx :effect-id :mine-detect
                        :event :release :params {:range 30.0}}]}}
    {:id :thunder-bolt
     :revision 1
     :activation :instant
     :cost {:cp (scale 280.0 420.0)
            :overload (scale 50.0 27.0)}
     :cooldown {:ticks (scale 120.0 50.0)}
     :program {:op :sequence
               :steps [{:op :query :query-type :attack
                        :range 20.0
                        :aoe-radius 8.0
                        :result-ref :attack
                        :result-paths {:target [:target-uuid]
                                       :impact [:impact]
                                       :victims [:victims]}
                        :result-flags {:hit [:target-uuid]}}
                       {:op :branch
                        :predicate-ref :hit
                        :then {:op :damage
                               :amount (scale 10.0 25.0)
                               :type :electric
                               :target-ref :target}
                        :else {:op :patch :entries []}}
                       {:op :world-effect
                        :effect-type :damage-targets
                        :targets-ref :attack
                        :targets-path [:victims]
                        :amount (scale 6.0 15.0)
                        :damage-type :electric}
                       {:op :world-effect
                        :effect-type :lightning
                        :origin-ref :attack
                        :origin-path [:impact]
                        :visual-only? true}
                       {:op :vfx :effect-id :thunder-bolt-strike
                       :event :perform
                        :params {:range 20.0 :aoe-radius 8.0}}]}}
    {:id :electron-bomb
     :revision 1
     :activation :instant
     :cooldown {:ticks (scale 20.0 10.0)}
     :program {:op :sequence
               :steps [{:op :query :query-type :raycast
                        :distance 20.0 :result-ref :aim}
                       {:op :world-effect
                        :effect-type :spawn-projectile
                        :projectile-spec {:kind :electron-bomb
                                          :damage (scale 6.0 12.0)
                                          :delay-ticks 20
                                          :target-ref :aim}}
                       {:op :vfx :effect-id :electron-bomb
                        :event :spawn
                       :params {:settle-ticks 20}}]}}
    {:id :flesh-ripping
     :revision 1
     :activation :session
     :period-ticks 1
     :cost-phase :release
     :cost {:cp (scale 130.0 270.0)
            :overload (scale 60.0 50.0)}
     :cooldown {:ticks (scale 90.0 40.0)}
     :program {:op :phase
               :release {:op :sequence
                         :steps [{:op :query :query-type :raycast
                                  :distance (scale 6.0 14.0)
                                  :result-ref :hit}
                                 {:op :require :predicate :hit}
                                 {:op :damage :amount (scale 5.0 12.0)
                                  :type :teleporter
                                  :target-ref :hit}
                                 {:op :vfx :effect-id :flesh-ripping
                                  :event :perform
                                  :params {:range-min 6.0 :range-max 14.0}}]}}}
    {:id :directed-shock
     :revision 1
     :activation :session
     :period-ticks 1
     :max-session-ticks 200
     :cost-phase :release
     :cost {:cp (scale 50.0 100.0)
            :overload (scale 18.0 12.0)}
     :cooldown {:ticks (scale 60.0 20.0)}
     :program {:op :phase
               :start {:op :session-patch
                       :entries [[[:charge-ticks] 0.0]]}
               :pulse {:op :session-patch
                       :entries [[[:charge-ticks]
                                  {:op :increment :amount 1.0}]]}
               :release {:op :sequence
                         :steps [{:op :require-session
                                  :path [:charge-ticks] :min 6.0 :max 50.0}
                                 {:op :query :query-type :raycast
                                  :distance 3.0 :result-ref :hit}
                                 {:op :require :predicate :hit}
                                 {:op :damage :amount (scale 7.0 15.0)
                                  :type :vector
                                  :target-ref :hit}
                                 {:op :world-effect :effect-type :knockback
                                  :target-ref :hit
                                  :movement {:impulse 0.24
                                             :knockback-y-adjust 0.6
                                             :knockback-scale -0.7}}
                                 {:op :vfx :effect-id :directed-shock
                                  :event :perform
                                  :params {:charge-min-ticks 6}}]}}}
    ;; Ray Barrage is represented as a bounded server-side ray recipe.  The
    ;; host owns the exact directional hit test and special-target handling;
    ;; Combat Core owns the authoritative costs, damage bands, ray count and
    ;; deterministic VFX signal.
    {:id :ray-barrage
     :revision 1
     :activation :instant
     :cost {:cp (scale 450.0 380.0)
            :overload (scale 300.0 140.0)}
     :cooldown {:ticks (scale 100.0 40.0)}
     :program {:op :sequence
               :steps [{:op :query :query-type :ray-barrage
                        :range 20.0
                        :result-ref :barrage}
                       {:op :world-effect
                        :effect-type :ray-barrage
                        :query-ref :barrage
                        :ray-count 5
                        :range 20.0
                        :cone-angle-degrees 55.0
                        :plain-damage (scale 25.0 60.0)
                        :scattered-damage (scale 10.0 18.0)
                        :special-target-policy :silbarn}
                       {:op :vfx :effect-id :ray-barrage
                        :event :perform
                        :params {:ray-count 5 :cone-angle-degrees 55.0}}]}}
    {:id :directed-blastwave
     :revision 1
     :activation :session
     :period-ticks 1
     :max-session-ticks 200
     :cost-phase :release
     :cost {:cp (scale 160.0 200.0)
            :overload (scale 50.0 30.0)}
     :cooldown {:ticks (scale 80.0 50.0)}
     :program {:op :phase
               :start {:op :session-patch
                       :entries [[[:charge-ticks] 0.0]]}
               :pulse {:op :session-patch
                       :entries [[[:charge-ticks]
                                  {:op :increment :amount 1.0}]]}
               :release {:op :sequence
                         :steps [{:op :require-session
                                  :path [:charge-ticks] :min 6.0 :max 50.0}
                                 {:op :query :query-type :directed-blastwave
                                  :range 4.0 :result-ref :blastwave}
                                 {:op :world-effect
                                  :effect-type :directed-blastwave
                                  :query-ref :blastwave
                                  :ray-count 1
                                  :aoe-radius 3.0
                                  :amount (scale 10.0 25.0)
                                  :damage-type :vector
                                  :movement {:impulse 0.24
                                             :knockback-y-adjust 0.4
                                             :knockback-scale -1.2}
                                  :breaking {:hardness-caps [2.9 25.0 55.0]
                                             :break-probability [0.5 0.8]
                                             :drop-probability [0.4 0.9]}}
                                 {:op :vfx :effect-id :directed-blastwave
                                  :event :perform
                                  :params {:radius 3.0}}]}}}
    {:id :groundshock
     :revision 1
     :activation :session
     :period-ticks 1
     :max-session-ticks 200
     :cost-phase :release
     :cost {:cp (scale 80.0 150.0)
            :overload (scale 15.0 10.0)}
     :cooldown {:ticks (scale 80.0 40.0)}
     :program {:op :phase
               :start {:op :session-patch
                       :entries [[[:charge-ticks] 0.0]]}
               :pulse {:op :session-patch
                       :entries [[[:charge-ticks]
                                  {:op :increment :amount 1.0}]]}
               :release {:op :sequence
                         :steps [{:op :require-session
                                  :path [:charge-ticks] :min 5.0 :max 200.0}
                                 {:op :query :query-type :groundshock
                                  :range 12.0 :result-ref :ground}
                                 {:op :world-effect
                                  :effect-type :groundshock
                                  :query-ref :ground
                                  :amount (scale 4.0 6.0)
                                  :max-iterations (scale 10.0 25.0)
                                  :init-energy (scale 60.0 120.0)
                                  :entity-search-radius 2.0
                                  :launch-scale (scale 0.8 1.3)
                                  :launch-random-base 0.6
                                  :launch-random-span 0.3
                                  :breaking {:ground-break-probability 0.3
                                             :drop-rate (scale 0.3 1.0)}}
                                 {:op :vfx :effect-id :groundshock
                                  :event :perform
                                  :params {:charge-min-ticks 5}}]}}}
    {:id :thunder-clap
     :revision 1
     :activation :session
     :period-ticks 1
     :max-session-ticks 60
     :cost {:overload (scale 390.0 252.0)}
     :cooldown-phase :release
     :cooldown {:ticks {:op :multiply
                        :values [(session-value [:charge-ticks])
                                 (scale 10.0 6.0)]}}
     :program {:op :phase
               :start {:op :sequence
                       :steps [{:op :session-patch
                                :entries [[[:charge-ticks] 0.0]]}
                               {:op :query :query-type :thunder-clap
                                :range 40.0 :aoe-radius 15.0
                                :result-ref :clap}]}
               :pulse {:op :sequence
                       :steps [{:op :session-patch
                                :entries [[[:charge-ticks]
                                           {:op :increment :amount 1.0}]]}
                               {:op :patch
                                :entries [[:resource :cp
                                           {:op :multiply
                                            :values [-1.0
                                                     {:op :scale :min 18.0 :max 25.0}]}]]}
                               {:op :query :query-type :thunder-clap
                                :range 40.0 :aoe-radius 15.0
                                :result-ref :clap}]}
               :release {:op :sequence
                         :steps [{:op :require-session
                                  :path [:charge-ticks] :min 40.0 :max 60.0}
                                 {:op :query :query-type :thunder-clap
                                  :range 40.0 :aoe-radius 15.0
                                  :result-ref :clap}
                                 {:op :world-effect
                                  :effect-type :thunder-clap
                                  :query-ref :clap
                                  :charge-ticks (session-value [:charge-ticks])
                                  :amount {:op :multiply
                                           :values [(scale 36.0 72.0)
                                                    (overcharge-multiplier)]}
                                  :aoe-radius (scale 15.0 30.0)
                                  :cooldown-multiplier (scale 10.0 6.0)}
                                 {:op :vfx :effect-id :thunder-clap
                                  :event :perform
                                  :params {:min-charge-ticks 40
                                           :max-charge-ticks 60}}]}}}
    {:id :blood-retrograde
     :revision 1
     :activation :session
     :period-ticks 1
     :max-session-ticks 30
     :cost-phase :release
     :cost {:cp (scale 280.0 350.0)
            :overload (scale 55.0 40.0)}
     :cooldown {:ticks (scale 90.0 40.0)}
     :program {:op :phase
               :start {:op :session-patch
                       :entries [[[:charge-ticks] 0.0]]}
               :pulse {:op :session-patch
                       :entries [[[:charge-ticks]
                                  {:op :increment :amount 1.0}]]}
               :release {:op :sequence
                         :steps [{:op :query :query-type :blood-retrograde
                                  :distance 2.0 :result-ref :hit}
                                 {:op :require :predicate :hit}
                                 {:op :world-effect
                                  :effect-type :blood-retrograde
                                  :query-ref :hit
                                  :amount (scale 30.0 60.0)
                                  :max-charge-ticks 30.0
                                  :entity-search-radius 4.0
                                  :spray-angles [0.0 30.0 45.0 60.0 80.0
                                                 -30.0 -45.0 -60.0 -80.0]}
                                 {:op :vfx :effect-id :blood-retrograde
                                  :event :perform
                                 :params {:max-charge-ticks 30}}]}}}
    {:id :electron-missile
     :revision 1
     :activation :session
     :period-ticks 1
     :max-session-ticks 200
     :cost-phase :pulse
     :cost {:cp (scale 12.0 5.0)}
     :cooldown {:ticks 700}
     :program {:op :phase
               :start {:op :patch
                       :entries [[:resource :overload -200.0]]}
               :pulse {:op :sequence
                       :steps [{:op :query :query-type :electron-missile
                                :seek-range (scale 5.0 13.0)
                                :result-ref :missile}
                               {:op :world-effect
                                :effect-type :electron-missile
                                :query-ref :missile
                                :damage (scale 10.0 18.0)
                                :seek-range (scale 5.0 13.0)
                                :spawn-interval 10
                                :fire-interval 8
                                :max-balls 5
                                :max-hold-ticks 200
                                :attack-cp (scale 60.0 25.0)
                                :attack-overload (scale 9.0 4.0)}
                               {:op :vfx :effect-id :electron-missile
                                :event :update
                                :params {:max-balls 5
                                         :spawn-interval 10
                                         :fire-interval 8}}]}}}
    {:id :scatter-bomb
     :revision 1
     :activation :session
     :period-ticks 1
     :max-session-ticks 200
     :cost-phase :pulse
     :cost {:cp (scale 3.0 6.0)}
     :program {:op :phase
               :start {:op :sequence
                       :steps [{:op :patch
                                :entries [[:resource :overload
                                           {:op :multiply
                                            :values [-1.0 (scale 80.0 60.0)]}]]}
                               {:op :session-patch
                                :entries [[[:ball-count] 0.0]]}]}
               :pulse {:op :session-patch
                       :entries [[[:ball-count]
                                  {:op :increment :amount 1.0}]]}
               :release {:op :sequence
                         :steps [{:op :query :query-type :scatter-bomb
                                  :result-ref :scatter}
                                 {:op :world-effect
                                  :effect-type :scatter-bomb
                                  :query-ref :scatter
                                  :ball-count {:op :clamp :min 0.0 :max 7.0
                                               :value (session-value [:ball-count])}
                                  :scatter-range 16.0
                                  :scatter-angle-degrees 35.0
                                  :auto-aim-radius 5.0
                                  :damage (scale 8.0 16.0)
                                  :anti-afk-tick 200
                                  :anti-afk-damage 6.0}
                                 {:op :vfx :effect-id :scatter-bomb
                                  :event :release
                                  :params {:max-balls 7}}]}}}
    {:id :plasma-cannon
     :revision 1
     :activation :session
     :period-ticks 1
     :max-session-ticks 120
     :cost-phase :release
     :cost {:cp (scale 180.0 120.0)
            :overload (scale 70.0 45.0)}
     :cooldown {:ticks (scale 100.0 60.0)}
     :program {:op :phase
               :pulse {:op :session-patch
                       :entries [[[:charge-ticks]
                                  {:op :increment :amount 1.0}]]}
               :release {:op :sequence
                         :steps [{:op :require-session
                                  :path [:charge-ticks] :min 10.0 :max 120.0}
                                 {:op :query :query-type :attack
                                  :range 32.0 :aoe-radius 8.0
                                  :result-ref :impact}
                                 {:op :world-effect
                                  :effect-type :plasma-cannon
                                  :query-ref :impact
                                  :charge-ticks (session-value [:charge-ticks])
                                  :damage (scale 30.0 80.0)
                                  :explosion-radius (scale 3.0 8.0)}
                                 {:op :vfx :effect-id :plasma-cannon
                                  :event :release
                                  :params {:max-charge-ticks 120}}]}}}
    ;; Meltdowner's host port owns the beam trace, block breaking and
    ;; Vector-Reflection callback. Combat Core supplies only charge and
    ;; balance data; the 20..40 time-rate is evaluated by the port.
    {:id :meltdowner
     :revision 1
     :activation :session
     :period-ticks 1
     :max-session-ticks 100
     :cost-phase :release
     :cost {:cp (scale 10.0 15.0)
            :overload (scale 200.0 170.0)}
     :cooldown {:ticks (scale 300.0 140.0)}
     :program {:op :phase
               :pulse {:op :session-patch
                       :entries [[[:charge-ticks]
                                  {:op :increment :amount 1.0}]]}
               :release {:op :sequence
                         :steps [{:op :require-session
                                  :path [:charge-ticks] :min 20.0 :max 100.0}
                                 {:op :query :query-type :raycast
                                  :distance 64.0 :result-ref :target}
                                 {:op :require :predicate :target}
                                 {:op :world-effect
                                  :effect-type :meltdowner
                                  :target-ref :target
                                  :charge-ticks (session-value [:charge-ticks])
                                  :damage (scale 18.0 50.0)
                                  :beam-radius (scale 2.0 3.0)
                                  :max-distance 64.0
                                  :block-energy (scale 1.0 3.0)
                                  :reflection {:enabled? true
                                               :shot-distance 64.0
                                               :damage-multiplier 1.0}}
                                 {:op :vfx :effect-id :meltdowner
                                  :event :release :params {:range 64.0}}]}}}
    {:id :mine-ray-basic
     :revision 1
     :activation :session
     :period-ticks 1
     :max-session-ticks 200
     :cost-phase :pulse
     :cost {:cp (scale 12.0 7.0)
            :overload (scale 200.0 150.0)}
     :cooldown {:ticks (scale 40.0 20.0)}
     :program {:op :sequence
               :steps [{:op :query :query-type :block-scan
                        :distance 10.0 :result-ref :scan}
                       {:op :world-effect :effect-type :mine-ray
                        :scan-ref :scan :range 10.0
                        :break-speed (scale 0.2 0.4) :fortune 0}
                       {:op :vfx :effect-id :mine-ray-basic
                        :event :pulse :params {:range 10.0}}]}}
    {:id :mine-ray-expert
     :revision 1
     :activation :session
     :period-ticks 1
     :max-session-ticks 200
     :cost-phase :pulse
     :cost {:cp (scale 25.0 15.0)
            :overload (scale 300.0 200.0)}
     :cooldown {:ticks (scale 60.0 30.0)}
     :program {:op :sequence
               :steps [{:op :query :query-type :block-scan
                        :distance 20.0 :result-ref :scan}
                       {:op :world-effect :effect-type :mine-ray
                        :scan-ref :scan :range 20.0
                        :break-speed (scale 0.5 1.0) :fortune 0}
                       {:op :vfx :effect-id :mine-ray-expert
                        :event :pulse :params {:range 20.0}}]}}
    {:id :mine-ray-luck
     :revision 1
     :activation :session
     :period-ticks 1
     :max-session-ticks 200
     :cost-phase :pulse
     :cost {:cp (scale 50.0 35.0)
            :overload (scale 350.0 300.0)}
     :cooldown {:ticks (scale 60.0 30.0)}
     :program {:op :sequence
               :steps [{:op :query :query-type :block-scan
                        :distance 20.0 :result-ref :scan}
                       {:op :world-effect :effect-type :mine-ray
                        :scan-ref :scan :range 20.0
                        :break-speed (scale 0.5 1.0) :fortune 3}
                       {:op :vfx :effect-id :mine-ray-luck
                       :event :pulse :params {:range 20.0 :fortune 3}}]}}
    {:id :mark-teleport
     :revision 1
     :activation :session
     :period-ticks 1
     :max-session-ticks 60
     :cost-phase :release
     :cost {:cp (scale 40.0 20.0)
            :overload (scale 30.0 15.0)}
     :cooldown {:ticks (scale 30.0 0.0)}
     :program {:op :phase
               :pulse {:op :session-patch
                       :entries [[[:hold-ticks]
                                  {:op :increment :amount 1.0}]]}
               :release {:op :sequence
                         :steps [{:op :query :query-type :teleport-target
                                  :mode :mark
                                  :max-range (scale 25.0 60.0)
                                  :result-ref :destination}
                                 {:op :require :predicate :destination}
                                 {:op :world-effect
                                  :effect-type :teleport-approved-target
                                  :target-ref :destination
                                  :mode :mark}
                                 {:op :vfx :effect-id :mark-teleport
                                  :event :release :params {:max-range 60.0}}]}}}
    {:id :penetrate-teleport
     :revision 1
     :activation :session
     :period-ticks 1
     :max-session-ticks 60
     :cost-phase :release
     :cost {:cp (scale 40.0 20.0)
            :overload (scale 30.0 15.0)}
     :cooldown {:ticks (scale 30.0 0.0)}
     :program {:op :phase
               :pulse {:op :session-patch
                       :entries [[[:hold-ticks]
                                  {:op :increment :amount 1.0}]]}
               :release {:op :sequence
                         :steps [{:op :query :query-type :teleport-target
                                  :mode :penetrate
                                  :max-range (scale 16.0 32.0)
                                  :result-ref :destination}
                                 {:op :require :predicate :destination}
                                 {:op :world-effect
                                  :effect-type :teleport-approved-target
                                  :target-ref :destination
                                  :mode :penetrate}
                                 {:op :vfx :effect-id :penetrate-teleport
                                  :event :release :params {:max-range 32.0}}]}}}
    {:id :shift-teleport
     :revision 1
     :activation :instant
     :cost {:cp (scale 260.0 320.0)}
     :cooldown {:ticks (scale 100.0 60.0)}
     :program {:op :sequence
               :steps [{:op :query :query-type :teleport-target
                        :mode :shift :max-range (scale 25.0 35.0)
                        :result-ref :destination}
                       {:op :require :predicate :destination}
                       {:op :world-effect
                        :effect-type :teleport-approved-target
                        :target-ref :destination :mode :shift}
                       {:op :vfx :effect-id :shift-teleport
                        :event :release :params {:max-range 35.0}}]}}
    {:id :threatening-teleport
     :revision 1
     :activation :session
     :period-ticks 1
     :max-session-ticks 60
     :cost-phase :release
     :cost {:cp (scale 35.0 100.0)
            :overload (scale 18.0 10.0)}
     :cooldown {:ticks (scale 30.0 15.0)}
     :program {:op :phase
               :pulse {:op :session-patch
                       :entries [[[:hold-ticks]
                                  {:op :increment :amount 1.0}]]}
               :release {:op :sequence
                         :steps [{:op :query :query-type :teleport-target
                                  :mode :threatening
                                  :max-range (scale 8.0 15.0)
                                  :result-ref :destination}
                                 {:op :require :predicate :destination}
                                 {:op :world-effect
                                  :effect-type :teleport-approved-target
                                  :target-ref :destination
                                  :mode :threatening}
                                 {:op :vfx :effect-id :threatening-teleport
                                  :event :release :params {:max-range 15.0}}]}}}
    ]})

(def ability-ids (set (map :id (:abilities provider))))
(defonce ^:private registered? (atom false))

(defn register!
  "Register AC's neutral provider exactly once before the registry freezes."
  [register-provider!]
  (when (compare-and-set! registered? false true)
    (register-provider! provider))
  :academy/base)

(defn reset-for-test! []
  (reset! registered? false)
  nil)
