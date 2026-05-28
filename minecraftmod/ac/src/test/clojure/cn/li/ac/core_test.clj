(ns cn.li.ac.core-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.core :as ac-core]
            [cn.li.ac.testing.smoke-manifest :as smoke-manifest]
            [cn.li.mcmod.lifecycle :as lifecycle]))

(defn- reset-core-guard! [f]
  (ac-core/reset-lifecycle-hooks-guard-for-test!)
  (try
    (f)
    (finally
      (ac-core/reset-lifecycle-hooks-guard-for-test!))))

(use-fixtures :each reset-core-guard!)

(deftest register-lifecycle-hooks-idempotent-test
  (let [calls (atom [])]
    (with-redefs [smoke-manifest/register! (fn [] (swap! calls conj :smoke) nil)
                  lifecycle/register-content-init! (fn [_] (swap! calls conj :content-init) nil)
                  lifecycle/register-runtime-content-activation! (fn [_] (swap! calls conj :runtime-activation) nil)
                  lifecycle/register-datagen-metadata-init! (fn [_] (swap! calls conj :datagen-init) nil)
                  requiring-resolve (fn [sym]
                                      (when (= sym 'cn.li.mcmod.lifecycle/register-client-init!)
                                        (fn [_] (swap! calls conj :client-init) nil)))]
      (ac-core/register-lifecycle-hooks!)
      (ac-core/register-lifecycle-hooks!))
    (is (= [:smoke :content-init :runtime-activation :datagen-init :client-init]
           @calls))))

(deftest lifecycle-hooks-runtime-isolation-test
  (let [runtime-a (ac-core/create-lifecycle-hooks-runtime)
        runtime-b (ac-core/create-lifecycle-hooks-runtime)]
    (ac-core/call-with-lifecycle-hooks-runtime
      runtime-a
      (fn []
        (is (false? (ac-core/lifecycle-hooks-guard-snapshot)))
        (ac-core/reset-lifecycle-hooks-guard-for-test! true)
        (is (true? (ac-core/lifecycle-hooks-guard-snapshot)))))
    (ac-core/call-with-lifecycle-hooks-runtime
      runtime-b
      (fn []
        (is (false? (ac-core/lifecycle-hooks-guard-snapshot)))))
    (ac-core/call-with-lifecycle-hooks-runtime
      runtime-a
      (fn []
        (is (true? (ac-core/lifecycle-hooks-guard-snapshot)))))))
