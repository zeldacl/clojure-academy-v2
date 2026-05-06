(ns cn.li.ac.test.support.contexts
  (:require [cn.li.ac.ability.state.context :as ctx]))

(defn clean-contexts-fixture
  [f]
  (doseq [ctx-id (keys (ctx/get-all-contexts))]
    (ctx/remove-context! ctx-id))
  (f)
  (doseq [ctx-id (keys (ctx/get-all-contexts))]
    (ctx/remove-context! ctx-id)))
