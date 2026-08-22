(ns cn.li.ac.ability.service.combat-runtime-vfx-audience-test
  "Regression coverage for P2 (Psi-style network): a combat result's VFX
   signals route to the self-only channel or the tracking broadcast channel
   based on their declared :audience (falling back to the VFX effect
   document's own default), and :session/:persistent instances get their
   last :spawn signal replayed on the periodic tick sweep so a player who
   enters tracking range after an effect started still sees it -- while
   :transient effects never replay, and a cleared owner stops replaying."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.service.combat-catalog :as catalog]
            [cn.li.ac.ability.service.combat-runtime :as combat-runtime]))

(defn- with-fake-sinks [f]
  (let [self-calls (atom [])
        broadcast-calls (atom [])]
    (combat-runtime/install-vfx-self-sink!
     (fn [owner signal] (swap! self-calls conj [owner signal])))
    (combat-runtime/install-vfx-broadcast-sink!
     (fn [owner signal radius] (swap! broadcast-calls conj [owner signal radius])))
    (f self-calls broadcast-calls)))

(deftest self-audience-signal-routes-to-self-sink-only-test
  (catalog/initialize!)
  (combat-runtime/reset-for-test!)
  (with-fake-sinks
    (fn [self-calls broadcast-calls]
      (combat-runtime/publish-combat-result!
       {:owner "owner-1" :status :accepted
        :vfx-signals [{:op :spawn :effect-id :screen-flash-session
                        :instance-key [:flash 1] :event-seq 1
                        :audience {:type :owner}}]})
      (is (= 1 (count @self-calls)))
      (is (= 0 (count @broadcast-calls))))))

(deftest nearby-audience-signal-routes-to-broadcast-sink-with-declared-radius-test
  (catalog/initialize!)
  (combat-runtime/reset-for-test!)
  (with-fake-sinks
    (fn [self-calls broadcast-calls]
      (combat-runtime/publish-combat-result!
       {:owner "owner-1" :status :accepted
        :vfx-signals [{:op :spawn :effect-id :beam-session
                        :instance-key [:beam 1] :event-seq 1
                        :audience {:type :nearby :radius 32.0}}]})
      (is (= 0 (count @self-calls)))
      (is (= 1 (count @broadcast-calls)))
      (is (= 32.0 (nth (first @broadcast-calls) 2))))))

(deftest undeclared-audience-falls-back-to-effect-catalog-default-test
  (catalog/initialize!)
  (combat-runtime/reset-for-test!)
  (with-fake-sinks
    (fn [_self-calls broadcast-calls]
      (combat-runtime/publish-combat-result!
       {:owner "owner-1" :status :accepted
        :vfx-signals [{:op :spawn :effect-id :beam-session
                        :instance-key [:beam 2] :event-seq 1}]})
      (is (= 1 (count @broadcast-calls)))
      (is (= 96.0 (nth (first @broadcast-calls) 2))))))

(deftest session-effect-spawn-replays-on-periodic-tick-sweep-test
  (catalog/initialize!)
  (combat-runtime/reset-for-test!)
  (with-fake-sinks
    (fn [_self-calls broadcast-calls]
      (combat-runtime/publish-combat-result!
       {:owner "owner-1" :status :accepted
        :vfx-signals [{:op :spawn :effect-id :beam-session
                        :instance-key [:beam 3] :event-seq 1
                        :audience {:type :nearby :radius 32.0}}]})
      (is (= 1 (count @broadcast-calls)))
      (combat-runtime/tick! 40)
      (is (= 2 (count @broadcast-calls))
          "a :session effect's last :spawn signal is resent on the 40-tick sweep")
      (is (= [:beam 3] (get-in (last @broadcast-calls) [1 :instance-key]))))))

(deftest transient-effect-spawn-does-not-replay-test
  (catalog/initialize!)
  (combat-runtime/reset-for-test!)
  (with-fake-sinks
    (fn [_self-calls broadcast-calls]
      (combat-runtime/publish-combat-result!
       {:owner "owner-1" :status :accepted
        :vfx-signals [{:op :spawn :effect-id :terrain-shockwave-transient
                        :instance-key [:shockwave 1] :event-seq 1
                        :audience {:type :nearby :radius 32.0}}]})
      (is (= 1 (count @broadcast-calls)))
      (combat-runtime/tick! 40)
      (is (= 1 (count @broadcast-calls))
          "a :transient effect is one-shot and never replayed on the sweep"))))

(deftest broadcast-clear-owner-forgets-tracked-signals-so-replay-stops-test
  (catalog/initialize!)
  (combat-runtime/reset-for-test!)
  (with-fake-sinks
    (fn [_self-calls broadcast-calls]
      (combat-runtime/publish-combat-result!
       {:owner "owner-1" :status :accepted
        :vfx-signals [{:op :spawn :effect-id :beam-session
                        :instance-key [:beam 4] :event-seq 1
                        :audience {:type :nearby :radius 32.0}}]})
      (combat-runtime/broadcast-clear-owner! "owner-1")
      (reset! broadcast-calls [])
      (combat-runtime/tick! 80)
      (is (= 0 (count @broadcast-calls))
          "clearing an owner forgets its tracked persistent signals"))))

(deftest destroy-signal-forgets-tracked-instance-so-replay-stops-test
  (catalog/initialize!)
  (combat-runtime/reset-for-test!)
  (with-fake-sinks
    (fn [_self-calls broadcast-calls]
      (combat-runtime/publish-combat-result!
       {:owner "owner-1" :status :accepted
        :vfx-signals [{:op :spawn :effect-id :beam-session
                        :instance-key [:beam 5] :event-seq 1
                        :audience {:type :nearby :radius 32.0}}]})
      (combat-runtime/publish-combat-result!
       {:owner "owner-1" :status :accepted
        :vfx-signals [{:op :destroy :effect-id :beam-session
                        :instance-key [:beam 5] :event-seq 2
                        :audience {:type :nearby :radius 32.0}}]})
      (reset! broadcast-calls [])
      (combat-runtime/tick! 120)
      (is (= 0 (count @broadcast-calls))
          "a :destroy signal for an instance-key removes it from replay tracking"))))
