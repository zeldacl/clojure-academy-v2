(ns cn.li.vfx.replication-contract-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.vfx.effect-schema :as schema]
            [cn.li.vfx.replication :as replication]))
(def catalog (schema/catalog [{:id :ring :lifecycle :session :parameters {:radius {:type :float :mutability :per-tick} :color {:type :color}} :primitives #{:line}}]))
(deftest catalog-and-256-bit-mask-test
  (is (string? (:hash catalog)))
  (is (= 2 (:word-count (schema/diff-mask (mapv #(schema/parameter (keyword (str "p" %)) {:type :float}) (range 65)) {} (into {} (map #(vector (keyword (str "p" %)) 1) (range 65))))))))
(deftest anchor-tracking-and-handle-update-test
  (let [service (replication/create-service {:catalog catalog :nearby (fn [_ anchor] (if (= :a anchor) #{:alice} #{}))})
        spawned (replication/spawn! service :ring {:owner :caster :world-id "w" :anchor :a :params {:radius 1.0}})
        packets (replication/tick-tracking! service)
        updated (replication/update! service (:handle spawned) {:radius 2.0})]
    (is (= :spawn (:op (:packet spawned))))
    (is (= [:alice] (vec (sort (map :recipient packets)))))
    (is (= 2.0 (get-in updated [:packet :params :radius])))
    (is (= 1 (get-in updated [:packet :mask :word-count])))))

(deftest unknown-replication-effect-is-rejected-test
  (let [service (replication/create-service {:catalog catalog
                                             :nearby (fn [_ _] #{})})
        error (try
                (replication/spawn! service :missing {:owner :caster
                                                       :world-id "w"})
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is error)
    (is (= :missing (:effect-id (ex-data error))))))


(deftest snapshot-release-destroy-semantics-test
  (let [service (replication/create-service {:catalog catalog
                                             :nearby (fn [_ anchor] (if (= :a anchor) #{:alice} #{}))})
        spawned (replication/spawn! service :ring {:owner :caster :world-id "w" :anchor :a
                                               :instance-key :cast :params {:radius 1.0}})
        entered (replication/tracking-enter! service :alice :a)
        left (replication/tracking-leave! service :alice :a)
        destroyed (replication/destroy! service (:handle spawned))]
    (is (= :snapshot (:op (first entered))))
    (is (= :release (:op (first left))))
    (is (= :destroy (:op (:packet destroyed))))
    (is (some? (replication/tombstone service (:instance-id (:handle spawned)))))))

