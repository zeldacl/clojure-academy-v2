(ns cn.li.combat.dsl
  "Small data-first DSL used by skill authors and compiler tests.

   The DSL expands to immutable maps. It does not execute game logic."
  (:refer-clojure :exclude [sequence repeat]))

(defmacro defability [name definition]
  `(def ~(with-meta name {:private false})
     (let [value# ~definition]
       (cn.li.combat.registry/register-ability! value#)
       value#)))

(defn instant [] :instant)
(defn session [] :session)
(defn toggle [] :toggle)
(defn passive [] :passive)

(defn node [id options] (assoc options :op :node :node-id id))
(defn sequence [& steps] {:op :sequence :steps (vec steps)})
(defn repeat [count & steps] {:op :repeat :count count :steps (vec steps)})
(defn branch [predicate then else] {:op :branch :predicate predicate :then then :else else})
(defn require-hit [] {:op :require :predicate :hit})
(defn query [query-type options] (assoc options :op :query :query-type query-type))
(defn raycast [options] (query :raycast options))
(defn entity-query [options] (query :entities options))
(defn damage [options] (assoc options :op :damage))
(defn vfx [effect-id options] (assoc options :op :vfx :effect-id effect-id))
(defn world-effect [effect-type options] (assoc options :op :world-effect :effect-type effect-type))
(defn domain-event [event-type options] (assoc options :op :domain-event :event-type event-type))
(defn patch [entries] {:op :patch :entries (vec entries)})
