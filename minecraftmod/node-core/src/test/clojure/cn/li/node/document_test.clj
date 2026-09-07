(ns cn.li.node.document-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.node.document :as document]
            [cn.li.node.api :as api]))

(def skill
  {:schema :ac/skill-v3
   :id :ac.skill/example
   :skill {:category :electromaster :level 1}
   :activation {:mode :instant}
   :parameters {:combat/damage {:type :double :default 10.0}}
   :entries
   {:activate
    {:on :activation/start
     :do [{:nid :n/raycast
           :component :target/raycast
           :inputs {:origin {:ref [:context :caster/eye]}}
           :bind {:result :hit}}
          {:nid :n/done
           :flow :finish
           :result {:outcome :performed}}]}}
   :editor {:layout {:n/raycast {:x 0 :y 0}}}})

(deftest validates-skill-and-ignores-editor-in-digest
  (is (= :skill (document/kind skill)))
  (is (= (api/document-semantic-digest skill)
         (api/document-semantic-digest
          (assoc-in skill [:editor :layout :n/raycast] {:x 200 :y 400})))))
  (is (= skill (document/validate-document! skill)))

(deftest rejects-unstable-or-unbounded-documents
  (testing "invalid nid"
    (is (thrown? clojure.lang.ExceptionInfo
                 (document/validate-document!
                  (assoc-in skill [:entries :activate :do 0 :nid] :bad/id)))))
  (testing "unbounded foreach"
    (is (thrown? clojure.lang.ExceptionInfo
                 (document/validate-document!
                  (assoc-in skill [:entries :activate :do]
                            [{:nid :n/loop
                              :flow :foreach
                              :collection {:nid :n/list :ref [:context :items]}
                              :as :item
                              :limit 0
                              :do []}]))))))
  (testing "duplicate node ids"
    (let [duplicate (assoc-in skill [:entries :activate :do 1 :nid] :n/raycast)]
      (try
        (document/validate-document! duplicate)
        (is false "duplicate node IDs must be rejected")
        (catch clojure.lang.ExceptionInfo error
          (is (re-find #"node IDs must be unique"
                       (.getMessage error)))))))
  (testing "invalid nested input node"
    (let [invalid (assoc-in skill [:entries :activate :do 0 :inputs :origin]
                            {:nid :bad/id :ref [:context :caster/eye]})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (document/validate-document! invalid)))))
  (testing "bind must carry a value"
    (let [invalid (assoc-in skill [:entries :activate :do 0]
                            {:nid :n/bind :flow :bind :name :local})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (document/validate-document! invalid)))))
