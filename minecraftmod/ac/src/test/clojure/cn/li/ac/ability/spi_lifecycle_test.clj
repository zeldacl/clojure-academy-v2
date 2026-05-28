(ns cn.li.ac.ability.spi-lifecycle-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.spi-lifecycle :as spi]))

(defn- clean-fixture [f]
  (spi/reset-lifecycle-registry-for-test!)
  (try
    (f)
    (finally
      (spi/reset-lifecycle-registry-for-test!))))

(use-fixtures :each clean-fixture)

(deftest lifecycle-registry-runtime-isolation-test
  (let [rt-a (spi/create-lifecycle-registry-runtime)
        rt-b (spi/create-lifecycle-registry-runtime)
        lc {:on-activate (fn [_ _ _] nil)}]
    (spi/call-with-lifecycle-registry-runtime rt-a
      (fn []
        (spi/register-lifecycle! :my-skill lc)))
    (spi/call-with-lifecycle-registry-runtime rt-b
      (fn []
        (is (false? (spi/lifecycle-registered? :my-skill)))))
    (spi/call-with-lifecycle-registry-runtime rt-a
      (fn []
        (is (true? (spi/lifecycle-registered? :my-skill)))))))
