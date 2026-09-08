(ns cn.li.ac.ability.service.combat-runtime-dispatch-intent-v2-test
  "S8: end-to-end proof cn.li.ac.ability.service.combat-runtime/dispatch-
   intent-v2! actually dispatches a real ac/skills-v3/*.edn ability through
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
  ;; :max-overload 1000.0 (not the smoke test's own default-100.0 pattern):
  ;; vec-reflection's real :activation-overload tunable (mastery-lerp,
  ;; untrained skill-exp=0 in a fresh test player) resolves to 350.0 --
  ;; a real config value discovered by actually running this test, not
  ;; guessed -- so the smoke test's own 100.0 default is insufficient
  ;; here specifically.
  (runtime-store/get-or-create-player-state! player-state-support/test-session-id owner)
  (let [state (runtime-store/get-player-state player-state-support/test-session-id owner)]
    (runtime-store/set-player-state!
     player-state-support/test-session-id owner
     (update state :resource-data merge
             {:cur-cp 1.0e9 :cur-overload 1000.0 :max-overload 1000.0
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
        (is (nil? (combat-sessions/session :ac owner))
            "session closed after the toggle's abort")))))

(deftest insufficient-resources-rejects-without-opening-a-session-test
  (let [owner "v2-insufficient-owner"]
    (runtime-store/get-or-create-player-state! player-state-support/test-session-id owner)
    (let [result (combat-runtime/dispatch-intent-v2! owner {:op :start :ability-id :vec-reflection})]
      (is (= :accepted (:status result)))
      (is (= :insufficient-resource (:outcome result)))
      (is (true? (:finish-ability? result)))
      (is (nil? (combat-sessions/session :ac owner))))))

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
                    nil))]
    (is (= :default (@#'combat-runtime/resolve-program-entry :arc-gen {:op :start})))
    (is (= :start (@#'combat-runtime/resolve-program-entry :vec-reflection {:op :start})))))
