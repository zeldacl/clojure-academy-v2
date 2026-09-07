(ns cn.li.node.document-compile-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.node.document-compile :as document-compile]
            [cn.li.node.test-fixtures :as fx]))

(def skill
  {:schema :ac/skill-v3
   :id :ac.skill/compile-example
   :skill {:category :electromaster :level 1}
   :activation {:mode :instant}
   :parameters {:combat/damage {:type :double :default 10.0}}
   :entries
   {:activate
    {:on :activation/start
     :do [{:nid :n/damage
           :component :combat/damage
           :inputs {:target {:ref [:context :target]}
                    :amount {:ref [:parameter :combat/damage]}
                    :damage-type :skill}}
          {:nid :n/done
           :flow :finish
           :result {:outcome :performed}}]}}})

(deftest lower-and-compile-v3-skill
  (let [{:keys [ir diagnostics]}
        (document-compile/compile-skill!
         skill (assoc fx/opts
                      :capabilities (assoc (:capabilities fx/opts)
                                           :target :entity-ref)
                      :vocab (assoc (:vocab fx/opts)
                                    :combat/damage {:params {:target {:type :any}
                                                             :amount {:type :double}
                                                             :damage-type {:type :keyword}}}))
         :collect)]
    (is (empty? diagnostics))
    (is (= :ac.skill/compile-example (:id ir)))
    (is (= {:activate :activation/start} (:entry-triggers ir)))
    (is (some #(= "n/damage" (:nid %)) (mapcat :instrs (:blocks ir))))))
