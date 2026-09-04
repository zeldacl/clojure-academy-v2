(ns cn.li.ac.ability.service.combat-runtime-vanilla-damage-reflection-test
  "End-to-end coverage for the weakest-covered link in the damage-
   interception boundary: a real vanilla hit (the platform reports only
   `[player-id attacker-id damage damage-source]`, exactly like a mob or
   another player's melee attack) reaching AC's
   process-damage-request!/apply-attack-precheck! and, through them,
   Combat Core's declarative reaction pipeline (cn.li.combat.interception),
   triggering a reflect reaction, landing the reflected hit through the
   registered :entity/damage capability, AND getting its cost committed
   into the real per-player store -- the full path, not a mocked slice of
   it.

   combat-core's own interception-test.clj already covers the reaction
   arithmetic/capability-routing in isolation with injected fakes; what is
   NOT covered anywhere else is that AC's real composition wiring
   (combat-catalog, runtime-store, commit-edn-owner-patches!,
   combat-sessions) actually reaches that pipeline and actually persists
   what it returns. A synthetic reflect ability is merged into the real
   compiled catalog (rather than depending on vec_reflection.edn's exact
   tunable curves, which would make this test's outcome depend on
   skill-config numbers this test does not own) so the reflect arithmetic
   itself stays deterministic while everything else -- catalog lookup,
   state read/write, patch commit -- is the real production path."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.framework.platform :as platform]
            [cn.li.ac.ability.service.combat-catalog :as combat-catalog]
            [cn.li.ac.ability.service.combat-runtime :as combat-runtime]
            [cn.li.ac.ability.service.runtime-store :as runtime-store]
            [cn.li.ac.test.support.player-state :as player-state-support]))

;; A raw catalog source's :damage-policies entry (the shape every real
;; ac/skills/*.edn source carries directly, e.g. vec_reflection.edn's own
;; :damage-policies -- see final-damage-policies-v2's own docstring in
;; combat_runtime.clj) rather than the old catalog's pre-lowering
;; :reactions wrapper, which nothing compiles into :damage-policies any
;; more now that the old engine's assembly pipeline is gone.
;; :when gates on having CP available, the same declarative-precondition
;; pattern real content uses (e.g. vec_reflection.edn's own :when checks
;; :context :enabled?) -- :damage/reflect itself has no built-in
;; affordability gate (unlike :damage/absorb's :requires-payment, its
;; :cost-per-damage is charged best-effort after the fact, see
;; final_damage.clj's reflection-costs), so a reaction that must not fire
;; without resources needs to say so explicitly.
(def ^:private reflect-source
  {:damage-policies [{:on :combat/damage
                       :priority 1
                       :when {:expr :math/gt
                              :args [{:ref [:input :context :resources :cp]} 0.0]}
                       :program {:component :damage/reflect
                                 :multiplier 0.5
                                 :cost-per-damage 0.1
                                 :minimum 0.1
                                 :max-depth 1
                                 :cost-resource :cp
                                 :progression-scale 0.0}}]})

(use-fixtures :each
  (fn [f]
    (combat-catalog/initialize!)
    ;; final-runtime-v2* is a JVM-lifetime singleton, normally warmed
    ;; lazily by final-damage-request's own guard on its first call --
    ;; explicit here so this fixture doesn't depend on some unrelated,
    ;; alphabetically-earlier test namespace happening to have warmed the
    ;; singleton first.
    (combat-runtime/install-ac-host-capabilities!)
    (combat-runtime/initialize-final-runtime-v2!)
    (player-state-support/clean-player-states-fixture
     (fn []
       (combat-runtime/reset-for-test!)
       (try
         (f)
         (finally
           (combat-runtime/reset-for-test!)))))))

(defn- with-fake-entity-damage-handler
  "apply-reflections-once! (combat_runtime.clj) calls a hardcoded damage-fn
   bound once at bootstrap directly to cn.li.combat.platform/damage!, which
   in turn calls cn.li.mcmod.platform.entity-damage/apply-direct-damage! --
   a separate platform SPI table ([:platform :entity-damage] in the
   Framework atom), deliberately resolved outside the generic capabilities
   registry for performance (install-runtime-adapters!'s own docstring).
   Faking cn.li.mcmod.runtime.capabilities' :entity/damage action (as this
   used to) can never be observed by that call. Install a fake directly
   into the real SPI instead, matching the shape a real platform loader's
   entity-damage/install-entity-damage! installs (see mcbase's
   create-entity-damage :apply-direct-damage! for the positional-args
   contract this mirrors)."
  [f]
  (let [fw-atom (fw/fw-atom)
        previous (platform/get-adapter fw-atom :entity-damage)
        seen (atom [])]
    (try
      (platform/install-adapter!
       fw-atom :entity-damage
       {:apply-direct-damage!
        (fn [world-id entity-uuid damage source-type opts]
          (swap! seen conj {:world-id world-id :target entity-uuid :amount damage
                             :damage-type source-type :owner (:attacker-uuid opts)})
          true)})
      (f seen)
      (finally
        (platform/install-adapter! fw-atom :entity-damage previous)))))

(defn- with-synthetic-reflect-ability
  "Replace the whole new-engine catalog's :sources table with just the
   synthetic reflect source -- not merged alongside the real catalog.
   final-damage-policies-v2/damage-policy-inputs (combat_runtime.clj) read
   damage policies from (:sources (:catalog (final-runtime-v2))), not from
   combat-catalog/catalog -- that function is a separate, EDN-metadata-only
   projection (skill tree UI, item triggers, passive effects) with no
   bearing on damage dispatch since the old engine's assembly pipeline
   (which used to lower :reactions into :damage-policies at load time) was
   deleted. Redefining final-runtime-v2 here, not combat-catalog/catalog,
   is what actually reaches the reflect pipeline. intercept-damage!
   evaluates every catalog ability's :tunables-fn/:when for every incoming
   hit regardless of which one ultimately matches, so mixing in the real
   EDN abilities here would make this test's outcome depend on their
   skill-config tunable curves too, exactly the coupling the synthetic
   source exists to avoid."
  [f]
  (let [real-runtime (combat-runtime/final-runtime-v2)
        isolated (assoc-in real-runtime [:catalog :sources] {:reflect-passive reflect-source})]
    (with-redefs [combat-runtime/final-runtime-v2 (fn [] isolated)]
      (f))))

(defn- seed-cp!
  "cmd-consume-resource's can-perform? gate requires :activated true (an
   ordinary player's resource pool won't spend CP while deactivated), so a
   test that wants a real commit to actually land needs it set, not just a
   non-zero :cur-cp."
  [player-id cp]
  (player-state-support/seed-player-state!
   player-id
   (-> (runtime-store/fresh-player-state)
       (assoc-in [:resource-data :cur-cp] cp)
       (assoc-in [:resource-data :activated] true))))

(deftest vanilla-hit-triggers-reflect-reaction-lands-through-capability-and-commits-cost-test
  (with-synthetic-reflect-ability
    (fn []
      (with-fake-entity-damage-handler
        (fn [seen]
          (seed-cp! "target-player" 100.0)
          (let [residual (combat-runtime/process-damage-request!
                          "target-player" "attacker-mob" 10.0
                          {:attacker-front? true :damage-type :mob})]
            (is (= 5.0 residual)
                "the native hit must land only the un-reflected remainder")
            (is (= 1 (count @seen))
                "the reflected half must land through the registered :entity/damage capability")
            (is (= {:target "attacker-mob" :amount 5.0
                    :damage-type :mob :owner "target-player"}
                   (dissoc (first @seen) :world-id)))
            ;; resolve-event's own reflection-costs formula (final_damage.clj)
            ;; is amount-before-reflect * ratio * cost-per-damage = 10.0 *
            ;; 0.5 * 0.1 = 0.5 -- cost-per-damage is charged per point of
            ;; the REFLECTED damage, not the original incoming damage.
            (is (= 99.5
                   (get-in (runtime-store/get-player-state
                            player-state-support/test-session-id "target-player")
                           [:resource-data :cur-cp]))
                "the reflect's cost-per-damage must be committed into the real player-state store")))))))

(deftest vanilla-hit-precheck-cancels-native-damage-and-still-lands-the-reflect-test
  ;; Regression: intercept!/apply-attack-precheck! used to gate the reflect's
  ;; :entity/damage capability call (and its cost commit) on `(not
  ;; cancelled?)`, but a precheck-mode reflect sets :cancelled? true exactly
  ;; WHEN it has already produced a replacement -- so that guard silently
  ;; discarded the reflected damage on every precheck-cancelled hit, the
  ;; platform's own attack-stage gate (see damage_interception_core.clj's
  ;; apply-attack-result!/allow-attack?): a cancelled attack never reaches
  ;; process-damage-request! afterwards, so this was the ONLY call that could
  ;; ever have applied that reflected damage.
  (with-synthetic-reflect-ability
    (fn []
      (with-fake-entity-damage-handler
        (fn [seen]
          (seed-cp! "target-player-2" 100.0)
          (let [must-cancel? (combat-runtime/apply-attack-precheck!
                              "target-player-2" "attacker-mob-2" 10.0
                              {:attacker-front? true :damage-type :mob})]
            (is (true? must-cancel?)
                "the platform must not also apply the native hit once reaction damage landed")
            (is (= 1 (count @seen))
                (str "the reflected hit must land through the capability during the SAME "
                     "precheck call -- process-damage-request! never runs afterwards for "
                     "an attack the precheck cancelled"))
            ;; resolve-event's own reflection-costs formula (final_damage.clj)
            ;; is amount-before-reflect * ratio * cost-per-damage = 10.0 *
            ;; 0.5 * 0.1 = 0.5 -- cost-per-damage is charged per point of
            ;; the REFLECTED damage, not the original incoming damage.
            (is (= 99.5
                   (get-in (runtime-store/get-player-state
                            player-state-support/test-session-id "target-player-2")
                           [:resource-data :cur-cp]))
                "the reflect's cost-per-damage must be committed even though the request was cancelled")))))))

(deftest vanilla-hit-with-no-eligible-reaction-passes-through-unmodified-test
  (with-synthetic-reflect-ability
    (fn []
      (with-fake-entity-damage-handler
        (fn [seen]
          ;; No CP at all: reflect-source's own :when (:context :resources
          ;; :cp > 0.0) fails to match, so the reaction must not fire and
          ;; the vanilla hit must land exactly as reported -- no phantom
          ;; damage transformation for a player with no eligible reactions.
          (seed-cp! "target-player-3" 0.0)
          (let [residual (combat-runtime/process-damage-request!
                          "target-player-3" "attacker-mob-3" 10.0
                          {:attacker-front? true :damage-type :mob})]
            (is (= 10.0 residual))
            (is (empty? @seen))))))))

