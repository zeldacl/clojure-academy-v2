(ns cn.li.node.graph-document-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.node.graph-document :as doc]
            [cn.li.node.graph-compile :as graph-compile]))

(defn- n [id type & kvs]
  (into {:nid id :type type} (apply hash-map kvs)))

(defn- e [id kind from to]
  {:id id :kind kind :from from :to to})

(def simple-graph
  {:nodes {:n/start (n :n/start :start)
           :n/end (n :n/end :end)}
   :links [(e :e/start :exec [:n/start :out] [:n/end :in])]})

(def skill
  {:schema :ac/skill-v4
   :id :test/skill
   :skill {:category :test :level 1}
   :activation {:mode :instant}
   :parameters {}
   :state {}
   :graphs {:default {:on :activation/start
                      :nodes (:nodes simple-graph)
                      :links (:links simple-graph)}}})

(deftest accepts-minimal-v4-skill
  (is (= skill (doc/validate-document! skill)))
  (is (= :skill (doc/kind skill)))
  (is (= (dissoc skill :editor) (doc/semantic-document skill))))

(deftest rejects-ordinary-cycle
  (let [graph {:nodes {:n/start (n :n/start :start)
                       :n/a (n :n/a :local-set :key :x :operation :define)
                       :n/end (n :n/end :end)}
               :links [(e :e/one :exec [:n/start :out] [:n/a :in])
                       (e :e/two :exec [:n/a :out] [:n/a :in])] }]
    (is (try
          (doc/validate-graph! graph [:graphs :default])
          false
          (catch clojure.lang.ExceptionInfo _
            true)))))

(deftest accepts-explicit-loop-back
  (let [graph {:nodes {:n/start (n :n/start :start)
                       :n/loop (n :n/loop :repeat :count 2)
                       :n/body (n :n/body :local-set :key :x :operation :define)
                       :n/end (n :n/end :end)
                       :n/loop-end (n :n/loop-end :loop-end)}
               :links [(e :e/one :exec [:n/start :out] [:n/loop :in])
                       (e :e/two :exec [:n/loop :body] [:n/body :in])
                       (e :e/three :exec [:n/loop :completed] [:n/end :in])
                       (e :e/four :exec [:n/body :out] [:n/loop-end :in])
                       (e :e/five :exec [:n/loop-end :continue] [:n/loop :loop-back])] }]
    (is (= graph (doc/validate-graph! graph [:graphs :default])))))

(deftest rejects-branch-without-both-outputs
  (let [graph {:nodes {:n/start (n :n/start :start)
                       :n/branch (n :n/branch :branch)
                       :n/end (n :n/end :end)}
               :links [(e :e/one :exec [:n/start :out] [:n/branch :in])
                       (e :e/two :exec [:n/branch :true] [:n/end :in])] }]
    (is (try
          (doc/validate-graph! graph [:graphs :default])
          false
          (catch clojure.lang.ExceptionInfo e
            (re-find #"true and false" (.getMessage e)))))))

(deftest compiles-minimal-v4-graph
  (let [d (assoc skill :graphs {:default {:on :activation/start
                                          :nodes {:n/start (n :n/start :start)
                                                  :n/action (n :n/action :component :component :test/do)
                                                  :n/end (n :n/end :end :result {:outcome :done})}
                                          :links [(e :e/link-a :exec [:n/start :out] [:n/action :in])
                                                  (e :e/link-b :exec [:n/action :out] [:n/end :in])]}})
        {:keys [ir diagnostics]} (graph-compile/compile-skill! d {:vocab {:test/do {:params {}}}} :collect)]
    (is (empty? diagnostics))
    (is (map? ir))))

(deftest preserves-inline-component-inputs
  (let [d (assoc skill :graphs {:default {:on :activation/start
                                          :nodes {:n/start (n :n/start :start)
                                                  :n/action (n :n/action :component :component :test/do
                                                                  :inputs {:amount 3})
                                                  :n/end (n :n/end :end)}
                                          :links [(e :e/aaa :exec [:n/start :out] [:n/action :in])
                                                  (e :e/bbb :exec [:n/action :out] [:n/end :in])]}})
        {:keys [diagnostics]} (graph-compile/compile-skill! d {:vocab {:test/do {:params {:amount {:type :long}}}}} :collect)]
    (is (empty? diagnostics))))
