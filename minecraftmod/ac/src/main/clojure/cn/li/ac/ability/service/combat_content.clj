(ns cn.li.ac.ability.service.combat-content
  "Neutral combat content registration owned by AC's composition root.

   This namespace contains only Clojure data/DSL. It does not reach a
   Minecraft object, packet, presentation host or VFX runtime."
  (:require [clojure.set :as set]
            [cn.li.ac.ability.skill-config :as skill-config]))

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
   [{:id :dim-folding-theorem
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
    ;; Not a player teleport -- place/drop the held item at a raycasted
    ;; point, then damage whatever intersects the line from caster to that
    ;; point. Own :shift-teleport query-type/effect-type; previously
    ;; borrowed :teleport-target/:teleport-approved-target with :mode :shift,
    ;; which never matched either op's real shape (see
    ;; docs/04-systems/COMBAT_VFX_PLATFORM_GAPS.md A section). Also added the
    ;; :overload cost that skill_config/teleporter.clj's recovered schema
    ;; declares (cost.up.overload [40.0 30.0]) but this content never had.
    {:id :shift-teleport
     :revision 1
     :activation :instant
     :cost {:cp (scale 260.0 320.0)
            :overload (scale 40.0 30.0)}
     :cooldown {:ticks (scale 100.0 60.0)}
     :program {:op :sequence
               :steps [{:op :query :query-type :shift-teleport
                        :max-range (scale 25.0 35.0)
                        :result-ref :destination}
                       {:op :require :predicate :destination}
                       {:op :world-effect
                        :effect-type :shift-teleport
                        :query-ref :destination
                        :damage (scale 15.0 35.0)}
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
                                  :mode :threatening
                                  :damage (scale 3.0 6.0)}
                                 {:op :vfx :effect-id :threatening-teleport
                                 :event :release :params {:max-range 15.0}}]}}}
    ]})

(def ability-ids (set (map :id (:abilities provider))))

(defn skill-specs
  "Return the player-facing registry metadata derived from the Combat Core
   catalog.  Skill behavior is never stored in these specs; the catalog is the
   sole executable source."
  []
  (mapv (fn [{:keys [id activation]}]
          (let [{:keys [category-id level controllable?]} 
                (get skill-config/skill-definitions-by-id id)]
            {:id id
             :category-id category-id
             :level level
             :controllable? controllable?
             :name-key (str "ability.skill." (name category-id) "." (name id))
             :description-key (str "ability.skill." (name category-id) "." (name id) ".desc")
             :icon (str "textures/abilities/" (name category-id) "/skills/" (name id) ".png")
             :ctrl-id id
             :pattern activation
             :cooldown {:mode :combat-core}
             ;; Tells the legacy Context fail-closed guard (context_state.clj)
             ;; that this skill has no legacy execution path to fall back to.
             :execution :combat-core}))
        (:abilities provider)))

(declare vfx-effect-ids)

(defn assert-complete-skill-catalog!
  "Fail closed if the executable catalog and player skill configuration diverge."
  []
  (let [configured (set skill-config/all-skill-ids)
        catalog ability-ids]
    (when-not (= configured catalog)
      (throw (ex-info "Combat Core catalog does not cover configured skills"
                      {:missing (sort (set/difference configured catalog))
                       :unexpected (sort (set/difference catalog configured))})))
    true))

(defn assert-complete-composition!
  "Validate every player-facing capability used by AC has a Combat Core
   recipe before the legacy namespace bootstrap can be removed."
  []
  (assert-complete-skill-catalog!)
  (when-not (every? keyword? vfx-effect-ids)
    (throw (ex-info "Combat Core contains an invalid VFX capability" {})))
  ;; These are the old AC bootstrap responsibilities that must be empty or
  ;; represented by Combat Core before the namespace scanner is bypassed.
  (when-not (= #{:instant :session :toggle :passive}
               (set (map :activation (:abilities provider))))
    (throw (ex-info "Combat Core contains an unsupported activation model" {})))
  true)

(defn- collect-vfx-effect-ids
  [value]
  (cond
    (map? value)
    (into #{} (mapcat (fn [[k v]]
                        (if (= k :effect-id) [v] (collect-vfx-effect-ids v))) value))
    (sequential? value)
    (into #{} (mapcat collect-vfx-effect-ids value))
    :else #{}))

(def vfx-effect-ids (collect-vfx-effect-ids (:abilities provider)))
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
