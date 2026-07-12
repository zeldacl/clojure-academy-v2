(ns cn.li.mcmod.i18n-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mcmod.i18n :as i18n]))

(deftest translate-default-and-overridden-test
  (testing "default translate uses key string"
    (binding [i18n/*translate-fn* (fn [k _args] (str k))]
      (is (= "my_mod.key" (i18n/translate "my_mod.key")))
      (is (= ":kw" (i18n/translate :kw)))))
  (testing "binding custom translator changes behavior"
    (binding [i18n/*translate-fn* (fn [k _args] (str "T(" k ")"))]
      (is (= "T(my_mod.ability.level_up)"
             (i18n/translate "my_mod.ability.level_up")))))
  (testing "extra args are passed to the translator"
    (binding [i18n/*translate-fn* (fn [k args] (apply str k "|" args))]
      (is (= "k|2000" (i18n/translate "k" "2000"))))))

