(ns cn.li.ac.ability.skills-catalog-v3-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.skills-catalog-v3 :as catalog]))

(deftest directory-catalog-compiles-one-document
  (let [{:keys [skills by-id resource-count]}
        (catalog/assemble {:resource-root "ac/skills-v3"})
        skill (get by-id :ac.test/catalog-smoke)]
    (is (= 1 resource-count))
    (is (= 1 (count skills)))
    (is (= :ac.test/catalog-smoke (:id skill)))
    (is (= {:activate :activation/start}
           (:entry-triggers (:ir skill))))
    (is (string? (:semantic-digest skill)))))

