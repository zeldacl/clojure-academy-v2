(ns cn.li.ability.engine-test
  "Unit coverage for cn.li.ability.engine against a fake :catalog-compile
   (a two-line stub, not AC's real catalog) -- ac's final_runtime_test.clj
   is the integration-level coverage that proves this same engine works
   against AC's real EDN abilities end to end."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ability.engine :as engine]
            [cn.li.combat.api :as combat-api]
            [cn.li.node.environment :as node-environment]
            [cn.li.mcmod.runtime.host :as host]))

(def ^:private environment
  (node-environment/build {:descriptors (combat-api/descriptor-specs)}))

;; :flow/after has no vocabulary descriptor (it is never used directly by
;; any real EDN ability -- only produced as a runtime scheduling
;; side-effect), so it cannot go through combat-api/compile-program's
;; node-kind lookup. Hand-roll the "compiled" shape directly instead,
;; exactly as cn.li.ability.engine's own scheduled-program helper already
;; does for continuations -- execute! only reads :program off this map, it
;; doesn't care whether compile-program produced it.
(defn- raw-compiled [program]
  {:schema-version 1 :program program :instructions 1})

(defn- fake-catalog-compile []
  {:status :ready
   :content-hash "test-hash"
   :node-environment environment
   :combat {:sources {}
            :by-id {:test/finish {:compiled (raw-compiled {:component :flow/finish :outcome :done})}
                    :test/after {:compiled (raw-compiled {:component :flow/after :delay 3
                                                           :body {:component :flow/finish :outcome :later}})}}}
   :vfx {:effects {}}})

(defn- new-runtime []
  (engine/create-runtime
   {:host (host/create {:queries {} :actions {}})
    :state-provider (fn [_] {})
    :commit-state! (fn [_] nil)
    :catalog-compile fake-catalog-compile}))

(deftest dispatch-before-initialize-is-rejected-test
  (let [rt (new-runtime)]
    (is (= {:status :rejected :reason :catalog-not-initialized}
           (engine/dispatch! rt :test/finish {:owner "a" :world "w" :tick 0 :seed 1 :input {}})))
    (is (= {:status :cold} (engine/catalog-status rt)))))

(deftest initialize-then-dispatch-known-ability-accepts-test
  (let [rt (engine/initialize! (new-runtime))]
    (is (= :ready (:status (engine/catalog-status rt))))
    (is (= :accepted
           (:status (engine/dispatch! rt :test/finish {:owner "a" :world "w" :tick 0 :seed 1 :input {}}))))))

(deftest dispatch-unknown-ability-is-rejected-test
  (let [rt (engine/initialize! (new-runtime))]
    (is (= {:status :rejected :reason :unknown-ability :ability-id :test/missing}
           (engine/dispatch! rt :test/missing {:owner "a" :world "w" :tick 0 :seed 1 :input {}})))))

(deftest scheduled-node-runs-on-its-due-tick-test
  (let [rt (engine/initialize! (new-runtime))]
    (engine/dispatch! rt :test/after {:owner "a" :world "w" :tick 0 :seed 1 :input {}})
    (let [premature (engine/tick! rt 2)
          due (engine/tick! rt 3)]
      (is (empty? (:results premature)))
      (is (= :later (get-in due [:results 0 :outcome]))))))

(deftest abort-owner-drops-only-that-owners-scheduled-work-test
  (let [rt (engine/initialize! (new-runtime))]
    (engine/dispatch! rt :test/after {:owner "a" :world "w" :tick 0 :seed 1 :input {}})
    (engine/dispatch! rt :test/after {:owner "b" :world "w" :tick 0 :seed 1 :input {}})
    (engine/abort-owner! rt "a")
    (let [due (engine/tick! rt 3)]
      ;; tick! results carry no :owner field (matches the ported
      ;; final_runtime.clj -- only dispatch!'s immediate result does), so
      ;; the count is what proves abort-owner! dropped only "a"'s entry:
      ;; if it hadn't, both "a" and "b" would still be due at tick 3.
      (is (= 1 (count (:results due))))
      (is (= :later (get-in due [:results 0 :outcome]))))))

(deftest install-production-then-dispatch-production-round-trips-test
  (let [runtime (engine/install-production!
                 {:state-provider (fn [_] {})
                  :commit-state! (fn [_] nil)
                  :catalog-compile fake-catalog-compile})]
    (is (identical? runtime (engine/production-runtime)))
    (is (= :accepted
           (:status (engine/dispatch-production! "a" :test/finish {}))))))
