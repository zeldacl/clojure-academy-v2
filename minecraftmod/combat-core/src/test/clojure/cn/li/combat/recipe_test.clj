(ns cn.li.combat.recipe-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.combat.components :as components]
            [cn.li.combat.recipe :as recipe]
            [cn.li.combat.vm :as vm]
            [cn.li.mcmod.runtime.safe-edn :as safe-edn])
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

(def ^:private per-item-composites
  {:test/per-item
   {:kind :composite :id :test/per-item :revision 1
    :iterates {:item {:as :internal-var :fields [:id :position]}}
    :inputs {:amount {:type :expr-per-item :port :item}}
    :body {:component :flow/foreach
           :items [{:id 1 :position 10.0} {:id 2 :position 20.0}]
           :as :internal-var
           :limit 2
           :body {:component :domain/event :event-type :hit
                  :payload {:amount {:ref [:input :amount]}}}}}})

(deftest per-item-input-resolves-a-distinct-value-for-each-iteration
  (testing (str "defect #5's actual fix: the caller writes {:ref [:item "
                ":position]}, never learning the composite's real internal "
                ":as name (:internal-var), and still gets a fresh value "
                "each iteration")
    (components/reset-for-test!)
    (let [ability (recipe/compile-ability
                    {:schema-version 1 :kind :ability :id :per-item-test
                     :revision 1 :activation :instant
                     :program {:component :flow/sequence
                               :steps [{:component :test/per-item
                                        :amount {:ref [:item :position]}}
                                       {:component :flow/finish :outcome :ok}]}}
                    {:composites per-item-composites})
          frame (ExecutionFrame. (double-array 0) (long-array 0)
                                 (boolean-array 0) (object-array 0)
                                 (ArrayList.) (ArrayList.) (ArrayList.)
                                 (int-array 0))
          result (vm/execute! (:compiled-program ability) frame
                              (HostTable. (object-array 0) (fn [_] true) (fn [_ _] true))
                              0 {:context {} :slots* (volatile! {})})]
      (is (= :finished (:status result)))
      (is (= [10.0 20.0] (mapv #(get-in % [:payload :amount]) (:events result)))))))

(deftest per-item-ref-on-a-non-expr-per-item-input-is-rejected
  (components/reset-for-test!)
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"not :type :expr-per-item"
       (recipe/compile-ability
         {:schema-version 1 :kind :ability :id :per-item-misuse-test
          :revision 1 :activation :instant
          :program {:component :test/finish :outcome {:ref [:item :position]}}}
         {:composites {:test/finish
                       {:kind :composite :id :test/finish :revision 1
                        :inputs {:outcome {:type :keyword}}
                        :body {:component :flow/finish
                               :outcome {:ref [:input :outcome]}}}}}))))

(deftest per-item-ref-outside-any-composite-invocation-is-rejected
  (components/reset-for-test!)
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"may only appear as the value"
       (recipe/compile-ability
         {:schema-version 1 :kind :ability :id :per-item-residual-test
          :revision 1 :activation :instant
          :program {:component :flow/finish :outcome {:ref [:item :position]}}}))))

(deftest per-item-field-not-published-by-iterates-is-rejected
  (components/reset-for-test!)
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"not published by the composite's :iterates entry"
       (recipe/compile-ability
         {:schema-version 1 :kind :ability :id :per-item-field-test
          :revision 1 :activation :instant
          :program {:component :test/per-item :amount {:ref [:item :not-a-real-field]}}}
         {:composites per-item-composites}))))

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

(deftest foreach-skip-item-only-skips-that-item
  (components/reset-for-test!)
  (let [ability (recipe/compile-ability
                  {:schema-version 1 :kind :ability :id :skip-item-test
                   :revision 1 :activation :instant
                   :program
                   {:component :flow/sequence
                    :steps [{:component :flow/foreach
                             :items [1 2 3] :as :item :limit 3
                             :body {:component :flow/branch
                                    :when {:expr :math/eq
                                           :args [{:ref [:slot :item]} 2.0]}
                                    :then {:component :flow/control :signal :skip-item}
                                    :else {:component :domain/event :event-type :processed
                                           :payload {:item {:ref [:slot :item]}}}}}
                            {:component :flow/finish :outcome :done}]}})
        frame (ExecutionFrame. (double-array 0) (long-array 0)
                               (boolean-array 0) (object-array 0)
                               (ArrayList.) (ArrayList.) (ArrayList.)
                               (int-array 0))
        result (vm/execute! (:compiled-program ability) frame
                            (HostTable. (object-array 0) (fn [_] true) (fn [_ _] true))
                            0 {:context {} :slots* (volatile! {})})]
    (is (= :finished (:status result)))
    (is (= :done (:outcome result))
        "the loop's own :skip-item must not be mistaken for a program finish")
    (is (= [1 3] (mapv #(get-in % [:payload :item]) (:events result))))))

(deftest foreach-break-loop-stops-iteration-but-not-the-program
  (components/reset-for-test!)
  (let [ability (recipe/compile-ability
                  {:schema-version 1 :kind :ability :id :break-loop-test
                   :revision 1 :activation :instant
                   :program
                   {:component :flow/sequence
                    :steps [{:component :flow/foreach
                             :items [1 2 3 4] :as :item :limit 4
                             :body {:component :flow/branch
                                    :when {:expr :math/eq
                                           :args [{:ref [:slot :item]} 3.0]}
                                    :then {:component :flow/control :signal :break-loop}
                                    :else {:component :domain/event :event-type :processed
                                           :payload {:item {:ref [:slot :item]}}}}}
                            {:component :flow/finish :outcome :done}]}})
        frame (ExecutionFrame. (double-array 0) (long-array 0)
                               (boolean-array 0) (object-array 0)
                               (ArrayList.) (ArrayList.) (ArrayList.)
                               (int-array 0))
        result (vm/execute! (:compiled-program ability) frame
                            (HostTable. (object-array 0) (fn [_] true) (fn [_ _] true))
                            0 {:context {} :slots* (volatile! {})})]
    (is (= :finished (:status result)))
    (is (= :done (:outcome result))
        "the step after the foreach must still run once the loop breaks")
    (is (= [1 2] (mapv #(get-in % [:payload :item]) (:events result)))
        "item 4 must never be reached once item 3 breaks the loop")))

(deftest txn-atomic-without-on-success-propagates-body-result
  (components/reset-for-test!)
  (let [ability (recipe/compile-ability
                  {:schema-version 1 :kind :ability :id :txn-body-result-test
                   :revision 1 :activation :instant
                   :program
                   {:component :txn/atomic
                    :guards [] :reservations []
                    :body {:component :flow/sequence
                           :steps [{:component :domain/event :event-type :ran :payload {}}
                                   {:component :flow/finish :outcome :done
                                    :finish-session? true}]}}})
        frame (ExecutionFrame. (double-array 0) (long-array 0)
                               (boolean-array 0) (object-array 0)
                               (ArrayList.) (ArrayList.) (ArrayList.)
                               (int-array 0))
        result (vm/execute! (:compiled-program ability) frame
                            (HostTable. (object-array 0) (fn [_] true) (fn [_ _] true))
                            0 {:context {}})]
    (is (= :finished (:status result))
        "without :on-success, the transaction must not silently discard its :body's finish")
    (is (= :done (:outcome result)))
    (is (true? (:finish-session? result)))))

(deftest vfx-instance-keys-are-namespaced-by-ability-id
  (testing "cross-ability instance-key collisions become structurally impossible"
    (components/reset-for-test!)
    (let [compiled (recipe/compile-ability
                     {:schema-version 1 :kind :ability :id :namespacing-test
                      :revision 1 :activation :instant
                      :program {:component :flow/sequence
                                :steps [{:component :effect/vfx
                                         :effect-id :fx :operation :spawn
                                         :instance-key [:activation :shared-name]
                                         :payload {}}
                                        {:component :flow/finish :outcome :ok}]}})]
      (is (= [:namespacing-test :activation :shared-name]
             (get-in compiled [:program :steps 0 :instance-key]))))))

(deftest from-scope-resolves-caster-facade-values
  (components/reset-for-test!)
  (let [ability (recipe/compile-ability
                  {:schema-version 1 :kind :ability :id :from-scope-test
                   :revision 1 :activation :instant
                   :program {:component :flow/sequence
                             :steps [{:component :data/bind :to :eye
                                      :value {:from :caster/eye}}
                                     {:component :flow/finish :outcome :ok}]}})
        frame (ExecutionFrame. (double-array 0) (long-array 0)
                               (boolean-array 0) (object-array 0)
                               (ArrayList.) (ArrayList.) (ArrayList.)
                               (int-array 0))
        result (vm/execute! (:compiled-program ability) frame
                            (HostTable. (object-array 0) (fn [_] true) (fn [_ _] true))
                            0 {:context {} :from {:caster/eye {:x 1.0 :y 2.0 :z 3.0}}
                               :slots* (volatile! {})})]
    (is (= :finished (:status result)))))

(deftest from-scope-rejects-undeclared-facade-key
  (components/reset-for-test!)
  (let [ability (recipe/compile-ability
                  {:schema-version 1 :kind :ability :id :from-scope-reject-test
                   :revision 1 :activation :instant
                   :program {:component :flow/sequence
                             :steps [{:component :data/bind :to :x
                                      :value {:from :caster/hand-item}}
                                     {:component :flow/finish :outcome :ok}]}})
        frame (ExecutionFrame. (double-array 0) (long-array 0)
                               (boolean-array 0) (object-array 0)
                               (ArrayList.) (ArrayList.) (ArrayList.)
                               (int-array 0))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"caster facade does not provide"
         (vm/execute! (:compiled-program ability) frame
                      (HostTable. (object-array 0) (fn [_] true) (fn [_ _] true))
                      0 {:context {} :from {} :slots* (volatile! {})})))))

(deftest tunable-scope-resolves-materialized-values
  (components/reset-for-test!)
  (let [ability (recipe/compile-ability
                  {:schema-version 1 :kind :ability :id :tunable-scope-test
                   :revision 1 :activation :instant
                   :program {:component :flow/sequence
                             :steps [{:component :combat/damage
                                      :target "t" :amount {:tunable :damage}}
                                     {:component :flow/finish :outcome :ok}]}})
        frame (ExecutionFrame. (double-array 0) (long-array 0)
                               (boolean-array 0) (object-array 0)
                               (ArrayList.) (ArrayList.) (ArrayList.)
                               (int-array 0))
        result (vm/execute! (:compiled-program ability) frame
                            (HostTable. (object-array 0) (fn [_] true) (fn [_ _] true))
                            0 {:context {} :tunables {:damage 7.5}})]
    (is (= :finished (:status result)))
    (is (= 7.5 (:amount (.get ^ArrayList (:actions result) 0))))))

(deftest unknown-ref-scope-fails-closed
  (components/reset-for-test!)
  (let [ability (recipe/compile-ability
                  {:schema-version 1 :kind :ability :id :unknown-scope-test
                   :revision 1 :activation :instant
                   :program {:component :data/bind :to :x
                             :value {:ref [:not-a-real-scope :key]}}})
        frame (ExecutionFrame. (double-array 0) (long-array 0)
                               (boolean-array 0) (object-array 0)
                               (ArrayList.) (ArrayList.) (ArrayList.)
                               (int-array 0))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"unknown :ref scope"
         (vm/execute! (:compiled-program ability) frame
                      (HostTable. (object-array 0) (fn [_] true) (fn [_ _] true))
                      0 {:context {} :slots* (volatile! {})})))))

(deftest cost-spend-emits-owner-patch-for-every-resource-when-affordable
  (testing "schema v2 design A: :cost/spend reads a named budget from the ability's own :costs
            block and emits the SAME neutral :owner/patch shape ability programs used to build
            by hand -- no new AC-side commit machinery needed"
    (components/reset-for-test!)
    (let [ability (recipe/compile-ability
                    {:schema-version 1 :kind :ability :id :cost-spend-test
                     :revision 1 :activation :instant
                     :program {:component :flow/sequence
                               :steps [{:component :cost/spend :budget :fire}
                                       {:component :flow/finish :outcome :ok}]}})
          frame (ExecutionFrame. (double-array 0) (long-array 0)
                                 (boolean-array 0) (object-array 0)
                                 (ArrayList.) (ArrayList.) (ArrayList.)
                                 (int-array 0))
          result (vm/execute! (:compiled-program ability) frame
                              (HostTable. (object-array 0) (fn [_] true) (fn [_ _] true))
                              0 {:context {:resources {:cp 100.0 :overload 50.0}}
                                 :costs {:fire {:resources {:cp {:tunable :cost-cp}
                                                            :overload {:tunable :cost-overload}}}}
                                 :tunables {:cost-cp 5.0 :cost-overload 3.0}})
          entries (:entries (.get ^ArrayList (:actions result) 0))]
      (is (= :finished (:status result)))
      (is (some #(= {:path [:resources :cp] :mode :increment :value -5.0} %) entries))
      (is (some #(= {:path [:resources :overload] :mode :increment :value -3.0} %) entries)))))

(deftest cost-spend-runs-on-insufficient-when-unaffordable
  (components/reset-for-test!)
  (let [ability (recipe/compile-ability
                  {:schema-version 1 :kind :ability :id :cost-spend-insufficient-test
                   :revision 1 :activation :instant
                   :program {:component :flow/sequence
                             :steps [{:component :cost/spend :budget :fire
                                      :on-insufficient {:component :flow/finish :outcome :broke}}
                                     {:component :flow/finish :outcome :ok}]}})
        frame (ExecutionFrame. (double-array 0) (long-array 0)
                               (boolean-array 0) (object-array 0)
                               (ArrayList.) (ArrayList.) (ArrayList.)
                               (int-array 0))
        result (vm/execute! (:compiled-program ability) frame
                            (HostTable. (object-array 0) (fn [_] true) (fn [_ _] true))
                            0 {:context {:resources {:cp 1.0}}
                               :costs {:fire {:resources {:cp {:tunable :cost-cp}}}}
                               :tunables {:cost-cp 5.0}})]
    (is (= :finished (:status result)))
    (is (= :broke (:outcome result)))
    (is (zero? (.size ^ArrayList (:actions result))))))

(deftest score-mark-emits-skill-exp-patch-without-naming-the-ability-in-the-program
  (components/reset-for-test!)
  (let [ability (recipe/compile-ability
                  {:schema-version 1 :kind :ability :id :score-mark-test
                   :revision 1 :activation :instant
                   :program {:component :flow/sequence
                             :steps [{:component :score/mark :tag :hit-entity}
                                     {:component :flow/finish :outcome :ok}]}})
        frame (ExecutionFrame. (double-array 0) (long-array 0)
                               (boolean-array 0) (object-array 0)
                               (ArrayList.) (ArrayList.) (ArrayList.)
                               (int-array 0))
        result (vm/execute! (:compiled-program ability) frame
                            (HostTable. (object-array 0) (fn [_] true) (fn [_ _] true))
                            0 {:context {:ability-id :score-mark-test}
                               :progression {:hit-entity {:per-mark {:tunable :exp-entity}}}
                               :tunables {:exp-entity 0.02}})
        entry (first (:entries (.get ^ArrayList (:actions result) 0)))]
    (is (= [:ability-data :skill-exps :score-mark-test] (:path entry)))
    (is (= 0.02 (:value entry)))))

(deftest cooldown-start-emits-cooldown-patch-keyed-by-context-ability-id
  (components/reset-for-test!)
  (let [ability (recipe/compile-ability
                  {:schema-version 1 :kind :ability :id :cooldown-start-test
                   :revision 1 :activation :instant
                   :program {:component :flow/sequence
                             :steps [{:component :cooldown/start :name :main}
                                     {:component :flow/finish :outcome :ok}]}})
        frame (ExecutionFrame. (double-array 0) (long-array 0)
                               (boolean-array 0) (object-array 0)
                               (ArrayList.) (ArrayList.) (ArrayList.)
                               (int-array 0))
        result (vm/execute! (:compiled-program ability) frame
                            (HostTable. (object-array 0) (fn [_] true) (fn [_ _] true))
                            0 {:context {:ability-id :cooldown-start-test}
                               :cooldown {:main {:ticks {:tunable :cooldown-ticks}}}
                               :tunables {:cooldown-ticks 100.0}})
        entry (first (:entries (.get ^ArrayList (:actions result) 0)))]
    (is (= [:cooldown-data :cooldown-start-test :main] (:path entry)))
    (is (= 100.0 (:value entry)))))

(deftest invariant-scope-resolves-declared-value
  (components/reset-for-test!)
  (let [ability (recipe/compile-ability
                  {:schema-version 1 :kind :ability :id :invariant-test
                   :revision 1 :activation :instant
                   :program {:component :flow/sequence
                             :steps [{:component :resource/enforce-floor
                                      :resource :overload
                                      :minimum {:invariant :overload-floor}}
                                     {:component :flow/finish :outcome :ok}]}})
        frame (ExecutionFrame. (double-array 0) (long-array 0)
                               (boolean-array 0) (object-array 0)
                               (ArrayList.) (ArrayList.) (ArrayList.)
                               (int-array 0))
        result (vm/execute! (:compiled-program ability) frame
                            (HostTable. (object-array 0) (fn [_] true) (fn [_ _] true))
                            0 {:context {}
                               :invariants {:overload-floor {:tunable :overload-keep}}
                               :tunables {:overload-keep 250.0}})]
    (is (= :finished (:status result)))
    (is (= 250.0 (:minimum (.get ^ArrayList (:actions result) 0))))))

(deftest fragments-expand-like-a-local-composite
  (testing "schema v2 design D: an ability-scoped :fragments entry is reachable by name from
            :program, exactly like a shared composite, and collapses two call sites into one body"
    (components/reset-for-test!)
    (let [ability (recipe/compile-ability
                    {:schema-version 2 :kind :ability :id :fragments-test
                     :revision 1 :activation :instant
                     :fragments {:mark {:inputs {:tag {:type :object}}
                                        :body {:component :score/mark :tag {:ref [:input :tag]}}}}
                     :program {:component :flow/sequence
                               :steps [{:component :mark :tag :hit-a}
                                       {:component :mark :tag :hit-b}
                                       {:component :flow/finish :outcome :ok}]}})]
      (is (:compiled? ability))
      (is (not-any? #(= :mark (get-in % [:data :component])) (:compiled-ir ability))
          "the fragment reference itself must not survive into compiled IR, only its expansion"))))

(deftest fragment-id-colliding-with-a-builtin-is-rejected
  (components/reset-for-test!)
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"collides with a builtin component"
       (recipe/compile-ability
        {:schema-version 2 :kind :ability :id :fragment-collision-test
         :revision 1 :activation :instant
         :fragments {:flow/finish {:inputs {} :body {:component :flow/finish :outcome :ok}}}
         :program {:component :flow/finish :outcome :ok}}))))

(deftest branch-arm-that-only-calls-a-continue-producing-component-must-throw-not-fall-through
  (testing "a :flow/branch arm whose only step is a component that returns nil on its
            success path (:cost/spend when affordable) but never explicitly reaches a
            :flow/finish must fail loudly -- this is the exact authoring mistake the
            schema v2 thunder-clap rewrite made, and the vm.clj opcode-26 fail-closed
            check exists to catch it instead of silently reading past the program's
            only compiled instruction (which used to throw an opaque
            ArrayIndexOutOfBoundsException)"
    (components/reset-for-test!)
    (let [ability (recipe/compile-ability
                    {:schema-version 2 :kind :ability :id :branch-fallthrough-test
                     :revision 1 :activation :session
                     :fragments
                     {:aim {:inputs {}
                            :body {:component :target/raycast
                                   :origin {:from :caster/eye} :direction {:from :caster/aim}
                                   :distance 10.0
                                   :include-entities? false :include-blocks? true
                                   :result :aim-hit}}}
                     :program
                     {:component :flow/phases
                      :start {:component :flow/finish :outcome :started}
                      :release {:component :flow/finish :outcome :released}
                      :abort {:component :flow/finish :outcome :aborted}
                      :pulse
                      {:component :flow/branch
                       :when {:expr :math/gte :args [{:from :charge/ticks} {:tunable :charge-max}]}
                       :then {:component :flow/finish :outcome :should-not-happen}
                       :else {:component :flow/sequence
                              :steps [{:component :aim}
                                      {:component :effect/vfx :effect-id :x :operation :update
                                       :instance-key [:activation :x]
                                       :payload {:target {:ref [:slot :aim-hit :position]}}}
                                      ;; The bug: this :then arm's only step can return nil
                                      ;; (the affordable path of :cost/spend) with no trailing
                                      ;; :flow/finish, so the arm -- and the whole program --
                                      ;; can finish without ever producing a result.
                                      {:component :flow/branch
                                       :when {:expr :math/lte :args [{:from :charge/ticks} {:tunable :charge-min}]}
                                       :then {:component :cost/spend
                                              :budget :charging
                                              :on-insufficient
                                              {:component :flow/finish :outcome :insufficient-resource
                                               :finish-session? true}}
                                       :else {:component :flow/finish :outcome :continue}}]}}}})
          frame (ExecutionFrame. (double-array 0) (long-array 0)
                                 (boolean-array 0) (object-array 0)
                                 (ArrayList.) (ArrayList.) (ArrayList.)
                                 (int-array 0))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"finished without reaching :flow/finish"
           (vm/execute! (:compiled-program ability) frame
                        (HostTable. (object-array 0) (fn [_] true) (fn [_ _] true))
                        0 {:phase :pulse
                           :context {:resources {}}
                           :from {:charge/ticks 0 :caster/eye {:x 0.0 :y 0.0 :z 0.0}
                                  :caster/aim {:x 0.0 :y 0.0 :z 1.0}}
                           ;; affordable: :charging budget is present, resources default to
                           ;; 0.0, but the cost also resolves to 0.0 here, so 0.0 >= 0.0 passes
                           :costs {:charging {:resources {:cp {:tunable :cost-tick-cp}}}}
                           :tunables {:charge-max 100 :charge-min 40 :cost-tick-cp 0.0}
                           :slots* (volatile! {})}))))))

(def ^:private charged-damage-test-composites
  {:combat/impact-strike
   {:kind :composite :id :combat/impact-strike :revision 1
    :inputs {:target {:type :object} :amount {:type :object}
             :damage-type {:type :keyword} :on-impact {:type :node}}
    :body {:component :flow/sequence
           :steps [{:component :combat/damage
                    :target {:ref [:input :target]}
                    :amount {:ref [:input :amount]}
                    :damage-type {:ref [:input :damage-type]}}
                   {:ref [:input :on-impact]}]}}
   :combat/area-damage
   {:kind :composite :id :combat/area-damage :revision 1
    :iterates {:item {:as :area-target :fields [:id :type :position]}}
    :inputs {:center {:type :object} :radius {:type :object} :filter {:type :object}
             :projection {:type :object} :limit {:type :object}
             :damage {:type :expr-per-item :port :item}
             :damage-type {:type :keyword} :on-impact {:type :node}}
    :body {:component :flow/sequence
           :steps [{:component :target/entities
                    :shape {:type :sphere :center {:ref [:input :center]} :radius {:ref [:input :radius]}}
                    :filter {:ref [:input :filter]} :projection {:ref [:input :projection]}
                    :limit {:ref [:input :limit]} :result :area-targets}
                   {:component :flow/foreach
                    :items {:ref [:slot :area-targets]} :as :area-target :limit {:ref [:input :limit]}
                    :body {:component :combat/impact-strike
                           :target {:ref [:slot :area-target :id]}
                           :amount {:ref [:input :damage]}
                           :damage-type {:ref [:input :damage-type]}
                           :on-impact {:ref [:input :on-impact]}}}]}}
   :combat/charged-area-damage
   {:kind :composite :id :combat/charged-area-damage :revision 1
    :iterates {:item {:as :area-target :fields [:id :type :position]}}
    :inputs {:current-ticks {:type :object} :minimum-ticks {:type :object} :maximum-ticks {:type :object}
             :ratio-slot {:type :keyword} :ratio-min {:type :object} :ratio-max {:type :object}
             :center {:type :object} :radius {:type :object} :filter {:type :object}
             :projection {:type :object} :limit {:type :object}
             :damage {:type :expr-per-item :port :item}
             :damage-type {:type :keyword} :on-impact {:type :node}}
    :body {:component :flow/sequence
           :steps [{:component :data/bind
                    :to {:ref [:input :ratio-slot]}
                    :value {:expr :math/clamp
                            :args [{:expr :math/div
                                    :args [{:expr :math/sub :args [{:ref [:input :current-ticks]}
                                                                    {:ref [:input :minimum-ticks]}]}
                                           {:expr :math/sub :args [{:ref [:input :maximum-ticks]}
                                                                    {:ref [:input :minimum-ticks]}]}]}
                                   {:ref [:input :ratio-min]} {:ref [:input :ratio-max]}]}}
                   {:component :combat/area-damage
                    :center {:ref [:input :center]} :radius {:ref [:input :radius]}
                    :filter {:ref [:input :filter]} :projection {:ref [:input :projection]}
                    :limit {:ref [:input :limit]} :damage {:ref [:input :damage]}
                    :damage-type {:ref [:input :damage-type]} :on-impact {:ref [:input :on-impact]}}]}}})

(deftest charged-area-damage-fragment-with-no-targets-still-reaches-finish
  (testing "the schema v2 :detonate-shaped fragment (charged-area-damage + overcharge lerp
            against a runtime ratio, not skill-exp) must finish cleanly even when the area
            query returns zero targets -- exercises the exact :tunable :path form thunder-clap
            uses to read a raw (lo,hi) pair uncurved"
    (components/reset-for-test!)
    (let [ability (recipe/compile-ability
                  {:schema-version 2 :kind :ability :id :detonate-fragment-test
                   :revision 1 :activation :instant
                   :fragments
                   {:detonate
                    {:inputs {}
                     :body
                     {:component :flow/sequence
                      :steps [{:component :combat/charged-area-damage
                               :current-ticks {:from :charge/ticks}
                               :minimum-ticks {:tunable :charge-min}
                               :maximum-ticks {:tunable :charge-max}
                               :ratio-slot :overcharge-ratio
                               :ratio-min 0.0 :ratio-max 1.0
                               :center {:x 0.0 :y 0.0 :z 0.0}
                               :radius {:tunable :aoe-radius}
                               :filter {}
                               :projection [:id :type :position]
                               :limit 256
                               :damage {:expr :math/mul
                                        :args [{:tunable :damage}
                                               {:expr :math/mul
                                                :args [{:expr :math/lerp
                                                        :args [{:tunable :overcharge-multiplier :path [0]}
                                                               {:tunable :overcharge-multiplier :path [1]}
                                                               {:ref [:slot :overcharge-ratio]}]}
                                                       {:expr :math/max
                                                        :args [0.0
                                                               {:expr :math/sub
                                                                :args [1.0
                                                                       {:expr :math/div
                                                                        :args [{:expr :vec3/distance
                                                                                :args [{:ref [:item :position]}
                                                                                       {:x 0.0 :y 0.0 :z 0.0}]}
                                                                               {:tunable :aoe-radius}]}]}]}]}]}
                               :damage-type :skill
                               :on-impact {:component :flow/sequence :steps []}}
                              {:component :flow/finish :outcome :performed :finish-session? true}]}}}
                   :program {:component :detonate}}
                  {:composites charged-damage-test-composites})
          frame (ExecutionFrame. (double-array 0) (long-array 0)
                                 (boolean-array 0) (object-array 0)
                                 (ArrayList.) (ArrayList.) (ArrayList.)
                                 (int-array 0))
          result (vm/execute! (:compiled-program ability) frame
                              (HostTable. (object-array [(fn [_ _] [])])
                                          (fn [_] true) (fn [_ _] true))
                              0 {:context {}
                                 :from {:charge/ticks 100}
                                 :tunables {:charge-min 40 :charge-max 100 :aoe-radius 5.0
                                            :damage 20.0 :overcharge-multiplier [1.0 2.0]}
                                 :query-order [:entity/select]
                                 :results* (volatile! {})
                                 :slots* (volatile! {})})]
      (is (= :finished (:status result)))
      (is (= :performed (:outcome result))))))

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
                                                  :then {:component :combat/status
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

(deftest random-calls-within-one-activation-can-differ
  (testing (str "defect #21: reusing the raw activation-seed for every random/* call "
                "made two calls in one run always agree; a call-scoped counter "
                "derives a distinct rng-state per call while staying deterministic "
                "for a given (seed, call-index)")
    (components/reset-for-test!)
    (let [ability (recipe/compile-ability
                    {:schema-version 1 :kind :ability :id :rng-fanout-test
                     :revision 1 :activation :instant
                     :program {:component :flow/sequence
                               :steps [{:component :data/bind :to :draw-a
                                        :value {:expr :random/uniform :args [0.0 1.0]}}
                                       {:component :data/bind :to :draw-b
                                        :value {:expr :random/uniform :args [0.0 1.0]}}
                                       {:component :flow/finish :outcome :ok}]}})
          run! (fn []
                 (let [slots* (volatile! {})
                       frame (ExecutionFrame. (double-array 0) (long-array 0)
                                              (boolean-array 0) (object-array 0)
                                              (ArrayList.) (ArrayList.) (ArrayList.)
                                              (int-array 0))]
                   (vm/execute! (:compiled-program ability) frame
                               (HostTable. (object-array 0) (fn [_] true) (fn [_ _] true))
                               0 {:context {} :activation-seed 42
                                  :rng-counter* (volatile! 0) :slots* slots*})
                   @slots*))
          first-run (run!)
          second-run (run!)]
      (is (not= (:draw-a first-run) (:draw-b first-run))
          "two random/uniform calls within the same activation must not agree")
      (is (= first-run second-run)
          "the same activation-seed must still reproduce identical draws"))))

(deftest load-catalog-isolates-a-single-ability-failure
  (testing (str "Design E: a bad manifest entry (unparseable document, id "
                "mismatch, dataflow violation, ...) must disable only that "
                "ability, not take the whole catalog load down")
    (components/reset-for-test!)
    (let [manifest {:documents [{:kind :ability :id :good-one :resource "good"}
                                 {:kind :ability :id :broken-one :resource "broken"}]}
          good-doc {:schema-version 1 :kind :ability :id :good-one :revision 1
                    :activation :instant
                    :program {:component :flow/finish :outcome :ok}}
          broken-doc {:schema-version 1 :kind :ability :id :broken-one :revision 1
                      :activation :instant
                      :program {:component :skill/no-such-component}}]
      (with-redefs [safe-edn/read-resource!
                    (fn [path]
                      (case path
                        "manifest" manifest
                        (throw (ex-info "unexpected manifest resource" {:path path}))))]
        (let [catalog (recipe/load-catalog!
                        {:manifest-resource "manifest"
                         :document-loader (fn [resource]
                                            (case resource
                                              "good" good-doc
                                              "broken" broken-doc))})]
          (is (contains? (:abilities catalog) :good-one)
              "the unrelated ability must still load")
          (is (not (contains? (:abilities catalog) :broken-one))
              "the broken ability must not be in the compiled catalog")
          (is (contains? (:errors catalog) :broken-one)
              "the failure must be surfaced, not silently dropped")
          (is (not (contains? (:errors catalog) :good-one))))))))

(deftest vfx-signal-requirements-finds-nodes-nested-under-flow-branch
  (let [ability (recipe/compile-ability
                 {:schema-version 1 :kind :ability :id :vfx-req-test
                  :revision 1 :activation :instant
                  :program
                  {:component :flow/sequence
                   :steps [{:component :flow/branch
                            :when true
                            :then {:component :effect/vfx
                                   :effect-id :arc-ring-session
                                   :operation :spawn
                                   :payload {:start {:vec3 [0.0 0.0 0.0]}
                                             :end {:vec3 [1.0 0.0 0.0]}}}
                            :else {:component :flow/finish :outcome :idle}}
                           {:component :effect/vfx
                            :effect-id :arc-ring-session
                            :operation :destroy
                            :payload {}}
                           {:component :flow/finish :outcome :ok}]}})
        requirements (recipe/vfx-signal-requirements ability)]
    (is (= 2 (count requirements))
        "one node nested under :flow/branch's :then, one at the top level")
    (is (= #{:spawn :destroy} (set (map :operation requirements))))
    (is (every? #(= :vfx-req-test (:ability-id %)) requirements))
    (is (every? #(= :arc-ring-session (:effect-id %)) requirements))
    (is (= #{:start :end}
           (:payload-keys (first (filter #(= :spawn (:operation %)) requirements)))))))
