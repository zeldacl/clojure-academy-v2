(ns cn.li.node.document-migrate-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.node.document :as document]
            [cn.li.node.document-migrate :as migrate]
            [cn.li.node.surface :as surface]))

(deftest migrates-surface-program-to-map-shaped-v3
  (let [old {:id :arc-gen
             :program "{:ability :arc-gen :activation :instant :tunables {:damage {:type :double}} :do [(let hit (target/raycast {:from ?caster/eye :dir ?caster/aim :distance $damage})) (when (:entity-id hit) (combat/damage {:target (:entity-id hit) :amount $damage})) (finish {:outcome :performed})]}"}
        v3 (migrate/migrate-skill old)]
    (document/validate-document! v3)
    (is (= :ac/skill-v3 (:schema v3)))
    (is (= :arc-gen (:id v3)))
    (is (every? :nid (get-in v3 [:entries :default :do])))
    (is (= :parameter (first (get-in v3 [:entries :default :do 0 :value :inputs :distance :ref]))))))

(deftest registration-id-replaces-old-source-indirection
  (let [old {:id :shared
             :program "{:ability :shared :activation :instant :do [(finish {:outcome :performed})]}"}
        v3 (migrate/migrate-registration
            {:id :electromaster/shared
             :source-id :shared
             :bindings {:metadata {:category-id :electromaster}
                        :presentation {:variant :expert}}}
            old)]
    (document/validate-document! v3)
    (is (= :electromaster/shared (:id v3)))
    (is (= :electromaster (get-in v3 [:skill :category])))
    (is (= :expert (get-in v3 [:presentation :variant])))))

