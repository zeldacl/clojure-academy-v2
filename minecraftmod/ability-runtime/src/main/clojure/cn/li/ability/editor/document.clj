(ns cn.li.ability.editor.document
  "Lifecycle operations for persisted V4 graph documents."
  (:require [cn.li.ability.editor.v3 :as v3] [cn.li.node.api :as node-api]))
(defn v4-document? [value] (contains? #{:ac/skill-v4 :ac/vfx-v4} (:schema value)))
(defn v3-document? [value] (v3/v3-document? value))
(defn open-v4 [raw-text]
  (let [d (binding [*read-eval* false] (read-string raw-text))]
    (node-api/validate-v4-document! d)
    {:v4? true :v4-document d :form d :history [] :future [] :dirty? false :file-text raw-text}))
(defn open-v3 [raw-text] (v3/open raw-text))
(defn edit [document new-form]
  (if (:v4? document)
    (-> document (update :history conj (:form document)) (assoc :form new-form :v4-document new-form :future [] :dirty? true))
    (-> document (update :history conj (:form document)) (assoc :form new-form :future [] :dirty? true))))
(defn undo [{:keys [history] :as document}]
  (if (empty? history) document (-> document (update :future conj (:form document)) (assoc :form (peek history)) (cond-> (:v4? document) (assoc :v4-document (peek history))) (update :history pop) (assoc :dirty? true))))
(defn redo [{:keys [future] :as document}]
  (if (empty? future) document (-> document (update :history conj (:form document)) (assoc :form (peek future)) (cond-> (:v4? document) (assoc :v4-document (peek future))) (update :future pop) (assoc :dirty? true))))
(defn save [document _print-fn]
  (if (:v4? document)
    (assoc document :v4-document (:form document) :file-text (pr-str (:form document)) :dirty? false)
    (do (when-not (:v3? document) (throw (ex-info "editor save requires structured document" {}))) (let [d (if (:dirty? document) (v3/form->document (:v3-document document) (:form document)) (:v3-document document))] (assoc document :v3-document d :file-text (pr-str d) :dirty? false)))))
