(ns cn.li.ability.engine-v2-test
  "S8: end-to-end proof cn.li.ability.engine-v2 actually dispatches a real
   compiled program against the SAME shared capability registry real
   content uses (not a bespoke fake host), and translates the result into
   the shape cn.li.ac.ability.service.combat-runtime's dispatch-intent!/
   finalize-result! already know how to consume from the old engine."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ability.engine-v2 :as engine-v2]
            [cn.li.combat.api :as combat-api]
            [cn.li.mcmod.runtime.capabilities :as capabilities]))

(use-fixtures :each (fn [f] (capabilities/reset-for-test!) (f) (capabilities/reset-for-test!)))

(def ^:private test-ability-text
  "{:ability :engine-v2-test-ability
    :activation :toggle
    :state {:hits {:type :long :default 0}}
    :tunables {:range {:type :double} :damage {:type :double}}
    :phases
    {:start
     [(let hit (target/raycast {:origin ?caster/eye :direction ?caster/aim
                                :distance $range :include-entities? true}))
      (when (:entity-id hit)
        (combat/damage {:target (:entity-id hit) :amount $damage}))
      (let sufficient? (cost/spend {:budget ?budget/activate}))
      (let deactivate-cooldown ?cooldown/deactivate)
      (cooldown/start {:name :deactivate :ticks deactivate-cooldown})
      (state! :hits 1)
      (vfx! {:effect-id :test-fx :operation :spawn :instance-key [:activation :marker]
            :audience {:type :nearby :radius 32.0} :payload {:position ?caster/eye}})
      (event! {:type :score/mark :tag :hit :progression 0.5})
      (finish {:outcome :started})]
     :release
     [(finish {:outcome :released :end-ability? true})]}}")

(defn- fake-catalog-compile []
  (let [ir (combat-api/compile-skill-doc! test-ability-text)]
    {:sources {:engine-v2-test-ability {:id :engine-v2-test-ability :program test-ability-text}}
     :registrations [{:id :engine-v2-test-ability :source-id :engine-v2-test-ability
                      :bindings {} :ir ir}]
     :by-id {}
     :source-count 1 :registration-count 1}))

(defn- register-fakes! [calls]
  (capabilities/register-query!
   :raycast (fn [args _frame] (swap! calls conj [:query :raycast args]) {:entity-id "target-1"})
   {:allow-overwrite? true})
  (capabilities/register-query!
   :cost/spend (fn [args _frame] (swap! calls conj [:query :cost/spend args]) true)
   {:allow-overwrite? true})
  (capabilities/register-action!
   :entity/damage (fn [args] (swap! calls conj [:action :entity/damage args]) {:status :applied})
   {:allow-overwrite? true})
  (capabilities/register-action!
   :cooldown/start (fn [args] (swap! calls conj [:action :cooldown/start args]) nil)
   {:allow-overwrite? true}))

(defn- new-runtime! [& {:keys [ability-state-patches removed?]
                        :or {ability-state-patches (atom nil) removed? (atom false)}}]
  (engine-v2/create-runtime
   {:catalog-compile fake-catalog-compile
    :commit-ability-state! (fn [_owner patches] (reset! ability-state-patches patches))
    :remove-ability-state! (fn [_owner] (reset! removed? true))}))

(deftest create-runtime-compiles-the-catalog-against-the-shared-registry-test
  (register-fakes! (atom []))
  (let [runtime (new-runtime!)]
    (is (some? (engine-v2/registration runtime :engine-v2-test-ability)))
    (is (= {:status :ready :source-count 1 :registration-count 1}
           (engine-v2/catalog-status runtime)))))

(deftest dispatch-injects-owner-world-id-ability-id-into-registered-handler-args-test
  (let [calls (atom [])]
    (register-fakes! calls)
    (let [runtime (new-runtime!)
          input {:tunables {:range 20.0 :damage 4.0}
                 :capabilities {:caster/eye {:x 0.0 :y 1.5 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                                :caster/id "player-1" :world/id "overworld"
                                :budget/activate {:cp 3.0} :cooldown/deactivate 40}}
          result (engine-v2/dispatch! runtime :engine-v2-test-ability
                                      {:owner "player-1" :entry :start :input input})]
      (testing "the raycast query got :owner/:world-id injected (not declared by the DSL node itself)"
        (is (some #(= [:query :raycast {:origin {:x 0.0 :y 1.5 :z 0.0} :direction {:x 0.0 :y 0.0 :z 1.0}
                                        :distance 20.0 :include-entities? true
                                        :owner "player-1" :world-id "overworld"}]
                      %)
                  @calls)))
      (testing "the damage action got :owner/:world-id/:ability-id injected"
        (is (some #(= [:action :entity/damage {:target "target-1" :amount 4.0
                                               :owner "player-1" :world-id "overworld"
                                               :ability-id :engine-v2-test-ability}]
                      %)
                  @calls)))
      (testing "the cooldown/start action also got the injected fields"
        (is (some #(= [:action :cooldown/start {:name :deactivate :ticks 40
                                                :owner "player-1" :world-id "overworld"
                                                :ability-id :engine-v2-test-ability}]
                      %)
                  @calls)))
      (is (= :accepted (:status result)))
      (is (= :started (:outcome result)))
      (is (false? (:finish-ability? result))))))

(deftest dispatch-translates-state-writes-vfx-and-events-test
  (let [calls (atom [])
        patches (atom nil)]
    (register-fakes! calls)
    (let [runtime (new-runtime! :ability-state-patches patches)
          input {:tunables {:range 20.0 :damage 4.0}
                 :capabilities {:caster/eye {:x 0.0 :y 1.5 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                                :caster/id "player-1" :world/id "overworld"
                                :budget/activate {:cp 3.0} :cooldown/deactivate 40}}
          result (engine-v2/dispatch! runtime :engine-v2-test-ability
                                      {:owner "player-1" :entry :start :input input})]
      (testing "session-state writes translate into commit-ability-state!'s own patch shape"
        (is (= [{:path [:hits] :mode :assign :value 1}] (:ability-state-patches result)))
        (is (= [{:path [:hits] :mode :assign :value 1}] @patches))
        (is (some? @patches) "commit-ability-state! callback actually ran"))
      (testing "vfx signals are normalized to the old engine's own wire shape"
        (is (= 1 (count (:vfx-signals result))))
        (let [signal (first (:vfx-signals result))]
          (is (= :spawn (:op signal)))
          (is (= :test-fx (:effect-id signal)))
          (is (= "player-1" (:owner signal)))
          (is (= "overworld" (:world-id signal)))
          (is (= [:activation :marker] (:instance-key signal)))
          (is (= {:position {:x 0.0 :y 1.5 :z 0.0}} (:params signal)))
          (is (number? (:event-seq signal)))))
      (testing "events carry owner/ability-id, matching handle-progression-event!'s own contract"
        (is (= 1 (count (:events result))))
        (let [event (first (:events result))]
          (is (= :score/mark (:type event)))
          (is (= 0.5 (:progression event)))
          (is (= "player-1" (:owner event)))
          (is (= :engine-v2-test-ability (:ability-id event))))))))

(deftest dispatch-calls-remove-ability-state-only-when-finish-ability-is-true-test
  (let [calls (atom [])
        removed? (atom false)]
    (register-fakes! calls)
    (let [runtime (new-runtime! :removed? removed?)
          input {:tunables {} :capabilities {}}]
      (engine-v2/dispatch! runtime :engine-v2-test-ability {:owner "player-1" :entry :release :input input})
      (is (true? @removed?)))))

(deftest dispatch-rejects-an-unknown-ability-test
  (register-fakes! (atom []))
  (let [runtime (new-runtime!)
        result (engine-v2/dispatch! runtime :not-a-real-ability {:owner "player-1" :entry :start :input {}})]
    (is (= :rejected (:status result)))
    (is (= :unknown-ability (:reason result)))))

(deftest tick-is-always-a-no-op-since-no-content-uses-scheduled-continuations-test
  (register-fakes! (atom []))
  (let [runtime (new-runtime!)]
    (is (= {:status :accepted :tick 42 :results []} (engine-v2/tick! runtime 42)))))

(deftest install-production-and-dispatch-production-round-trip-test
  (register-fakes! (atom []))
  (let [runtime (new-runtime!)]
    (engine-v2/install-production! (:options runtime))
    (is (identical? (engine-v2/production-runtime) (engine-v2/production-runtime)))
    (let [result (engine-v2/dispatch-production!
                  "player-1" :engine-v2-test-ability
                  {:entry :release :input {:tunables {} :capabilities {}}})]
      (is (= :accepted (:status result)))
      (is (= :released (:outcome result))))))
