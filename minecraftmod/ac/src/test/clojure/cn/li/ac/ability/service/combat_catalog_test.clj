(ns cn.li.ac.ability.service.combat-catalog-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.service.combat-catalog :as combat-catalog]))

(deftest arc-gen-is-available-after-initialize
  (combat-catalog/initialize!)
  (is (= :ready (:status (combat-catalog/catalog))))
  (is (pos? (count (get-in (combat-catalog/catalog) [:combat :abilities]))))
  (is (true? (combat-catalog/available? :arc-gen)))
  (is (true? (combat-catalog/available? :arc_gen))
      "underscore alias must not empty the client cast gate"))
