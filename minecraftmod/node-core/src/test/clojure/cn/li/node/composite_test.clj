(ns cn.li.node.composite-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.node.descriptor :as registry]
            [cn.li.node.flow :as flow]
            [cn.li.node.composite :as composite]))

(use-fixtures :each
  (fn [f]
    (registry/reset-for-test!)
    (flow/install!)
    (registry/register-primitive!
     {:id :test/query :revision 1 :inputs {:origin {:type :vec3}} :outputs {:hit {:type :hit-result}}
      :impl (fn [_ _] {})})
    (registry/register-primitive!
     {:id :test/resolve :revision 1 :inputs {:hit {:type :hit-result}} :outputs {:destination {:type :destination}}
      :impl (fn [_ _] {})})
    (registry/register-composite!
     {:id :test/query-destination :revision 1 :layer :mid
      :inputs {:origin {:type :vec3}}
      :outputs {:destination {:type :destination :from [:local :dest]}}
      :body {:component :flow/sequence
             :steps [{:component :test/query :origin {:ref [:input :origin]} :bind {:hit :h}}
                     {:component :test/resolve :hit {:ref [:local :h]} :bind {:destination :dest}}]}})
    (registry/register-composite!
     {:id :test/no-output :revision 1 :layer :mid
      :inputs {}
      :outputs {}
      :body {:component :test/query :origin {:vec3 [0.0 0.0 0.0]}}})
    (registry/register-composite!
     {:id :test/source :revision 1 :layer :source :outputs {:value {:type :double}}})
    (f)
    (registry/reset-for-test!)))

(deftest expand-substitutes-inputs-and-produces-only-primitives-test
  (let [expanded (composite/expand {:component :test/query-destination :origin {:vec3 [1.0 2.0 3.0]}
                                    :bind {:destination :final}})]
    ;; The whole thing collapses into a :flow/sequence of real primitives --
    ;; no :test/query-destination id survives expansion.
    (is (= :flow/sequence (:component expanded)))
    (is (every? (fn [step] (not= :test/query-destination (:component step))) (:steps expanded)))
    ;; origin substituted as a literal into the first real query.
    (is (= {:vec3 [1.0 2.0 3.0]} (:origin (first (:steps expanded)))))
    ;; the requested output bind becomes a final :data/bind step.
    (let [last-step (last (:steps expanded))]
      (is (= :data/bind (:component last-step)))
      (is (= :final (:to last-step))))))

(deftest expand-with-no-output-request-does-not-add-bind-step-test
  (let [expanded (composite/expand {:component :test/no-output})]
    (is (= :test/query (:component expanded)))))

(deftest two-call-sites-get-independently-renamed-locals-test
  (let [a (composite/expand {:component :test/query-destination :origin {:vec3 [0.0 0.0 0.0]} :bind {:destination :a}})
        b (composite/expand {:component :test/query-destination :origin {:vec3 [0.0 0.0 0.0]} :bind {:destination :b}})
        internal-name (fn [expanded] (get-in expanded [:steps 0 :bind :hit]))]
    (is (not= (internal-name a) (internal-name b)))))

(deftest unknown-composite-input-throws-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"unknown-composite-input"
       (composite/expand {:component :test/query-destination :origin {:vec3 [0.0 0.0 0.0]} :bogus 1}))))

(deftest missing-required-input-throws-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"missing-required-field"
       (composite/expand {:component :test/query-destination}))))

(deftest unknown-output-port-throws-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"unknown-output-port"
       (composite/expand {:component :test/query-destination :origin {:vec3 [0.0 0.0 0.0]}
                          :bind {:not-a-port :x}}))))

(deftest source-node-inside-composite-body-throws-test
  (registry/register-composite!
   {:id :test/bad-composite :revision 1 :layer :mid :inputs {} :outputs {}
    :body {:component :test/source}})
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"source-node-outside-ability"
       (composite/expand {:component :test/bad-composite}))))

(deftest cycle-detection-throws-test
  (registry/register-composite!
   {:id :test/cyclic :revision 1 :layer :mid :inputs {} :outputs {}
    :body {:component :test/cyclic}})
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"composite-expansion-cycle"
       (composite/expand {:component :test/cyclic}))))
