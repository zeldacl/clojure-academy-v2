(ns cn.li.ac.ability.util.toggle-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.test.support.contexts :as test-contexts]
            [cn.li.ac.ability.state.context :as ctx]
            [cn.li.ac.ability.util.toggle :as tg]))

(use-fixtures :each test-contexts/clean-contexts-fixture)

(deftest init-and-query-toggle-test
  (let [m (tg/init-toggle-state :my-skill)]
    (is (true? (:active m)))
    (is (= :my-skill (:skill-id m)))
    (is (= 0 (:start-tick m)))
    (is (= 0 (:total-ticks m))))
  (is (not (tg/is-toggle-active? {} :x)))
  (is (true? (tg/is-toggle-active? {:skill-state {:toggle {:x {:active true}}}} :x)))
  (is (false? (tg/is-toggle-active? {:skill-state {:toggle {:x {:active false}}}} :x)))
  (is (= {:active true} (tg/get-toggle-state {:skill-state {:toggle {:s {:active true}}}} :s))))

(deftest toggle-mutations-via-context-test
  (let [c (ctx/new-server-context "p" :skill "ctx-toggle")]
    (ctx/register-context! c)
    (tg/activate-toggle! "ctx-toggle" :s)
    (is (tg/is-toggle-active? (ctx/get-context "ctx-toggle") :s))
    (tg/update-toggle-tick! "ctx-toggle" :s)
    (is (= 1 (:total-ticks (tg/get-toggle-state (ctx/get-context "ctx-toggle") :s))))
    (tg/deactivate-toggle! "ctx-toggle" :s)
    (is (false? (:active (tg/get-toggle-state (ctx/get-context "ctx-toggle") :s))))
    (tg/remove-toggle! "ctx-toggle" :s)
    (is (nil? (tg/get-toggle-state (ctx/get-context "ctx-toggle") :s)))))
