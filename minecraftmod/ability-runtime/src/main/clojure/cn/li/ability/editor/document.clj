(ns cn.li.ability.editor.document
  "Lifecycle operations for one structured AC V3 document.

   The editor persists the complete V3 map. There is deliberately no legacy
   :program/:scene string reader or byte-splice path: every asset opened by
   the production editor is validated before it enters the graph model and
   every save reconstructs the same structured document contract."
  (:require [cn.li.ability.editor.v3 :as v3]))

(defn v3-document? [value] (v3/v3-document? value))

(defn open-v3
  "Parse and validate one structured AC V3 document for editor use."
  [raw-text]
  (v3/open raw-text))

(defn edit [document new-form]
  (-> document
      (update :history conj (:form document))
      (assoc :form new-form :future [] :dirty? true)))

(defn undo [{:keys [history] :as document}]
  (if (empty? history)
    document
    (-> document (update :future conj (:form document))
        (assoc :form (peek history)) (update :history pop) (assoc :dirty? true))))

(defn redo [{:keys [future] :as document}]
  (if (empty? future)
    document
    (-> document (update :history conj (:form document))
        (assoc :form (peek future)) (update :future pop) (assoc :dirty? true))))

(defn save
  "Rebuild and serialize a structured V3 document; print-fn is ignored."
  [document _print-fn]
  (when-not (:v3? document)
    (throw (ex-info "editor save requires a structured V3 document" {})))
  (let [new-v3 (if (:dirty? document)
                 (v3/form->document (:v3-document document) (:form document))
                 (:v3-document document))]
    (assoc document :v3-document new-v3 :file-text (pr-str new-v3) :dirty? false)))