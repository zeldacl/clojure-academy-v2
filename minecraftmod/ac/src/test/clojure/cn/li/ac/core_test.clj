(ns cn.li.ac.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.core :as ac-core]
            [cn.li.ac.testing.smoke-manifest :as smoke-manifest]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.lifecycle :as lifecycle]))

(defn- reset-core-guard! [f]
  (ac-core/reset-lifecycle-hooks-guard-for-test!)
  (try
    (f)
    (finally
      (ac-core/reset-lifecycle-hooks-guard-for-test!))))

(use-fixtures :each reset-core-guard!)

(defn- with-redefs-for-hook-calls [calls body]
  (with-redefs [smoke-manifest/register! (fn [] (swap! calls conj :smoke) nil)
                lifecycle/register-content-init! (fn [_] (swap! calls conj :content-init) nil)
                lifecycle/register-runtime-content-activation! (fn [_] (swap! calls conj :runtime-activation) nil)
                lifecycle/register-datagen-metadata-init! (fn [_] (swap! calls conj :datagen-init) nil)
                lifecycle/register-client-init! (fn [_] (swap! calls conj :client-init) nil)]
    (body)))

(deftest register-lifecycle-hooks-idempotent-test
  (let [calls (atom [])]
    (with-redefs-for-hook-calls calls
      (ac-core/register-lifecycle-hooks!)
      (ac-core/register-lifecycle-hooks!))
    (is (= [:smoke :content-init :runtime-activation :datagen-init :client-init]
           @calls))))

(deftest register-lifecycle-hooks-with-framework-test
  (testing "hooks register when Framework atom already exists (normal mod startup order)"
    (let [calls (atom [])
          prev-fw fw/framework]
      (try
        (when-let [fw-inst (fw/create-framework)]
          (alter-var-root #'fw/framework (constantly fw-inst))
          (with-redefs-for-hook-calls calls
            (is (false? (ac-core/lifecycle-hooks-guard-snapshot)))
            (ac-core/register-lifecycle-hooks!)
            (is (true? (ac-core/lifecycle-hooks-guard-snapshot)))
            (ac-core/register-lifecycle-hooks!))
          (is (= [:smoke :content-init :runtime-activation :datagen-init :client-init]
                 @calls)))
        (finally
          (alter-var-root #'fw/framework (constantly prev-fw)))))))
