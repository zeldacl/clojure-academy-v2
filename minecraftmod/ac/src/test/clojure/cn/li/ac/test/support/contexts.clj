(ns cn.li.ac.test.support.contexts
  (:require [cn.li.ac.ability.service.dispatcher :as ctx]))

(defn clean-contexts-fixture
  [f]
  (ctx/reset-contexts-for-test!)
  (ctx/reset-lifecycle-counters!)
  (ctx/reset-route-fns-for-test!)
  (f)
  (ctx/reset-contexts-for-test!)
  (ctx/reset-route-fns-for-test!))
