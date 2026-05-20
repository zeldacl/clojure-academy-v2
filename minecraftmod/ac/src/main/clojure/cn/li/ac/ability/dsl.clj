(ns cn.li.ac.ability.dsl
  "Usage:
    (defcategory electrokinesis
      :id       :electrokinesis
      :name-key \"category.electrokinesis\"
      :level-matcher (fn [level] :normal)
      :prog-incr-rate 1.0)

    (defskill thunderbolt
      :id         :thunderbolt
      :category   :electrokinesis
      :name-key   \"skill.thunderbolt\"
      :max-level  3
      :can-control? true
      :conditions [(constantly true)])

  Macros only declare data. Runtime registration is performed by explicit
  content bootstrap functions."
  (:require [cn.li.ac.ability.definition-core :as definition-core]))

;; ============================================================================
;; defcategory
;; ============================================================================

(defmacro defcategory
  "Declare and register an ability category.
  Keys: :id :name-key :enabled? :level-matcher :prog-incr-rate :metadata"
  [sym & opts]
  (let [category-spec (definition-core/build-category-spec sym (apply hash-map opts))
        id           (:id category-spec)
        rest-map     (dissoc category-spec :id)]
    `(let [category-map# (assoc ~rest-map :id ~id :ac/content-type :category)]
       (def ~sym category-map#))))

;; ============================================================================
;; defskill
;; ============================================================================

(defmacro defskill
  "Declare a skill map without registration."
  [sym & opts]
  (let [kv-map (apply hash-map opts)
        skill-map (definition-core/build-skill-spec sym kv-map)]
    `(let [skill-map# (assoc ~skill-map :ac/content-type :skill)]
       (def ~sym skill-map#))))

(defmacro defskill!
  "Declare a skill map without registration.

  Kept as a legacy spelling for existing content declarations; registration is
  performed by cn.li.ac.content.ability/init-ability-content!."
  [sym & opts]
  (let [kv-map (apply hash-map opts)
        skill-map (definition-core/build-skill-spec sym kv-map)]
    `(let [skill-map# (assoc ~skill-map :ac/content-type :skill)]
       (def ~sym skill-map#))))
