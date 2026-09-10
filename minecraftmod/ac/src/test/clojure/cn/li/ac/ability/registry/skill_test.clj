(ns cn.li.ac.ability.registry.skill-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.domain.developer :as developer]
            [cn.li.ac.ability.registry.skill :as sk]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.rules.progression :as progression]))

(defn- reset-skills! [f]
  (let [saved (sk/skill-registry-snapshot)]
    (try
      (sk/reset-skill-registry-for-test!)
      (f)
      (finally
        ;; Restore so tests that rely on skill content registered at require-time
        ;; (e.g. content skill specs) don't see an empty registry just because
        ;; this namespace ran earlier in the same JVM.
        (sk/reset-skill-registry-for-test! saved)))))

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
  (is (= 2 (count (skill-query/get-skills-for-category :cat-a))))
  (is (every? #{:s-a :s-b} (set (map :id (skill-query/get-skills-for-category :cat-a))))))

(deftest get-skill-by-controllable-test
  (sk/register-skill! (minimal-skill :mine :electromaster :arc-gen))
  (is (= :mine (skill-query/get-skill-by-controllable :electromaster :arc-gen))))

(deftest get-skill-by-controllable-definitions-fallback-test
  "Picker synthesizes from skill-definitions when registry is empty; bind must
   still resolve electromaster/arc-gen without a live registry entry."
  (is (= :arc-gen (skill-query/get-skill-by-controllable :electromaster :arc-gen)))
  (is (nil? (skill-query/get-skill-by-controllable :migrated :arc-gen))))

(deftest get-skill-by-controllable-migrated-registry-category-test
  "Registry may still carry EDN :migrated; definitions category wins."
  (sk/register-skill! (minimal-skill :arc-gen :migrated :arc-gen :level 1))
  (is (= :arc-gen (skill-query/get-skill-by-controllable :electromaster :arc-gen)))
  (is (= [:electromaster :arc-gen] (skill-query/controllable-key :arc-gen))))

(deftest install-empty-skill-runtime-does-not-clobber-test
  (sk/register-skill! (minimal-skill :keep :electromaster :keep :level 1))
  (sk/install-skill-registry-runtime! (sk/create-skill-registry-runtime))
  (is (= :keep (:id (sk/raw-skill :keep)))))

(deftest controllable-and-icon-test
  (sk/register-skill! (-> (minimal-skill :x :c :x)
                          (assoc :enabled false)))
  (is (false? (skill-query/can-control? :x)))
  (sk/register-skill! (assoc (minimal-skill :y :c :y) :enabled true :controllable? false))
  (is (false? (skill-query/can-control? :y)))
  (sk/register-skill! (assoc (minimal-skill :z :c :z2) :icon "path/to/icon.png"))
  ;; bare paths are resolved against the mod namespace
  (is (= "academy:path/to/icon.png" (skill-query/get-skill-icon-path :z)))
  ;; EDN without :icon still resolves via category/skills/<stem>.png convention
  (sk/register-skill! (dissoc (minimal-skill :railgun :electromaster :railgun) :icon))
  (is (= "academy:textures/abilities/electromaster/skills/railgun.png"
         (skill-query/get-skill-icon-path :railgun)))
  (sk/register-skill! (dissoc (minimal-skill :shift-teleport :teleporter :shift-teleport) :icon))
  (is (= "academy:textures/abilities/teleporter/skills/shift_tp.png"
         (skill-query/get-skill-icon-path :shift-teleport)))
  (sk/register-skill! (dissoc (minimal-skill :electromaster/brain-course :electromaster :brain-course
                                             :level 3)
                              :icon))
  (is (= "academy:textures/abilities/generic/skills/brain_course.png"
         (skill-query/get-skill-icon-path :electromaster/brain-course))))

(deftest learning-cost-and-developer-type-test
  (is (= 5.0 (progression/learning-cost 2))) ;; 3 + 2²×0.5
  (is (true? (developer/gte? :advanced :normal)))
  (is (false? (developer/gte? :portable :advanced))))

(deftest skill-registry-duplicate-and-freeze-policy-test
  (let [spec (minimal-skill :dup :cat-a :ctrl-a)]
    (sk/register-skill! spec)
    (is (= :dup (:id (sk/register-skill! spec))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Conflicting skill id"
                          (sk/register-skill! (minimal-skill :dup :cat-b :ctrl-a))))
    (sk/freeze-skill-registry!)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Skill registry is frozen"
                          (sk/register-skill! (minimal-skill :new-skill :cat-a :ctrl-b))))))



