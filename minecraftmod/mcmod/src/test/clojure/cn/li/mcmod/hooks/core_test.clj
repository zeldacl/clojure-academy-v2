(ns cn.li.mcmod.hooks.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.mcmod.hooks.core :as hooks]))

(defn- clean-hooks-fixture
  [f]
  ;; reset by installing no-op overrides for deterministic tests
  (hooks/register-power-runtime-hooks! {:on-player-login! (fn [_] nil)
                                        :on-player-logout! (fn [_] nil)})
  (f)
  (hooks/register-power-runtime-hooks! {:on-player-login! (fn [_] nil)
                                        :on-player-logout! (fn [_] nil)}))

(use-fixtures :each clean-hooks-fixture)

(deftest register-runtime-hooks-validation-test
  (testing "valid known hook keys and fn values are accepted"
    (is (nil? (hooks/register-power-runtime-hooks!
               {:on-player-login! (fn [_] :ok)
                :on-player-logout! (fn [_] :ok)}))))
  (testing "unknown keys fail fast"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown runtime hook keys"
                          (hooks/register-power-runtime-hooks!
                           {:unknown/hook (fn [& _] nil)}))))
  (testing "non-fn values fail contract"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"runtime-hooks contract violation"
                          (hooks/register-power-runtime-hooks!
                           {:on-player-login! :not-a-fn})))))