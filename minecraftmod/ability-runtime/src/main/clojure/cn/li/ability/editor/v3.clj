(ns cn.li.ability.editor.v3
  "Bidirectional adapter between the persisted AC V3 document contract and
   the editor's surface-form graph model.

   The runtime and the editor deliberately meet at the V3 document boundary:
   the editor may use the existing graph widgets and surface compiler, but it
   never has to persist a legacy :program/:scene string.  Lowering is only a
   view operation; save reconstructs explicit V3 nodes with stable :nid
   values, so a document remains addressable by a visual editor after a
   round-trip."
  (:require [clojure.string :as str]
            [cn.li.node.document :as node-document]
            [cn.li.node.document-compile :as document-compile]
            [cn.li.node.surface :as surface]))

(def v3-schemas #{:ac/skill-v3 :ac/vfx-v3})

(defn v3-document?
  "Return true when value is one of the editor-backed AC V3 documents."
  [value]
  (and (map? value) (contains? v3-schemas (:schema value))))

(defn- phase-map
  [document]
  (case (:schema document)
    :ac/skill-v3 (into {} (map (fn [[entry spec]] [entry (:do spec)]))
                       (:entries document))
    :ac/vfx-v3 (get document :system {})
    (throw (ex-info "unsupported V3 editor document" {:schema (:schema document)}))))

(defn- normalize-nid
  "Convert the metadata emitted by document-compile's lowering pass back to
   the persisted keyword form required by the V3 validator."
  [raw fallback]
  (cond
    (and (keyword? raw) (= "n" (namespace raw))) raw
    (keyword? raw) (keyword "n" (name raw))
    (string? raw) (let [raw (if (str/starts-with? raw "n/")
                              (subs raw 2)
                              raw)]
                    (keyword "n" raw))
    :else (keyword "n" (str "editor-" fallback))))

(defn- form-nid
  [form counter*]
  (normalize-nid (:nid (meta form)) (swap! counter* inc)))

(defn- parse-keyword
  [s]
  (let [s (str s)
        slash (str/index-of s "/")]
    (if slash
      (keyword (subs s 0 slash) (subs s (inc slash)))
      (keyword s))))

(defn- ref-form
  [form]
  (let [s (str form)]
    (cond
      (str/starts-with? s "$") [:parameter (parse-keyword (subs s 1))]
      (str/starts-with? s "?") [:context (parse-keyword (subs s 1))]
      (str/starts-with? s "%") [:state (parse-keyword (subs s 1))]
      :else [:local (parse-keyword s)])))

(declare form->expr)

(defn- form->expr
  [form counter*]
  (cond
    (symbol? form)
    {:nid (form-nid form counter*) :ref (ref-form form)}

    (and (seq? form) (keyword? (first form)))
    {:nid (form-nid form counter*)
     :component :value/field
     :inputs {:field (first form)
              :value (form->expr (second form) counter*)}}

    (seq? form)
    (let [head (first form)
          component (if (symbol? head)
                      (parse-keyword head)
                      (throw (ex-info "V3 editor expression head must be a symbol"
                                      {:form form})))]
      (if (map? (second form))
        {:nid (form-nid form counter*)
         :component component
         :inputs (into {}
                       (map (fn [[key value]] [key (form->expr value counter*)]))
                       (second form))}
        {:nid (form-nid form counter*)
         :component component
         :args (mapv #(form->expr % counter*) (rest form))}))

    (map? form)
    (into {} (map (fn [[key value]] [key (form->expr value counter*)])) form)

    (vector? form)
    (mapv #(form->expr % counter*) form)

    :else form))

(declare form->stmt)

(defn- form->stmt
  [form counter*]
  (let [nid (form-nid form counter*)]
    (when-not (seq? form)
      (throw (ex-info "V3 editor statement must be a list" {:form form})))
    (let [head (first form)]
      (cond
        (= (str head) "let")
        {:nid nid :flow :bind :name (parse-keyword (second form))
         :value (form->expr (nth form 2) counter*)}

        (= (str head) "when")
        {:nid nid :flow :when
         :condition (form->expr (second form) counter*)
         :do (mapv #(form->stmt % counter*) (drop 2 form))}

        (= (str head) "each")
        (let [binding (second form)
              [item index] (if (vector? binding) binding [binding nil])]
          {:nid nid :flow :foreach
           :as (parse-keyword item)
           :index-as (when index (parse-keyword index))
           :collection (form->expr (nth form 2) counter*)
           ;; The original surface DSL has no limit slot.  256 is the same
           ;; bounded default used by the generated V3 corpus and keeps the
           ;; persisted contract statically safe after an editor round-trip.
           :limit 256
           :do (mapv #(form->stmt % counter*) (drop 3 form))})

        (= (str head) "if")
        (do
          (let [then-form (nth form 2 nil)
                else-form (nth form 3 nil)]
            (when-not (vector? then-form)
              (throw (ex-info "V3 editor if arm is not a statement body"
                              {:if-form form :head head :head-key (str head) :value then-form
                               :class (class then-form)})))
            (when-not (vector? else-form)
              (throw (ex-info "V3 editor if arm is not a statement body"
                              {:if-form form :head head :head-key (str head) :value else-form
                               :class (class else-form)})))
            {:nid nid :flow :if
             :condition (form->expr (second form) counter*)
             :then (mapv #(form->stmt % counter*) then-form)
             :else (mapv #(form->stmt % counter*) else-form)}))

        (= (str head) "finish")
        {:nid nid :flow :finish
         :result (form->expr (second form) counter*)}

        (= (str head) "state!")
        {:nid nid :component :state!
         :args [(form->expr (second form) counter*)
                (form->expr (nth form 2) counter*)]}

        (= (str head) "set!")
        {:nid nid :component :set!
         :args [(form->expr (second form) counter*)
                (form->expr (nth form 2) counter*)]}

        (= (str head) "event!")
        {:nid nid :component :event!
         :inputs (into {}
                       (map (fn [[key value]]
                              [key (if (= key :type)
                                     value
                                     (form->expr value counter*))]))
                       (second form))}

        (= (str head) "vfx!")
        {:nid nid :component :vfx!
         :inputs (into {}
                       (map (fn [[key value]]
                              [key (if (= key :effect-id)
                                     value
                                     (form->expr value counter*))]))
                       (second form))}

        :else
        ;; Bare query/action statements are already component-shaped in the
        ;; surface language.  Reuse the expression adapter and make sure a
        ;; malformed literal cannot silently enter a persisted V3 body.
        (let [node (form->expr form counter*)]
          (when-not (:component node)
            (throw (ex-info "V3 editor statement is not a component"
                            {:form form})))
           node)))))

(defn document->form
  "Lower a validated V3 document into the editor's phase map:
   `{:phases {phase [surface statements]}}`."
  [document]
  (node-document/validate-document! document)
  (let [phases (into {}
                     (map (fn [[phase nodes]]
                            [phase (document-compile/lower-stmts nodes)]))
                     (phase-map document))]
    ;; Keep the editor's phase map while also presenting the normal surface
    ;; document header expected by node-core's diagnostics compiler.
    {:ability (:id document)
     :activation (get-in document [:activation :mode])
     :tunables (or (:parameters document) {})
     :state (or (:state document) {})
     :phases phases}))

(defn- form-phases
  [form]
  (or (:phases form)
      (throw (ex-info "V3 editor form requires :phases" {:form form}))))

(defn form->document
  "Rebuild the V3 document while preserving non-executable metadata and
   replacing only the skill entries or VFX render stages represented by the
   editor's phase map."
  [document form]
  (node-document/validate-document! document)
  (let [counter* (atom 0)
        phases (form-phases form)
        result (case (:schema document)
                 :ac/skill-v3
                 (update document :entries
                         (fn [entries]
                           (into {}
                                 (map (fn [[entry spec]]
                                        [entry (assoc spec :do
                                                           (mapv #(form->stmt % counter*)
                                                                 (get phases entry [])))]))
                                 entries)))

                 :ac/vfx-v3
                 (update-in document [:system :render]
                            (fn [stages]
                              (into {}
                                    (map (fn [[stage nodes]]
                                           [stage (mapv #(form->stmt % counter*)
                                                        (get phases stage []))]))
                                    stages)))

                 (throw (ex-info "unsupported V3 editor document"
                                 {:schema (:schema document)})))]
    (node-document/validate-document! result)
    result))

(defn open
  "Parse and validate a raw V3 EDN document for editor use."
  [raw-text]
  (let [document (surface/read-doc raw-text)]
    (node-document/validate-document! document)
    {:v3? true
     :v3-document document
     :file-text raw-text
     :form (document->form document)
     :history []
     :future []
     :dirty? false}))
