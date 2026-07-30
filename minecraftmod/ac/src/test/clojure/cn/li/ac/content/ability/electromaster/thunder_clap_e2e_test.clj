(ns cn.li.ac.content.ability.electromaster.thunder-clap-e2e-test
  "Whole server-side lifecycle for thunder-clap, driven through the real
  per-tick pipeline (context-manager/tick-player-contexts!).

  Regression guard: a client-side context for the same player/skill lives in
  the same dispatcher registry in single player (integrated server), so any
  charge lookup that scans all contexts instead of the dispatched ctx-id can
  read the client copy — whose :skill-state is never projected — see 0 held
  ticks, and silently skip the lightning strike."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.effects.world :as world-op]
            [cn.li.ac.ability.model.ability :as ad]
            [cn.li.ac.ability.model.resource :as rd]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-manager :as cm]
            [cn.li.ac.ability.service.context-state :as ctx-rt]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.content.ability.electromaster.thunder-clap :as tc]
            [cn.li.ac.test.support.contexts :as test-contexts]
            [cn.li.ac.test.support.player-state :as test-player]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(def ^:private player-id "e2e-player")
(def ^:private ctx-id "e2e-ctx")

;; Enough headroom that neither the 390 down-stage overload nor the ~40x25 CP
;; charge aborts the context — this test is about the charge/strike wiring.
(def ^:private max-cp 24000.0)
(def ^:private max-overload 4000.0)

(defn- server-owner []
  {:logical-side :server
   :server-session-id test-player/test-session-id
   :player-uuid player-id})

(defn- client-owner []
  {:logical-side :client
   :client-session-id :test-client-session
   :player-uuid player-id})

(defn- reset-fixture [f]
  (test-contexts/clean-contexts-fixture
   #(test-player/clean-player-states-fixture
     (fn []
       (skill-registry/install-skill-registry-runtime!
        (skill-registry/create-skill-registry-runtime))
       (evt/install-event-subscriber-runtime!
        (evt/create-event-subscriber-runtime))
       (cm/register-send-fns! {:to-client nil :to-server nil})
       (skill-registry/register-skill! tc/thunder-clap)
       (f)))))

(use-fixtures :each reset-fixture)

(defn- seed-player! []
  (store/set-player-state!
   test-player/test-session-id player-id
   {:ability-data (-> (ad/new-ability-data)
                      (ad/learn-skill :thunder-clap))
    :resource-data (assoc (rd/new-resource-data max-cp max-overload)
                          :activated true)}))

(defn- seed-alive-contexts!
  "Register the server context the pipeline drives plus the client-side twin
  that single player always keeps alongside it."
  []
  (ctx/register-context!
   (assoc (ctx/new-server-context player-id :thunder-clap ctx-id (server-owner))
          :status ctx/STATUS-ALIVE))
  (ctx/with-context-owner (client-owner)
    (ctx/register-context!
     (assoc (ctx/new-context player-id :thunder-clap (client-owner))
            :id ctx-id
            :status ctx/STATUS-ALIVE)))
  (command-rt/run-command-in-session!
   test-player/test-session-id player-id
   {:command :register-context :ctx-id ctx-id
    :skill-id :thunder-clap :status :alive}))

(defn- server-tick! []
  (runtime-hooks/with-client-ctx-fn
    {:player-owner {:server-session-id test-player/test-session-id
                    :player-uuid player-id}}
    (fn [] (cm/tick-player-contexts! player-id))))

(defn- current-cp []
  (get-in (store/get-player-state test-player/test-session-id player-id)
          [:resource-data :cur-cp]))

(deftest server-tick-charge-reaches-min-and-strikes-lightning-test
  (let [strikes* (atom [])
        cooldowns* (atom [])]
    (runtime-hooks/with-player-state-owner-fn
      {:server-session-id test-player/test-session-id :player-uuid player-id}
      (fn []
        (seed-player!)
        (with-redefs [world-op/execute-spawn-lightning!
                      (fn [evt params] (swap! strikes* conj [(:hit-pos evt) params]) evt)
                      tc/execute-thunder-clap-aoe! (fn [& _] 0)
                      skill-effects/set-main-cooldown!
                      (fn [& args] (swap! cooldowns* conj (vec args)))]
          (ctx/with-context-owner (server-owner)
            (seed-alive-contexts!)
            (ctx-rt/handle-key-down! (server-owner) ctx-id {} nil)
            (dotimes [_ 45] (server-tick!))

            (is (= 45 (get-in (ctx-skill/get-context ctx-id) [:skill-state :hold-ticks]))
                "every server tick advances the charge")
            ;; exp 0 => cost.tick.cp lerp endpoint 18.0, charged only while the
            ;; tick being produced is still within min-ticks (40). The cost
            ;; callbacks have no ctx-id, so this also pins the by-player charge
            ;; lookup to the server context.
            (is (= (- max-cp (* 40 18.0)) (current-cp))
                "CP is paid for the first min-ticks ticks and free afterwards")

            (ctx-rt/handle-key-up! (server-owner) ctx-id {} nil)
            (is (= 1 (count @strikes*))
                "a release past min-ticks always spawns the lightning bolt")
            (is (= {:at :hit-pos :visual-only? true} (second (first @strikes*))))
            (is (true? (get-in (ctx-skill/get-context ctx-id) [:skill-state :performed?])))
            (is (= 1 (count @cooldowns*)))))))))

(deftest release-below-min-charge-strikes-nothing-test
  (let [strikes* (atom [])]
    (runtime-hooks/with-player-state-owner-fn
      {:server-session-id test-player/test-session-id :player-uuid player-id}
      (fn []
        (seed-player!)
        (with-redefs [world-op/execute-spawn-lightning!
                      (fn [evt _params] (swap! strikes* conj evt) evt)
                      tc/execute-thunder-clap-aoe! (fn [& _] 0)]
          (ctx/with-context-owner (server-owner)
            (seed-alive-contexts!)
            (ctx-rt/handle-key-down! (server-owner) ctx-id {} nil)
            (dotimes [_ 10] (server-tick!))
            (ctx-rt/handle-key-up! (server-owner) ctx-id {} nil)
            (is (empty? @strikes*))
            (is (false? (get-in (ctx-skill/get-context ctx-id)
                                [:skill-state :performed?])))))))))

(deftest auto-strike-at-max-charge-test
  (let [strikes* (atom [])]
    (runtime-hooks/with-player-state-owner-fn
      {:server-session-id test-player/test-session-id :player-uuid player-id}
      (fn []
        (seed-player!)
        (with-redefs [world-op/execute-spawn-lightning!
                      (fn [evt _params] (swap! strikes* conj evt) evt)
                      tc/execute-thunder-clap-aoe! (fn [& _] 0)
                      skill-effects/set-main-cooldown! (fn [& _] nil)]
          (ctx/with-context-owner (server-owner)
            (seed-alive-contexts!)
            (ctx-rt/handle-key-down! (server-owner) ctx-id {} nil)
            ;; Upstream fires MSG_END to itself the tick the charge hits
            ;; MAX_TICKS, even while the key is still held.
            (dotimes [_ 80] (server-tick!))
            (is (= 1 (count @strikes*))
                "max charge auto-releases exactly one strike")))))))
