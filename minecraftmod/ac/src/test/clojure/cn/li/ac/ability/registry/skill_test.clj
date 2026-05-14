(ns cn.li.ac.ability.registry.skill-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.ability.service.registry :as sk]))

(defn- reset-skills! [f]
  (let [saved @sk/skill-registry]
    (try
      (reset! sk/skill-registry {})
      (f)
      (finally
        ;; Restore so tests that rely on skill content registered at require-time
        ;; (e.g. content skill specs) don't see an empty registry just because
        ;; this namespace ran earlier in the same JVM.
        (reset! sk/skill-registry saved)))))

(use-fixtures :each reset-skills!)

(defn- minimal-skill [id cat ctrl & {:keys [level] :or {level 1}}]
  {:id id
   :category-id cat
   :level level
   :name-key (name id)
   :description-key (str (name id) "-d")
   :icon "icon"
   :ctrl-id ctrl
   :pattern :instant
   :actions {:perform! (fn [_] nil)}})

(deftest register-get-and-category-query-test
  (sk/register-skill! (minimal-skill :s-a :cat-a :c-a))
  (sk/register-skill! (minimal-skill :s-b :cat-a :c-b))
  (sk/register-skill! (minimal-skill :s-c :cat-b :c-c))
  (is (= :cat-a (:category-id (sk/get-skill :s-a))))
  (is (= 2 (count (sk/get-skills-for-category :cat-a))))
  (is (every? #{:s-a :s-b} (set (map :id (sk/get-skills-for-category :cat-a))))))

(deftest get-skill-by-controllable-test
  (sk/register-skill! (minimal-skill :mine :electromaster :arc-gen))
  (is (= :mine (sk/get-skill-by-controllable :electromaster :arc-gen))))

(deftest controllable-and-icon-test
  (sk/register-skill! (-> (minimal-skill :x :c :x)
                          (assoc :enabled false)))
  (is (false? (sk/can-control? :x)))
  (sk/register-skill! (assoc (minimal-skill :y :c :y) :enabled true :controllable? false))
  (is (false? (sk/can-control? :y)))
  (sk/register-skill! (assoc (minimal-skill :z :c :z2) :icon "path/to/icon.png"))
  (is (= "path/to/icon.png" (sk/get-skill-icon-path :z))))

(deftest learning-cost-and-developer-type-test
  (is (= 5.0 (sk/learning-cost 2))) ;; 3 + 2²×0.5
  (is (true? (sk/developer-type-gte? :advanced :normal)))
  (is (false? (sk/developer-type-gte? :portable :advanced))))
