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

  Both macros resolve to register-category! / register-skill! calls wrapped
  in a defonce-guarded init so reloading is safe."
  (:require [cn.li.ac.ability.category :as cat]
            [cn.li.ac.ability.skill    :as sk]))

;; ============================================================================
;; defcategory
;; ============================================================================

(defmacro defcategory
  "Declare and register an ability category.
  Keys: :id :name-key :enabled? :level-matcher :prog-incr-rate :metadata"
  [sym & opts]
  (let [kv-map    (apply hash-map opts)
        id        (get kv-map :id (keyword (name sym)))
        rest-map  (dissoc kv-map :id)]
    `(let [category-map# (assoc ~rest-map :id ~id :ac/content-type :category)]
       (def ~sym category-map#))))

;; ============================================================================
;; defskill
;; ============================================================================

(defmacro defskill
  "Declare and register a skill.
  Keys: :id :category :name-key :max-level :can-control? :conditions
        :min-developer-type :can-break-blocks :metadata"
  [sym & opts]
  (let [kv-map  (apply hash-map opts)
        id      (get kv-map :id (keyword (name sym)))
        rest-map (dissoc kv-map :id)]
    `(let [skill-map# (assoc ~rest-map :id ~id :ac/content-type :skill)]
       (def ~sym skill-map#))))
