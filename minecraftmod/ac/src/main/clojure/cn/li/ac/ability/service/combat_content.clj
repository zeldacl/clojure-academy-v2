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
               :steps [{:op :query :query-type :raycast
                        :distance 24.0 :result-ref :destination}
                       {:op :require :predicate :destination}
                       {:op :world-effect :effect-type :teleport
                        :target-ref :destination}
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
