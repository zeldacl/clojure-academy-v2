(ns cn.li.ability.editor.document
  "Lifecycle operations for the documents the node editor opens.

   Two kinds, and the difference is a migration boundary rather than a
   design: a skill is persisted as surface DSL, a VFX effect is still
   persisted as a node/wire graph. :v4? marks the graph kind. When VFX
   content moves to surface the flag, both branches here, and node-core's
   whole graph-document layer go together."
  (:require [cn.li.node.api :as node-api]))

(defn v4-document? [value] (contains? #{:ac/skill-v4 :ac/vfx-v4} (:schema value)))

(defn open
  "Raw file text -> an editor document.

   Read as EDN with tagged literals rejected, for both kinds, rather than
   with read-string: content is data. The ^{:nid} stamps a skill carries
   are ordinary reader metadata and survive it -- they have to, since the
   editor anchors selection and diagnostics on them."
  [raw-text]
  (let [d (node-api/read-surface-document raw-text)
        base {:form d :history [] :future [] :dirty? false :file-text raw-text}]
    (case (:schema d)
      ;; A skill is surface DSL. Nothing to structurally validate on open:
      ;; the rules the graph form needed were about wires, and there are
      ;; none. Everything else arrives as a compile diagnostic.
      :ac/skill-v4 (assoc base :v4? false)
      :ac/vfx-v4 (do (node-api/validate-v4-document! d)
                     (assoc base :v4? true :v4-document d))
      (throw (ex-info "node editor cannot open this document"
                      {:schema (:schema d)})))))

(defn edit [document new-form]
  (cond-> (-> document
              (update :history conj (:form document))
              (assoc :form new-form :future [] :dirty? true))
    (:v4? document) (assoc :v4-document new-form)))

(defn undo [{:keys [history] :as document}]
  (if (empty? history) document (-> document (update :future conj (:form document)) (assoc :form (peek history)) (cond-> (:v4? document) (assoc :v4-document (peek history))) (update :history pop) (assoc :dirty? true))))
(defn redo [{:keys [future] :as document}]
  (if (empty? future) document (-> document (update :history conj (:form document)) (assoc :form (peek future)) (cond-> (:v4? document) (assoc :v4-document (peek future))) (update :future pop) (assoc :dirty? true))))

(defn save [document _print-fn]
  (when-not (v4-document? (:form document))
    (throw (ex-info "editor save requires a skill or vfx document"
                    {:schema (:schema (:form document))})))
  (when (:v4? document)
    (node-api/validate-v4-document! (:form document)))
  (-> document
      ;; write-surface-document for both: it is pretty-printed and carries
      ;; metadata, and pr-str is neither. A VFX graph has no ^{:nid} stamps
      ;; to lose, but it does have the same reason to stay reviewable.
      (assoc :file-text (node-api/write-surface-document (:form document))
             :dirty? false)
      (cond-> (:v4? document) (assoc :v4-document (:form document)))))
