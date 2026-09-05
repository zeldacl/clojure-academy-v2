(ns cn.li.ability.editor.document
  "Open/edit/undo/redo/save for one content file (an ac/skills/*.edn or
   ac/vfx/fx/*.edn wrapper map). Save is deliberately SURGICAL
   (splice-program): it replaces only the :program/:scene string value's
   BYTES in the original file text, leaving every other byte -- the
   wrapper's other fields, and crucially every :doc entry that used to be
   a raw ;; comment (see the node-editor plan's Phase 1 migration) --
   untouched. This is what makes the editor's save lossless without
   needing a full-fidelity printer for the WRAPPER map, only for the DSL
   string inside it."
  (:require [clojure.string :as str]
            [cn.li.node.surface :as surface]))

;; --- quote-aware text scanning ----------------------------------------

(defn- skip-string
  "text, i (index of the opening '\"') -> index just past the matching
   closing '\"', honoring backslash escapes."
  ^long [^String text ^long i]
  (let [n (count text)]
    (loop [j (unchecked-inc i)]
      (cond
        (>= j n) (throw (ex-info "unterminated string literal" {:at i}))
        (= \\ (.charAt text j)) (recur (unchecked-add j 2))
        (= \" (.charAt text j)) (unchecked-inc j)
        :else (recur (unchecked-inc j))))))

(defn- key-token [field] (str ":" (name field)))

(defn string-value-span
  "raw-text, field (keyword, :program or :scene) -> [start end] character
   indices spanning the STRING VALUE's bytes, quotes EXCLUDED, for
   field's value in raw-text -- quote-aware: only recognizes the key
   token when it appears OUTSIDE any string literal, so a :doc sentence
   that happens to mention \":program\" as prose can never be mistaken
   for the real key. Throws if the key is not found, or if its value is
   not immediately a string literal (skipping only intervening
   whitespace)."
  [^String raw-text field]
  (let [token (key-token field)
        tlen (long (count token))
        n (long (count raw-text))]
    (loop [i 0]
      (when (>= i n) (throw (ex-info "field not found in document text" {:field field})))
      (let [c (.charAt raw-text i)]
        (cond
          (= \" c)
          (recur (skip-string raw-text i))

          (and (= \: c)
               (<= (+ i tlen) n)
               (= token (subs raw-text i (+ i tlen)))
               ;; must be a whole token, not a prefix of a longer key
               ;; (:program vs :programs) -- next char must not be a
               ;; keyword-constituent character.
               (or (= (+ i tlen) n)
                   (not (Character/isLetterOrDigit (.charAt raw-text (+ i tlen))))
                   (= \? (.charAt raw-text (+ i tlen)))))
          (let [after-token (+ i tlen)
                ws-end (long (loop [j after-token]
                                (if (and (< j n) (Character/isWhitespace (.charAt raw-text j)))
                                  (recur (unchecked-inc j))
                                  j)))]
            (if (and (< ws-end n) (= \" (.charAt raw-text ws-end)))
              [(inc ws-end) (dec (skip-string raw-text ws-end))]
              (throw (ex-info "field's value is not a string literal" {:field field}))))

          :else (recur (unchecked-inc i)))))))

(defn- unescape-edn-string [s]
  (-> s (str/replace "\\\"" "\"") (str/replace "\\\\" "\\")))

(defn- escape-edn-string [s]
  (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")))

(defn splice-program
  "raw-text, field, new-dsl-text -> raw-text with field's string value
   replaced by new-dsl-text, every other byte untouched."
  [raw-text field new-dsl-text]
  (let [[start end] (string-value-span raw-text field)]
    (str (subs raw-text 0 start) (escape-edn-string new-dsl-text) (subs raw-text end))))

;; --- document lifecycle --------------------------------------------------

(defn open
  "raw-text, field -> a fresh document: {:file-text :field :form :layout
   :history :future :dirty?}. :form is the PARSED, un-normalized DSL doc
   (cn.li.node.surface/read-doc's output) -- the editor operates on this
   shape (graph.clj's form<->graph), never on compiled IR."
  [raw-text field]
  (let [[start end] (string-value-span raw-text field)
        dsl-text (unescape-edn-string (subs raw-text start end))]
    {:file-text raw-text
     :field field
     :form (surface/read-doc dsl-text)
     :layout {}
     :history []
     :future []
     :dirty? false}))

(defn edit
  "document, new-form -> document with :form replaced, the OLD form
   pushed onto :history, :future cleared (a fresh edit invalidates any
   redo stack), :dirty? set. The whole form is snapshotted per edit
   (not a diff) -- simplest correct approach at this document size (one
   ability/effect's DSL, never more than a few hundred forms), matching
   this codebase's own 'ship the simple case, do not build a generalized
   mechanism speculatively' convention."
  [document new-form]
  (-> document
      (update :history conj (:form document))
      (assoc :form new-form :future [] :dirty? true)))

(defn undo [{:keys [history] :as document}]
  (if (empty? history)
    document
    (-> document
        (update :future conj (:form document))
        (assoc :form (peek history))
        (update :history pop)
        (assoc :dirty? true))))

(defn redo [{:keys [future] :as document}]
  (if (empty? future)
    document
    (-> document
        (update :history conj (:form document))
        (assoc :form (peek future))
        (update :future pop)
        (assoc :dirty? true))))

(defn save
  "document, print-fn ((fn [form] dsl-text), cn.li.ability.editor.graph's
   printer once Phase 2's graph.clj exists) -> a NEW document whose
   :file-text has :form printed back through splice-program and :dirty?
   cleared. Does not write to disk -- callers own the actual file I/O
   (an ac screen writes to the runtime workspace by default, an explicit
   export action writes to the source tree; see the node-editor plan's
   Phase 3 save-strategy note)."
  [{:keys [field form] :as document} print-fn]
  (let [new-text (splice-program (:file-text document) field (print-fn form))]
    (assoc document :file-text new-text :dirty? false)))
