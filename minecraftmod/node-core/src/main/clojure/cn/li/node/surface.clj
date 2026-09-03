(ns cn.li.node.surface
  "Reads the surface DSL and normalizes it to the shape cn.li.node.compile
   consumes. The DSL is plain EDN -- lists, vectors, maps, keywords, numbers,
   strings, symbols -- read with clojure.edn/read (never clojure.core/read,
   never eval): EDN's grammar already includes lists and symbols, so
   S-expression call forms like (v+ a b) read as ordinary data with no
   reader-macro or eval surface at all, unlike the full Clojure reader.

   Sigils, recognized on symbols only:
     $x   a tunable read           -> [:tunable :x]
     ?x   a capability/system read -> [:capability :x]
   Every other symbol is a local reference, resolved by the compiler against
   enclosing let/each/:params bindings. `?`/`$` are ordinary (non-macro)
   symbol-constituent characters in Clojure, so `?caster/eye`, `$range` etc.
   are unremarkable symbols to the reader; the sigil meaning is entirely a
   cn.li.node.surface/cn.li.node.compile convention on top of that.

   Position tracking: forms are read from a LineNumberingPushbackReader, so
   clojure.edn/read attaches :line/:column metadata to every collection and
   symbol it reads. cn.li.node.compile threads that metadata into
   diagnostics; when a form was constructed by the compiler itself (not read
   from source, e.g. after :defn inlining) the metadata is simply absent and
   diagnostics fall back to nil position -- degraded, not broken."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.io StringReader]
           [clojure.lang LineNumberingPushbackReader]))

(defn read-doc
  "Read exactly one top-level EDN map from `text`. Throws ex-info on
   trailing forms, on any EDN tag (default reader always rejects -- no
   tagged literals are part of this language), and on anything other than
   a map at the top level. clojure.edn's reader wraps an exception thrown
   from inside the :default tag handler in its own
   EdnReader$ReaderException (cause = the ex-info) instead of propagating
   it directly, so every failure path here is normalized through one
   try/catch -- the same pattern cn.li.mcmod.runtime.safe-edn's
   read-resource! already uses for the same reason."
  [text]
  (try
    (let [reader (LineNumberingPushbackReader. (StringReader. text))
          reject-tag (fn [tag value] (throw (ex-info "EDN tag is forbidden in DSL source" {:tag tag :value value})))
          opts {:eof ::eof :readers {} :default reject-tag}
          form (edn/read opts reader)
          trailing (edn/read opts reader)]
      (when (= form ::eof) (throw (ex-info "empty DSL document" {})))
      (when-not (= trailing ::eof) (throw (ex-info "multiple top-level forms in one DSL document" {})))
      (when-not (map? form) (throw (ex-info "DSL document must be a top-level map" {:form form})))
      form)
    (catch clojure.lang.ExceptionInfo e (throw e))
    (catch Throwable t
      (throw (ex-info "invalid DSL source" {:cause (ex-message t)} t)))))

(defn str->keyword
  "Build a (possibly namespaced) keyword from a plain string by splitting
   on the first \"/\", NOT via 1-arg clojure.core/keyword -- its treatment
   of an embedded \"/\" is an implementation detail this code should not
   depend on; splitting explicitly is unambiguous regardless."
  [s]
  (let [i (str/index-of s "/")]
    (if i (keyword (subs s 0 i) (subs s (inc i))) (keyword s))))

(defn sigil
  "Classify a DSL symbol: [:tunable kw] for $x, [:capability kw] for ?x,
   [:local sym] for everything else. Uses (str sym), NOT (name sym): for a
   namespaced symbol like ?caster/eye, (name ...) strips the namespace and
   returns just \"eye\" -- the leading sigil only ever appears in the
   namespace segment, so name-based detection silently misses every
   namespaced capability/tunable reference."
  [sym]
  (let [s (str sym)]
    (cond
      (str/starts-with? s "$") [:tunable (str->keyword (subs s 1))]
      (str/starts-with? s "?") [:capability (str->keyword (subs s 1))]
      :else [:local sym])))

(defn- entries-of [doc]
  (cond
    (contains? doc :phases) (:phases doc)
    (contains? doc :do) {:default (:do doc)}
    (contains? doc :defn) nil
    :else (throw (ex-info "DSL ability document needs :do or :phases" {:doc doc}))))

(defn normalize
  "Normalize a raw parsed doc (from read-doc) into the shape
   cn.li.node.compile/compile-program consumes:

     function doc -> {:kind :defn :id kw :params [{:name kw :type t} ...] :body [stmt ...]}
     ability doc  -> {:kind :ability :id kw :tunables {name {:type t}} :entries {phase-kw [stmt ...]}}"
  [doc]
  (cond
    (contains? doc :defn)
    {:kind :defn
     :id (:defn doc)
     :params (mapv (fn [{:keys [name type]}]
                     ;; :name is a SYMBOL, not a keyword: the :defn body
                     ;; references it as a bare local (`target`, matching
                     ;; every other local binding form -- let/each), so it
                     ;; must be the exact value cn.li.node.compile's locals
                     ;; map is keyed by.
                     (when-not (and (symbol? name) (keyword? type))
                       (throw (ex-info ":defn :params entries need a :name symbol and a :type keyword" {:doc doc})))
                     {:name name :type type})
                   (:params doc))
     :body (vec (:do doc))
     :meta (meta doc)}

    (contains? doc :ability)
    {:kind :ability
     :id (:ability doc)
     :activation (:activation doc)
     :tunables (into {} (map (fn [[k spec]] [k {:type (:type spec)}])) (:tunables doc))
     :entries (into {} (map (fn [[k stmts]] [k (vec stmts)])) (entries-of doc))
     :meta (meta doc)}

    :else
    (throw (ex-info "DSL document must declare :defn or :ability" {:doc doc}))))

(defn parse
  "read-doc + normalize in one step."
  [text]
  (normalize (read-doc text)))
