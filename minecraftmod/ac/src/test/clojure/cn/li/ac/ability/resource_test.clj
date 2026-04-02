(ns cn.li.ac.ability.resource-test
  (:require [cn.li.ac.ability.model.resource-data :as rd]
            [cn.li.ac.ability.service.resource :as res]))

(defn test-consume-and-recover
  []
  (let [r0 (assoc (rd/new-resource-data) :activated true)
        {:keys [data success?]} (res/perform-resource r0 "u" 10.0 20.0 false)
        {:keys [data]} (res/server-tick data)]
    (assert success? "resource perform should succeed")
    (assert (<= (:cur-cp data) (:max-cp data)) "cp should be bounded")))

(defn run-all-tests []
  (println "=== ability resource tests ===")
  (test-consume-and-recover)
  (println "ok"))
