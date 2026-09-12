(ns cn.li.ac.ability.service.combat-runtime-dispatch-intent-v2-test
  "S8: end-to-end proof cn.li.ac.ability.service.combat-runtime/dispatch-
   intent-v2! actually dispatches a real ac/skills-v4/*.edn ability through
   the new engine, with the SAME pre-dispatch orchestration (cooldown
   pre-check, toggle close-edge, session open/close) dispatch-intent!
   already has for the old engine -- not a fake host, not a synthetic
   ability, the real vec-reflection.edn toggle ability against the real
   capability registry and player-state store."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.ability.model.preset :as preset-data]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.service.combat-runtime :as combat-runtime]
            [cn.li.ac.ability.service.combat-catalog :as combat-catalog]
            [cn.li.ac.ability.service.runtime-store :as runtime-store]
            [cn.li.ac.test.support.player-state :as player-state-support]
            [cn.li.ability.session :as combat-sessions]))

(use-fixtures :each
  (fn [f]
    (combat-catalog/initialize!)
    (player-state-support/clean-player-states-fixture
     (fn []
       ;; Register after the fixture installs a fresh Framework — the skill
       ;; registry lives there, and production (cn.li.ac.content.ability)
       ;; does the same projection from combat-catalog skill-specs.
       (skill-registry/reset-skill-registry-for-test!)
       (doseq [spec (combat-catalog/skill-specs)]
         (skill-registry/register-skill! spec))
       (runtime-store/create-session! player-state-support/test-session-id)
       (combat-sessions/reset-for-test!)
       (combat-runtime/reset-final-runtime-v2-for-test!)
       (try
         (f)
         (finally
           (combat-sessions/reset-for-test!)
           (combat-runtime/reset-final-runtime-v2-for-test!)))))))

(defn- flush-with-resources! [owner]
  ;; High CP; cur-overload may stay 0 — overload cost is heat ADDED on cast,
  ;; not a pool that must already be filled (see :cost/spend in combat-runtime).
  (runtime-store/get-or-create-player-state! player-state-support/test-session-id owner)
  (let [state (runtime-store/get-player-state player-state-support/test-session-id owner)]
    (runtime-store/set-player-state!
     player-state-support/test-session-id owner
     (update state :resource-data merge
             {:cur-cp 1.0e9 :cur-overload 0.0 :max-overload 1000.0
              :overload-fine true :activated true}))))

(deftest cooldown-pre-check-rejects-without-a-real-dispatch-test
  (let [owner "v2-cooldown-owner"]
    (flush-with-resources! owner)
    (runtime-store/set-player-state!
     player-state-support/test-session-id owner
     (assoc-in (runtime-store/get-player-state player-state-support/test-session-id owner)
               [:cooldown-data [:vec-reflection :main]] {:ticks 40 :max 40}))
    (let [result (combat-runtime/dispatch-intent-v2! owner {:op :start :ability-id :vec-reflection})]
      (is (= :rejected (:status result)))
      (is (= :cooldown (:reason result))))))

(deftest toggle-start-then-second-start-closes-via-abort-test
  (let [owner "v2-toggle-owner"]
    (flush-with-resources! owner)
    (testing "first :start succeeds, opens a session, real cost/spend + vfx"
      (let [result (combat-runtime/dispatch-intent-v2! owner {:op :start :ability-id :vec-reflection})]
        (is (= :accepted (:status result)))
        (is (= :started (:outcome result)))
        (is (= 1 (count (:vfx-signals result))))
        (is (= :ring-particle-field (:effect-id (first (:vfx-signals result)))))
        (is (= owner (:owner (first (:vfx-signals result)))))
        (is (some? (combat-sessions/session :ac owner)))
        (is (= :vec-reflection (:ability-id (combat-sessions/session :ac owner))))))
    (testing "a SECOND :start on the same owner/ability is the toggle's close edge -> :abort"
      (let [result (combat-runtime/dispatch-intent-v2! owner {:op :start :ability-id :vec-reflection})]
        (is (= :accepted (:status result)))
        (is (= :aborted (:outcome result)))
        (is (true? (:finish-ability? result)))
        (is (= :destroy (:op (first (:vfx-signals result))))
            "toggle abort must emit a destroy signal")
        (is (nil? (combat-sessions/session :ac owner))
            "session closed after the toggle's abort")))))

(deftest manual-release-reuses-active-session-activation-seed-test
  "A client key-up carries only the neutral release edge. The server must
   reuse the active session seed so its destroy signal addresses the same VFX
   instance that :start created."
  (let [owner "v2-manual-release-seed-owner"]
    (flush-with-resources! owner)
    (let [start (combat-runtime/dispatch-intent-v2!
                 owner {:op :start :ability-id :vec-reflection})
          release (combat-runtime/dispatch-intent-v2!
                   owner {:op :release :ability-id :vec-reflection})]
      (is (= :accepted (:status start)))
      (is (= :accepted (:status release)))
      (is (= :destroy (:op (first (:vfx-signals release)))))
      (is (= (:instance-key (first (:vfx-signals start)))
             (:instance-key (first (:vfx-signals release))))
          "manual release must destroy the start activation's VFX instance")
      (is (nil? (combat-sessions/session :ac owner))))))

(deftest insufficient-resources-rejects-without-opening-a-session-test
  (let [owner "v2-insufficient-owner"]
    (runtime-store/get-or-create-player-state! player-state-support/test-session-id owner)
    (let [result (combat-runtime/dispatch-intent-v2! owner {:op :start :ability-id :vec-reflection})]
      (is (= :accepted (:status result)))
      (is (= :insufficient-resource (:outcome result)))
      (is (true? (:finish-ability? result)))
      (is (nil? (combat-sessions/session :ac owner))))))

(deftest overload-cost-is-heat-not-pool-test
  "Fresh players have cur-overload 0. Overload budget must ADD heat, not
   require an already-filled overload pool (that false gate blocked casts)."
  (let [owner "v2-overload-heat-owner"]
    (flush-with-resources! owner)
    (let [before (get-in (runtime-store/get-player-state
                          player-state-support/test-session-id owner)
                         [:resource-data :cur-overload])
          ;; vec-reflection needs no world raycast host in this suite.
          result (combat-runtime/dispatch-intent-v2! owner {:op :start :ability-id :vec-reflection})
          after (get-in (runtime-store/get-player-state
                         player-state-support/test-session-id owner)
                        [:resource-data :cur-overload])]
      (is (= 0.0 (double before)))
      (is (= :accepted (:status result)))
      (is (= :started (:outcome result)))
      (is (pos? (double after))
          "successful cast should raise overload heat"))))

(deftest unknown-ability-reject-carries-feedback-test
  "engine-v2 returns :reason without :feedback; AC must attach feedback so
   the network path can push a visible reject to the client."
  (let [result (combat-runtime/dispatch-intent-v2!
                "v2-unknown-owner"
                {:op :start :ability-id :not-a-real-skill})]
    (is (= :rejected (:status result)))
    (is (= :unknown-ability (:reason result)))
    (is (= [{:type :combat-input-rejected :reason :unknown-ability}]
           (:feedback result)))))

(deftest slot-intent-resolves-skill-id-without-ability-id-test
  "Client LMB intents carry :slot only. resolve-slot returns a skill-id
   keyword; edn-ability-id must not treat that keyword as a map (:id)."
  (let [owner "v2-slot-owner"]
    (flush-with-resources! owner)
    (runtime-store/set-player-state!
     player-state-support/test-session-id owner
     (update (runtime-store/get-player-state player-state-support/test-session-id owner)
             :preset-data
             #(-> (or % (preset-data/new-preset-data))
                  (preset-data/set-slot 0 0 [:vecmanip :vec-reflection]))))
    (is (= :vec-reflection (combat-runtime/resolve-slot owner {:slot 0})))
    (is (= :vec-reflection (@#'combat-runtime/edn-ability-id owner {:op :start :slot 0})))
    (let [result (combat-runtime/dispatch-intent-v2! owner {:op :start :slot 0})]
      (is (= :vec-reflection (:ability-id result)))
      (is (not= :unknown-ability (:reason result)))
      (is (= :accepted (:status result)))
      (is (= :started (:outcome result))))))

(deftest instant-skill-entry-maps-op-start-via-activation-trigger-test
  "arc-gen (and other instant skills) name their entry :default with
   :on :activation/start. :op :start must resolve to that entry, not
   assume the entry is literally named :start."
  (with-redefs [cn.li.ac.ability.service.combat-runtime/entry-triggers-for
                (fn [ability-id]
                  (case ability-id
                    :arc-gen {:default :activation/start}
                    :vec-reflection {:start :phase/start}
                    nil))
                cn.li.ac.ability.service.combat-runtime/known-program-entries
                (fn [ability-id]
                  (case ability-id
                    :arc-gen #{:default}
                    :vec-reflection #{:start :pulse :release :abort}
                    nil))]
    (is (= :default (@#'combat-runtime/resolve-program-entry :arc-gen {:op :start})))
    (is (= :start (@#'combat-runtime/resolve-program-entry :vec-reflection {:op :start})))
    (is (nil? (@#'combat-runtime/resolve-program-entry :arc-gen {:op :release}))
        "instant skills have no release entry; must not fall back to :release")))

(deftest start-without-entry-triggers-does-not-guess-test
  "Missing V4 :entry-triggers must fail closed — never invent :default."
  (with-redefs [cn.li.ac.ability.service.combat-runtime/entry-triggers-for
                (constantly nil)
                cn.li.ac.ability.service.combat-runtime/known-program-entries
                (fn [ability-id]
                  (when (= ability-id :arc-gen) #{:default}))]
    (is (nil? (@#'combat-runtime/resolve-program-entry :arc-gen {:op :start})))))

(deftest real-arc-gen-entry-resolution-and-release-noop-test
  "Regression for in-game 'no such program entry': arc-gen's only entry is
   :default (:on :activation/start). Client key-up still sends :release;
   that must noop instead of dispatching a literal :release entry."
  (let [runtime (combat-runtime/initialize-final-runtime-v2!)
        triggers (get-in runtime [:catalog :by-id :arc-gen :ir :entry-triggers])
        known (set (keys (get-in runtime [:catalog :by-id :arc-gen :ir :entries])))
        owner "v2-arc-gen-release-owner"]
    (is (= {:default :activation/start} triggers))
    (is (= #{:default} known))
    (is (= :default (@#'combat-runtime/resolve-program-entry :arc-gen {:op :start})))
    (is (nil? (@#'combat-runtime/resolve-program-entry :arc-gen {:op :release})))
    (flush-with-resources! owner)
    (let [result (combat-runtime/dispatch-intent-v2! owner {:op :release :ability-id :arc-gen})]
      (is (= :accepted (:status result)))
      (is (= :noop (:outcome result))))))

(deftest real-arc-gen-slot-wheel-is-noop-without-entry-test
  "Wheel is not a cast/release. If a stray :slot-wheel reaches arc-gen
   (no such trigger), accept as noop — never :no-program-entry spam."
  (let [_ (combat-runtime/initialize-final-runtime-v2!)
        owner "v2-arc-gen-wheel-owner"]
    (is (nil? (@#'combat-runtime/resolve-program-entry
               :arc-gen {:op :event :event :slot-wheel})))
    (flush-with-resources! owner)
    (let [result (combat-runtime/dispatch-intent-v2!
                  owner {:op :event :event :slot-wheel :ability-id :arc-gen :slot 0})]
      (is (= :accepted (:status result)))
      (is (= :noop (:outcome result))))))

(deftest railgun-sessionless-release-after-auto-release-is-noop-test
  "Railgun may finish from the server pulse's automatic release before the
   physical mouse-up arrives. That stale key-up must not fire a second beam."
  (let [owner "v2-railgun-stale-release-owner"]
    (flush-with-resources! owner)
    (is (nil? (combat-sessions/session :ac owner :railgun)))
    (let [result (combat-runtime/dispatch-intent-v2!
                  owner {:op :release :ability-id :railgun})]
      (is (= :accepted (:status result)))
      (is (= :noop (:outcome result)))
      (is (nil? (combat-sessions/session :ac owner :railgun))))))
