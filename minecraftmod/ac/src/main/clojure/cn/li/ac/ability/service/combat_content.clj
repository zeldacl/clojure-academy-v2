(ns cn.li.ac.ability.service.combat-content
  "Neutral combat content registration owned by AC's composition root.

   This namespace contains only Clojure data/DSL. It does not reach a
   Minecraft object, packet, presentation host or VFX runtime.")

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
                        :event :release :params {:range 30.0}}]}}]})

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
