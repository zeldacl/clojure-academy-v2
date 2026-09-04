(ns cn.li.node.surface-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.node.surface :as surface]))

(deftest sigil-classification-test
  (is (= [:tunable :range] (surface/sigil '$range)))
  (is (= [:capability :caster/eye] (surface/sigil '?caster/eye)))
  (is (= [:local 'hit] (surface/sigil 'hit))))

(deftest read-doc-rejects-multiple-top-level-forms-test
  (is (thrown? clojure.lang.ExceptionInfo (surface/read-doc "{:a 1} {:b 2}"))))

(deftest read-doc-rejects-non-map-top-level-test
  (is (thrown? clojure.lang.ExceptionInfo (surface/read-doc "[1 2 3]"))))

(deftest read-doc-rejects-unknown-tagged-literals-test
  (is (thrown? clojure.lang.ExceptionInfo (surface/read-doc "#some/custom-tag {:a 1}"))))

(deftest normalize-do-sugar-becomes-default-entry-test
  (let [doc (surface/parse "{:ability :x :tunables {} :do [(finish {:outcome :performed})]}")]
    (is (= :ability (:kind doc)))
    (is (= :x (:id doc)))
    (is (contains? (:entries doc) :default))))

(deftest normalize-phases-pass-through-test
  (let [doc (surface/parse "{:ability :x :tunables {} :phases {:start [(finish {:outcome :performed})]
                                                                :pulse [(finish {:outcome :ended})]}}")]
    (is (= #{:start :pulse} (set (keys (:entries doc)))))))

(deftest normalize-defn-doc-test
  (let [doc (surface/parse "{:defn :ac/strike :params [{:name target :type :entity-ref}] :do [(cooldown/start {:name :main :ticks 1})]}")]
    (is (= :defn (:kind doc)))
    (is (= :ac/strike (:id doc)))
    (is (= [{:name 'target :type :entity-ref}] (:params doc)))))
