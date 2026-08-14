(ns cn.li.presentation.core.render-ir
  "Painter-order Render IR helpers. Batching decisions belong to Clojure;
   Java only defines immutable command records.")

(defn command-list [] [])
(defn add [commands command]
  (conj (vec commands) command))
(defn add-all [commands new-commands]
  (into (vec commands) new-commands))
(defn freeze [commands]
  (vec commands))

(defn compatible?
  "Adjacent commands may be merged only when material/texture/clip/layer
   identity is equal. Backend implementations must not change this semantic."
  [a b]
  (and (= (type a) (type b))
       (= (:material-id a) (:material-id b))
       (= (:texture-id a) (:texture-id b))))
