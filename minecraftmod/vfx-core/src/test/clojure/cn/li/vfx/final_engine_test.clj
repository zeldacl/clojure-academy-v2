(ns cn.li.vfx.final-engine-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.vfx.effect-schema :as schema]
            [cn.li.vfx.replication :as replication]
            [cn.li.vfx.final-engine :as engine]))

(def catalog
  (schema/catalog [{:id :ring
                    :lifecycle :transient
                    :parameters {:radius {:type :float :default 1.0 :mutability :per-tick}
                                 :duration-ticks {:type :int :default 2}}
                    :primitives #{:line}}]))

(deftest lifecycle-and-render-batch-test
  (let [service (replication/create-service
                  {:catalog catalog
                   :nearby (fn [_ _] #{:alice})})
        runtime (engine/create-runtime {:catalog catalog
                                        :replication-service service})
        spawned (engine/spawn! runtime :ring {:owner :caster :world-id "w"
                                              :anchor :a :params {:radius 2.0}})
        updated (engine/update! runtime (:handle spawned) {:radius 3.0})
        tick1 (engine/tick! runtime)
        tick2 (engine/tick! runtime)]
    (is (= :spawn (:op (:packet spawned))))
    (is (= :line (:primitive (first (:render spawned)))))
    (is (= 3.0 (get-in updated [:render 0 :params :radius])))
    (is (empty? (:expired tick1)))
    (is (= [(:handle spawned)] (:expired tick2)))
    (is (empty? (:instances (engine/snapshot runtime))))))

(deftest unknown-parameter-is-rejected-test
  (let [runtime (engine/create-runtime {:catalog catalog})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (engine/spawn! runtime :ring {:params {:unknown 1.0}})))))

