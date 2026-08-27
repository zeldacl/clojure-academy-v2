(ns cn.li.ac.ability.final-runtime-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.final-runtime :as runtime]
            [cn.li.mcmod.runtime.host :as host]))

(deftest final-runtime-rejects-before-catalog-initialization-test
  (let [rt (runtime/create-runtime
            {:host (host/create {:queries {} :actions {}})
             :state-provider (fn [_] {})
             :commit-state! (fn [_] nil)})]
    (is (= {:status :rejected :reason :catalog-not-initialized}
           (runtime/dispatch! rt :railgun
                              {:owner "alice" :world "w" :tick 0 :seed 1 :input {}})))
    (is (= {:status :cold} (runtime/catalog-status rt)))))

(deftest final-runtime-exposes-fully-lowered-catalog-test
  (let [rt (runtime/create-runtime
            {:host (host/create {:queries {} :actions {}})
             :state-provider (fn [_] {})
             :commit-state! (fn [_] nil)})]
    (runtime/initialize! rt)
    (is (= :ready (:status (runtime/catalog-status rt))))))

(deftest final-runtime-executes-migrated-passive-smoke-skill-test
  (let [rt (runtime/create-runtime
            {:host (host/create {:queries {} :actions {}})
             :state-provider (fn [_] {})
             :commit-state! (fn [_] nil)})]
    (runtime/initialize! rt)
    (is (= :accepted
           (:status (runtime/dispatch! rt :electromaster/brain-course
                                       {:owner "alice" :world "w" :tick 0
                                       :seed 1 :input {:phase :start}}))))))

(deftest final-runtime-executes-course-family-smoke-test
  (let [rt (runtime/create-runtime
            {:host (host/create {:queries {} :actions {}})
             :state-provider (fn [_] {})
             :commit-state! (fn [_] nil)})]
    (runtime/initialize! rt)
    (doseq [ability-id [:electromaster/mind-course
                        :electromaster/brain-course-advanced]]
      (is (= :accepted
             (:status (runtime/dispatch! rt ability-id
                                         {:owner "alice" :world "w" :tick 0
                                          :seed 1 :input {:phase :start}})))))))

(deftest final-runtime-frame-world-prefers-activation-context-test
  (let [resolve-world (var-get (ns-resolve 'cn.li.ac.ability.final-runtime 'frame-world-id))]
    (is (= "world:nether" (resolve-world {:context {:world-id "world:nether"}})))
    (is (= "world:end" (resolve-world {:capabilities {:world/id "world:end"}})))
    (is (= "world:explicit"
           (resolve-world {:world-id "world:explicit"
                           :context {:world-id "world:nether"}})))
    (is (= "minecraft:overworld" (resolve-world {})))))
