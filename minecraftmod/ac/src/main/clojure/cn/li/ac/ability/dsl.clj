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
            [cn.li.ac.ability.skill :as sk]))

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

(defn- normalize-prereqs
  [value]
  (cond
    (map? value) (mapv (fn [[skill-id min-exp]] {:skill-id skill-id :min-exp (double min-exp)}) value)
    (vector? value) value
    :else []))

(defn- normalize-skill-spec
  [sym kv-map]
  (let [id (get kv-map :id (keyword (name sym)))
        kv-map* (-> kv-map
                    (dissoc :id)
                    (update :category-id #(or % (:category kv-map)))
                    (dissoc :category)
                    (update :prerequisites #(normalize-prereqs (or % (:prereqs kv-map))))
                    (dissoc :prereqs))]
    (assoc kv-map* :id id)))

(defmacro defskill
  "Declare a skill map without registration."
  [sym & opts]
  (let [kv-map (apply hash-map opts)
        skill-map (normalize-skill-spec sym kv-map)]
    `(let [skill-map# (assoc ~skill-map :ac/content-type :skill)]
       (def ~sym skill-map#))))

(defmacro defskill!
  "Declare and immediately register a skill.
  Supports :category alias for :category-id and :prereqs map sugar."
  [sym & opts]
  (let [kv-map (apply hash-map opts)
        skill-map (normalize-skill-spec sym kv-map)]
    `(let [skill-map# ~skill-map
           registered# (sk/register-skill! skill-map#)]
       (def ~sym registered#))))
