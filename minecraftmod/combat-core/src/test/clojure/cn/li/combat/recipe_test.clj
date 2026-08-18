(ns cn.li.combat.recipe-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.combat.components :as components]
            [cn.li.combat.recipe :as recipe]
            [cn.li.combat.vm :as vm])
  (:import [cn.li.mcmod.runtime.effect CompiledProgram ExecutionFrame HostTable]
           [java.util ArrayList]))

(def railgun
  {:schema-version 1 :kind :ability :id :railgun :revision 2
   :activation :session :program
   {:component :flow/phases
    :start {:component :flow/finish :outcome :no-trigger}
    :pulse {:component :flow/finish :outcome :continue}
    :release {:component :flow/finish :outcome :cancelled}
    :abort {:component :flow/finish :outcome :aborted}}})

(deftest generic-ability-compiles
  (components/reset-for-test!)
  (let [compiled (recipe/compile-ability railgun)]
    (is (:compiled? compiled))
    (is (pos? (count (:compiled-ir compiled))))
    (is (instance? CompiledProgram (:compiled-program compiled)))
    (is (= :railgun (:id compiled)))))

(deftest unknown-components-are-rejected
  (components/reset-for-test!)
  (is (try
        (recipe/compile-ability
          (assoc railgun :program {:component :skill/railgun}))
        false
        (catch clojure.lang.ExceptionInfo _ true))))

(deftest composites-expand-before-runtime
  (components/reset-for-test!)
  (let [composites {:test/finish
                    {:kind :composite :id :test/finish :revision 1
                     :inputs {:outcome {:type :keyword}}
                     :body {:component :flow/finish
                            :outcome {:ref [:input :outcome]}}}}
        compiled (recipe/compile-ability
                  {:schema-version 1 :kind :ability :id :composite-test
                   :revision 1 :activation :instant
                   :program {:component :test/finish :outcome :ok}}
                  {:composites composites})]
    (is (= :flow/finish (get-in compiled [:program :component])))
    (is (= :ok (get-in compiled [:program :outcome])))
    (is (not-any? #(= :test/finish (get-in % [:data :component]))
                  (:compiled-ir compiled)))))

(deftest composite-cycle-and-missing-input-are-rejected
  (components/reset-for-test!)
  (let [cycle {:test/a {:kind :composite :id :test/a :inputs {}
                        :body {:component :test/b}}
               :test/b {:kind :composite :id :test/b :inputs {}
                        :body {:component :test/a}}}
        ability {:schema-version 1 :kind :ability :id :cycle-test
                 :revision 1 :activation :instant
                 :program {:component :test/a}}]
    (is (thrown? clojure.lang.ExceptionInfo
                 (recipe/compile-ability ability {:composites cycle})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (recipe/compile-ability
                  (assoc ability :program {:component :test/a :extra 1})
                  {:composites {:test/a {:kind :composite :id :test/a
                                         :inputs {:required {:type :object}}
                                         :body {:component :flow/finish
                                                :outcome :ok}}}})))))

(deftest primitive-vm-executes-array-ir
  (let [program (CompiledProgram.
                  (int-array [1 24])
                  (int-array [0 0 0 0 0 0 0 0 0 0 0 0])
                  (double-array [2.0 3.0])
                  (long-array 0)
                  (object-array 0)
                  (int-array 1)
                  1 0 0 0)
        frame (ExecutionFrame. (double-array 2) (long-array 0)
                               (boolean-array 0) (object-array 0)
                               (ArrayList.) (ArrayList.) (ArrayList.)
                               (int-array 0))
        host (HostTable. (object-array 0) (fn [_] nil) (fn [_ _] nil))
        result (vm/execute! program frame host 0)]
    (is (= :finished (:status result)))
    (is (= 2.0 (aget ^doubles (.-doubles frame) 0)))))

(deftest phases-execute-only-selected-child
  (components/reset-for-test!)
  (let [compiled (recipe/compile-ability railgun)
        program (:compiled-program compiled)
        host (HostTable. (object-array 0) (fn [_] true) (fn [_ _] true))
        run (fn [phase]
              (vm/execute! program
                           (ExecutionFrame. (double-array 0) (long-array 0)
                                             (boolean-array 0) (object-array 0)
                                             (ArrayList.) (ArrayList.) (ArrayList.)
                                             (int-array 0))
                           host 0 {:phase phase :context {}}))]
    (is (= :no-trigger (:outcome (run :start))))
    (is (= :continue (:outcome (run :pulse))))
    (is (= :cancelled (:outcome (run :release))))))

(deftest target-query-component-uses-neutral-host-capability
  (components/reset-for-test!)
  (let [seen (atom nil)
        ability (recipe/compile-ability
                  {:schema-version 1 :kind :ability :id :query-test
                   :revision 1 :activation :instant
                   :program {:component :flow/sequence
                             :steps [{:component :target/entities
                                      :shape {:type :sphere :radius 4.0}
                                      :projection [:id]
                                      :limit 8
                                      :result :targets}
                                     {:component :flow/finish :outcome :ok}]}})
        handler (fn [request _frame] (reset! seen request) {:count 2})
        host (HostTable. (object-array [handler]) (fn [_] true) (fn [_ _] true))
        frame (ExecutionFrame. (double-array 0) (long-array 0)
                               (boolean-array 0) (object-array 0)
                               (ArrayList.) (ArrayList.) (ArrayList.)
                               (int-array 0))
        result (vm/execute! (:compiled-program ability) frame host 0
                            {:phase :start
                             :context {:world-id "world"}
                             :query-order [:entity/select]
                             :results* (volatile! {})})]
    (is (= :finished (:status result)))
    (is (= :entity/select (:capability @seen)))
    (is (= "world" (:world-id @seen)))
    (is (= 8 (:limit @seen)))))

(deftest impact-composite-expands-to-generic-sequence
  (components/reset-for-test!)
  (let [composites {:combat/impact-strike
                    {:kind :composite :id :combat/impact-strike :revision 1
                     :inputs {:target {:type :object}
                              :amount {:type :object}
                              :damage-type {:type :keyword}
                              :on-impact {:type :node}}
                     :body {:component :flow/sequence
                            :steps [{:component :combat/damage
                                     :target {:ref [:input :target]}
                                     :amount {:ref [:input :amount]}
                                     :damage-type {:ref [:input :damage-type]}}
                                    {:ref [:input :on-impact]}]}}}
        ability (recipe/compile-ability
                  {:schema-version 1 :kind :ability :id :impact-test
                   :revision 1 :activation :instant
                   :program {:component :flow/sequence
                             :steps [{:component :combat/impact-strike
                                      :target "target-1"
                                      :amount 3.0
                                      :damage-type :skill
                                      :on-impact {:component :flow/branch
                                                  :when true
                                                  :then {:component :entity/status
                                                         :target "target-1"
                                                         :status-id :powered-creeper
                                                         :duration-ticks 1}}
                                      }
                                     {:component :flow/finish :outcome :ok}]}}
                  {:composites composites})
        frame (ExecutionFrame. (double-array 0) (long-array 0)
                               (boolean-array 0) (object-array 0)
                               (ArrayList.) (ArrayList.) (ArrayList.)
                               (int-array 0))
        result (vm/execute! (:compiled-program ability) frame
                            (HostTable. (object-array 0) (fn [_] true) (fn [_ _] true))
                            0 {:context {}})]
    (is (= :finished (:status result)))
    (is (= 2 (.size ^ArrayList (:actions result))))
    (is (= :entity/damage (:capability (.get ^ArrayList (:actions result) 0))))
    (is (= :entity/status (:capability (.get ^ArrayList (:actions result) 1))))))
