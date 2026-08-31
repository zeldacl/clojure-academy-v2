(ns cn.li.node.composite-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.node.environment :as environment]
            [cn.li.node.flow :as flow]
            [cn.li.node.composite :as composite]))

(def ^:private primitive-descriptors
  (concat flow/builtin-descriptors
          [{:id :test/query :revision 1 :layer :primitive
            :inputs {:origin {:type :vec3}} :outputs {:hit {:type :hit-result}}
            :impl (fn [_ _] {})}
           {:id :test/resolve :revision 1 :layer :primitive
            :inputs {:hit {:type :hit-result}} :outputs {:destination {:type :destination}}
            :impl (fn [_ _] {})}
           {:id :test/emit :revision 1 :layer :primitive
            :inputs {:value {:type :any}} :impl (fn [_ _] {})}]))

(def ^:private test-environment (environment/build {:descriptors primitive-descriptors}))

(def ^:private query-destination
  {:id :test/query-destination :revision 1 :layer :composite
   :inputs {:origin {:type :vec3}}
   :outputs {:destination {:type :destination :from [:local :dest]}}
   :body {:component :flow/sequence
          :steps [{:component :test/query :origin {:ref [:input :origin]} :bind {:hit :h}}
                  {:component :test/resolve :hit {:ref [:local :h]} :bind {:destination :dest}}]}})

(defn- expand [node & [composites]]
  (composite/expand-with-environment-and-composites test-environment node (or composites {})))

(deftest expand-substitutes-inputs-and-output-bind-test
  (let [expanded (expand {:component :test/query-destination
                          :origin {:vec3 [1.0 2.0 3.0]}
                          :bind {:destination :final}}
                         {:test/query-destination query-destination})]
    (is (= :flow/sequence (:component expanded)))
    (is (= {:vec3 [1.0 2.0 3.0]} (:origin (first (:steps expanded)))))
    (is (= :final (:to (last (:steps expanded)))))))

(deftest expansion-renames-internal-locals-per-call-site-test
  (let [a (expand {:component :test/query-destination :origin {:vec3 [0.0 0.0 0.0]}}
                  {:test/query-destination query-destination})
        b (expand {:component :test/query-destination :origin {:vec3 [0.0 0.0 0.0]}}
                  {:test/query-destination query-destination})]
    (is (not= (get-in a [:steps 0 :bind :hit])
              (get-in b [:steps 0 :bind :hit])))))

(deftest expansion-rejects-unknown-input-and-cycles-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown-composite-input"
                        (expand {:component :test/query-destination :bogus 1}
                                {:test/query-destination query-destination})))
  (let [cyclic {:id :test/cyclic :revision 1 :layer :composite :inputs {} :outputs {}
                :body {:component :test/cyclic}}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"composite-expansion-cycle"
                          (expand {:component :test/cyclic} {:test/cyclic cyclic})))))
