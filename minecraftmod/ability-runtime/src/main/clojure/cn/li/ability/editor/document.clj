(ns cn.li.ability.editor.document
  "Lifecycle operations for the documents the node editor opens.

   One kind now. A skill and a VFX effect are both surface DSL -- the same
   grammar, differing only in which vocabulary their calls come from -- so
   there is no document-shape branch left here, and nothing structural to
   validate on open: the rules the graph form needed were about wires, and
   there are none. Everything else arrives as a compile diagnostic."
  (:require [cn.li.node.api :as node-api]))

(def ^:private editable-schemas #{:ac/skill-v4 :ac/vfx-v4})

(defn editable? [value] (contains? editable-schemas (:schema value)))

(defn open
  "Raw file text -> an editor document.

   Read as EDN with tagged literals rejected rather than with read-string:
   content is data. The ^{:nid} stamps are ordinary reader metadata and
   survive it -- they have to, since the editor anchors selection and
   diagnostics on them."
  [raw-text]
  (let [d (node-api/read-surface-document raw-text)]
    (when-not (editable? d)
      (throw (ex-info "node editor cannot open this document" {:schema (:schema d)})))
    {:form d :history [] :future [] :dirty? false :file-text raw-text}))

(defn edit [document new-form]
  (-> document
      (update :history conj (:form document))
      (assoc :form new-form :future [] :dirty? true)))

(defn undo [{:keys [history] :as document}]
  (if (empty? history)
    document
    (-> document
        (update :future conj (:form document))
        (assoc :form (peek history) :dirty? true)
        (update :history pop))))

(defn redo [{:keys [future] :as document}]
  (if (empty? future)
    document
    (-> document
        (update :history conj (:form document))
        (assoc :form (peek future) :dirty? true)
        (update :future pop))))

(defn save [document _print-fn]
  (when-not (editable? (:form document))
    (throw (ex-info "editor save requires a skill or vfx document"
                    {:schema (:schema (:form document))})))
  (assoc document
         ;; write-surface-document, not pr-str: it is pretty-printed and
         ;; carries the ^{:nid} stamps, and read-surface-document reads its
         ;; output back identically. A save that is not read-back-identical
         ;; silently rewrites content.
         :file-text (node-api/write-surface-document (:form document))
         :dirty? false))
