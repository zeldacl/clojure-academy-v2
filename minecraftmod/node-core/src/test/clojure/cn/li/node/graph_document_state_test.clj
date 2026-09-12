(ns cn.li.node.graph-document-state-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.node.graph-document :as document]))

(def base-skill
  {:schema :ac/skill-v4
   :id :test/state-defaults
   :skill {:name "test"}
   :activation {:type :manual}
   :parameters {}
   :graphs {:default {:nodes {:n/start {:nid :n/start :type :start}
                              :n/end {:nid :n/end :type :end}}
                      :links [{:id :e/start :kind :exec
                               :from [:n/start :out] :to [:n/end :in]}]}}})

(deftest malformed-primitive-state-defaults-are-rejected-test
  (doseq [[type value]
          [[:double "not-a-number"]
           [:long 1.5]
           [:vec3 [1.0 2.0]]]]
    (let [doc (assoc base-skill :state
                     {:value {:type type :default value}})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (document/validate-document! doc))))))

(deftest valid-vec3-state-default-is-accepted-test
  (let [doc (assoc base-skill :state
                   {:value {:type :vec3 :default {:vec3 [0.0 0.0 0.0]}}})]
    (is (map? (document/validate-document! doc)))))

(deftest undeclared-formula-tunable-is-rejected-test
  (let [doc (assoc base-skill
                   :costs {:pulse {:resources {:cp {:ref [:input :tunables :missing]}}}})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (document/validate-document! doc)))))

(deftest declared-formula-tunable-is-accepted-test
  (let [doc (-> base-skill
                (assoc :parameters {:cost-tick-cp {:type :double :default nil}})
                (assoc :costs {:pulse {:resources {:cp {:ref [:input :tunables :cost-tick-cp]}}}}))]
    (is (map? (document/validate-document! doc)))))
