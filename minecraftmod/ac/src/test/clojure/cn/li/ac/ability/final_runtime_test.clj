(ns cn.li.ac.ability.final-runtime-test
  "Exercises cn.li.ability.engine (the ported final_runtime.clj) with AC's
   real catalog-compile, so this stays the integration point proving
   ability-runtime's engine and AC's real EDN abilities actually work
   together end to end -- the same job this file did before the P4 move,
   just against the new namespace and its now-explicit :catalog-compile."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ability.engine :as runtime]
            [cn.li.ac.ability.final-catalog-service :as final-catalog-service]
            [cn.li.combat.api :as combat-api]
            [cn.li.mcmod.runtime.host :as host]))

(defn- new-runtime []
  (runtime/create-runtime
   {:host (host/create {:queries {} :actions {}})
    :state-provider (fn [_] {})
    :commit-state! (fn [_] nil)
    :catalog-compile final-catalog-service/initialize!}))

(deftest final-runtime-rejects-before-catalog-initialization-test
  (let [rt (new-runtime)]
    (is (= {:status :rejected :reason :catalog-not-initialized}
           (runtime/dispatch! rt :railgun
                              {:owner "alice" :world "w" :tick 0 :seed 1 :input {}})))
    (is (= {:status :cold} (runtime/catalog-status rt)))))

(deftest final-runtime-exposes-fully-lowered-catalog-test
  (let [rt (new-runtime)]
    (runtime/initialize! rt)
    (is (= :ready (:status (runtime/catalog-status rt))))))

(deftest final-runtime-executes-migrated-passive-smoke-skill-test
  (let [rt (new-runtime)]
    (runtime/initialize! rt)
    (is (= :accepted
           (:status (runtime/dispatch! rt :electromaster/brain-course
                                       {:owner "alice" :world "w" :tick 0
                                       :seed 1 :input {:phase :start}}))))))

(deftest final-runtime-executes-course-family-smoke-test
  (let [rt (new-runtime)]
    (runtime/initialize! rt)
    (doseq [ability-id [:electromaster/mind-course
                        :electromaster/brain-course-advanced]]
      (is (= :accepted
             (:status (runtime/dispatch! rt ability-id
                                         {:owner "alice" :world "w" :tick 0
                                          :seed 1 :input {:phase :start}})))))))

(deftest final-runtime-tick-executes-precompiled-due-node-test
  (let [rt (new-runtime)]
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
  ;; Real combat-api execute (not a stubbed :apis map, which no longer
  ;; exists -- dispatch!/registration now read straight off the runtime's
  ;; own :catalog atom and call combat-api directly): :flow/after with
  ;; :delay 7 dispatched at :tick 0 schedules its :body for tick 7,
  ;; exercising the same enqueue-scheduled! bucketing/dissoc-:node behavior
  ;; this test always covered. :flow/after has no vocabulary descriptor (no
  ;; real EDN ability ever uses it directly -- it's only produced as a
  ;; runtime scheduling side-effect), so it cannot go through
  ;; combat-api/compile-program's node-kind lookup; hand-roll the
  ;; "compiled" shape instead, exactly as cn.li.ability.engine's own
  ;; scheduled-program helper already does for continuations.
  (let [compiled {:schema-version 1
                  :program {:component :flow/after :delay 7
                            :body {:component :flow/finish :outcome :later}}
                  :instructions 1}
        rt {:catalog (atom {:status :ready :combat {:by-id {:skill/a {:compiled compiled}}}})
            :engine (combat-api/create-engine
                     {:host (host/create {:queries {} :actions {}})
                      :state-provider (fn [_] {})
                      :commit-state! (fn [_] nil)})
            :scheduled (atom (sorted-map))}
        frame {:owner "alice" :world "w" :tick 0 :seed 1 :input {}}]
    (is (= :accepted (:status (runtime/dispatch! rt :skill/a frame))))
    (let [bucket (get @(:scheduled rt) 7)
          entry (first bucket)]
      (is (= 1 (count bucket)))
      (is (= :flow/sequence (get-in entry [:program :program :component])))
      (is (not (contains? entry :node))))))

(deftest final-runtime-frame-world-prefers-activation-context-test
  (let [resolve-world (var-get (ns-resolve 'cn.li.ability.engine 'frame-world-id))]
    (is (= "world:nether" (resolve-world {:context {:world-id "world:nether"}})))
    (is (= "world:end" (resolve-world {:capabilities {:world/id "world:end"}})))
    (is (= "world:explicit"
           (resolve-world {:world-id "world:explicit"
                           :context {:world-id "world:nether"}})))
    (is (= "minecraft:overworld" (resolve-world {})))))

