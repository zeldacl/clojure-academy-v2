(ns cn.li.ac.content.ability.electromaster.railgun-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.runtime :as client-runtime]
            [cn.li.ac.content.ability.electromaster.railgun-fx :as railgun-fx]))

(defn- reset-fixture [f]
  (try
        (level-effects/reset-level-effect-registry-for-test!)
        (railgun-fx/reset-fx-for-test!)
        (f)
        (finally
          (railgun-fx/reset-fx-for-test!)
          (level-effects/reset-level-effect-registry-for-test!))))

(use-fixtures :each reset-fixture)

(defn- event [ctx-id channel payload]
  {:payload payload
   :ctx-id ctx-id
   :channel channel
   :owner-key [:ctx ctx-id]})

(deftest init-registers-owner-aware-railgun-fx-test
  (let [registered-level* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (railgun-fx/init!)
      (is (= :railgun-shot (first @registered-level*)))
      (is (fn? (:enqueue-state-fn (second @registered-level*))))
      (is (= #{:railgun/fx-shot :railgun/fx-reflect
               :railgun/fx-charge-start :railgun/fx-charge-update :railgun/fx-charge-end}
             @registered-topics*)))))

(deftest fx-handler-routes-with-ctx-metadata-test
  (let [handlers* (atom {})
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic handler]
                                                      (swap! handlers* assoc topic handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id ctx-id channel payload & opts]
                                                        (swap! enqueued* conj [effect-id ctx-id channel payload opts])
                                                        nil)]
      (railgun-fx/init!)
      ((get @handlers* :railgun/fx-shot) "ctx-rail" :railgun/fx-shot {:mode :block-hit
                                               :start {:x 0.0 :y 64.0 :z 0.0}
                                               :end {:x 8.0 :y 64.0 :z 0.0}
                                               :world-id "minecraft:overworld"})
      (is (= [[:railgun-shot {:mode :block-hit
                              :owner-key [:ctx "ctx-rail"]
                              :ctx-id "ctx-rail"
                              :channel :railgun/fx-shot
                              :start {:x 0.0 :y 64.0 :z 0.0}
                              :end {:x 8.0 :y 64.0 :z 0.0}
                              :world-id "minecraft:overworld"}
               {:ctx-id "ctx-rail"
                :channel :railgun/fx-shot
                :owner-key [:ctx "ctx-rail"]}]]
             @enqueued*)))))

(deftest enqueue-perform-adds-beam-and-builds-plan-test
  (do
    (arc-beam/enqueue-for-test! :railgun-shot "ctx-main" :railgun/fx-shot {:start {:x 0.0 :y 64.0 :z 0.0}
                                           :end {:x 3.0 :y 64.0 :z 3.0}
                                           :hit-distance 18.0})
    (let [plan (arc-beam/effect-build-plan :railgun-shot {:x 0.0 :y 65.0 :z 0.0} nil 0)]
      (is (some? plan))
      (is (seq (:ops plan))))
    (is (= 1 (count (get (:beam-effects (railgun-fx/fx-snapshot)) [:ctx "ctx-main"]))))))

(deftest two-owners-keep-railgun-beams-independent-test
  (do
    (arc-beam/enqueue-for-test! :railgun-shot "ctx-a" :railgun/fx-shot {:start {:x 0.0 :y 64.0 :z 0.0}
                                         :end {:x 6.0 :y 64.0 :z 0.0}})
    (arc-beam/enqueue-for-test! :railgun-shot "ctx-b" :railgun/fx-reflect {:start {:x 0.0 :y 65.0 :z 0.0}
                                           :end {:x 6.0 :y 65.0 :z 0.0}})
    (let [snapshot (railgun-fx/fx-snapshot)]
      (is (= 1 (count (get (:beam-effects snapshot) [:ctx "ctx-a"]))))
      (is (= 1 (count (get (:beam-effects snapshot) [:ctx "ctx-b"]))))
      (railgun-fx/clear-fx-owner! [:ctx "ctx-a"])
      (let [after-clear (railgun-fx/fx-snapshot)]
        (is (nil? (get (:beam-effects after-clear) [:ctx "ctx-a"])))
        (is (= 1 (count (get (:beam-effects after-clear) [:ctx "ctx-b"]))))))))

(deftest fx-snapshot-default-without-registered-state-test
  (is (= {:beam-effects {} :charging {}}
         (railgun-fx/fx-snapshot))))

(deftest charging-lifecycle-populates-and-clears-the-idle-gating-marker-test
  ;; :charging is a pure idle-gating marker (see impl/railgun_shot.clj):
  ;; before this fix, no level-effect state existed during the charge-only
  ;; window (no beam yet), so level-effects' idle-gating suppressed build-plan
  ;; entirely and the charge-hand animation never rendered until a beam
  ;; existed. default-empty-state? only treats :railgun-shot as idle once
  ;; BOTH :beam-effects and :charging are empty.
  (do
    (is (empty? (:charging (railgun-fx/fx-snapshot))))
    (arc-beam/enqueue-for-test! :railgun-shot "ctx-charge" :railgun/fx-charge-start {:mode :charge-start})
    (is (contains? (:charging (railgun-fx/fx-snapshot)) [:ctx "ctx-charge"]))

    (arc-beam/enqueue-for-test! :railgun-shot "ctx-charge" :railgun/fx-charge-update {:mode :charge-update :charge-ticks-left 5})
    (is (contains? (:charging (railgun-fx/fx-snapshot)) [:ctx "ctx-charge"]))

    (arc-beam/enqueue-for-test! :railgun-shot "ctx-charge" :railgun/fx-charge-end {:mode :charge-end})
    (is (not (contains? (:charging (railgun-fx/fx-snapshot)) [:ctx "ctx-charge"])))
    (is (empty? (:charging (railgun-fx/fx-snapshot))))))

(deftest charge-hand-visual-renders-once-charging-marker-is-live-test
  (with-redefs [client-runtime/railgun-charge-visual-state
                (fn [_player-uuid _now-ms]
                  {:active? true :charge-start-ms 0 :charge-ratio 0.5 :coin-active? false})]
    (let [hand {:x 1.0 :y 65.0 :z 1.0 :player-uuid "p1"}]
      (arc-beam/enqueue-for-test! :railgun-shot "ctx-charge" :railgun/fx-charge-start {:mode :charge-start})
      (let [plan (arc-beam/effect-build-plan :railgun-shot {:x 0.0 :y 65.0 :z 0.0} hand 0)]
        (is (seq (:ops plan)))
        (is (every? #(= :quad (:kind %)) (:ops plan))
            "no beam exists yet — the only ops should be the charge-hand quad")))))

(deftest charging-marker-expires-via-ttl-without-explicit-end-test
  (do
    (arc-beam/enqueue-for-test! :railgun-shot "ctx-stale" :railgun/fx-charge-start {:mode :charge-start})
    (is (contains? (:charging (railgun-fx/fx-snapshot)) [:ctx "ctx-stale"]))
    (dotimes [_ 8]
      (level-effects/update-effect-state! :railgun-shot
        (fn [store] (arc-beam/effect-tick-state! :level :railgun-shot store))))
    (is (not (contains? (:charging (railgun-fx/fx-snapshot)) [:ctx "ctx-stale"])))))
