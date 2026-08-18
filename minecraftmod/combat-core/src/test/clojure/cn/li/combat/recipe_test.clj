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
