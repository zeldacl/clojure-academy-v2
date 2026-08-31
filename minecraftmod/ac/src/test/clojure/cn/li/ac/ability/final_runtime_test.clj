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

(deftest final-runtime-tick-executes-precompiled-due-node-test
  (let [rt (runtime/create-runtime
            {:host (host/create {:queries {} :actions {}})
             :state-provider (fn [_] {})
             :commit-state! (fn [_] nil)})]
    (runtime/initialize! rt)
    (reset! (:scheduled rt)
            (sorted-map 0 [{:tick 0
              :program {:schema-version 1
                       :program {:component :flow/sequence
                                 :kind :flow
                                 :steps [{:component :flow/finish :outcome :tick-fired}]}
                       :instructions 1}
              :frame {:owner "alice" :world "w" :ability-id :skill/a
                      :tick 0 :seed 1 :input {}}}]) )
    (let [result (runtime/tick! rt 0)]
      (is (= :accepted (:status result)))
      (is (= :tick-fired (get-in result [:results 0 :outcome])))
      (is (empty? @(:scheduled rt))))))
(deftest final-runtime-tick-no-due-work-fast-path-test
  (let [scheduled (atom (sorted-map 10 [{:tick 10}]))
        rt {:scheduled scheduled}]
    (is (= {:status :accepted :tick 0 :results []}
           (runtime/tick! rt 0)))
    (is (= {10 [{:tick 10}]} @scheduled))))
(deftest final-runtime-dispatch-enqueues-precompiled-scheduled-bucket-test
  (let [rt {:catalog (atom {:status :ready})
            :apis {:registration (fn [_] {:compiled :entry})
                   :execute (fn [_ _ _] {:status :accepted :scheduled [{:tick 7
                                                    :node {:component :flow/finish :outcome :later}}]})}
            :engine :engine
            :scheduled (atom (sorted-map))}
        frame {:owner "alice" :world "w" :tick 0 :seed 1 :input {}}]
    (is (= :accepted (:status (runtime/dispatch! rt :skill/a frame))))
    (let [bucket (get @(:scheduled rt) 7)
          entry (first bucket)]
      (is (= 1 (count bucket)))
      (is (= :flow/sequence (get-in entry [:program :program :component])))
      (is (not (contains? entry :node))))))

(deftest final-runtime-frame-world-prefers-activation-context-test
  (let [resolve-world (var-get (ns-resolve 'cn.li.ac.ability.final-runtime 'frame-world-id))]
    (is (= "world:nether" (resolve-world {:context {:world-id "world:nether"}})))
    (is (= "world:end" (resolve-world {:capabilities {:world/id "world:end"}})))
    (is (= "world:explicit"
           (resolve-world {:world-id "world:explicit"
                           :context {:world-id "world:nether"}})))
    (is (= "minecraft:overworld" (resolve-world {})))))

