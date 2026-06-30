(ns cn.li.mcmod.schema.core
  "Pure-Clojure schema validation — zero external dependencies.

  Replaces Malli to avoid AOT classloader conflicts with dynaload/fipp transitive
  dependencies under Minecraft's shaded-jar / tree-shaken build environment.

  Keep this namespace platform-neutral. Callers should compile validators once
  near immutable schemas and call `explain` only on failure."
  (:require [clojure.string :as str]))

;; ============================================================================
;; Schema compilation
;; ============================================================================

(defn- ^:private resolve-sym
  "Resolve `x` if it is a symbol, otherwise return `x` unchanged."
  [x resolve-ns]
  (if (symbol? x)
    (let [v (ns-resolve resolve-ns x)]
      (if v
        @v
        (throw (IllegalArgumentException.
                (str "schema.core: unresolved symbol " x " in " (ns-name resolve-ns))))))
    x))

(defn- ^:private compile-schema*
  "Recursive schema compiler. Returns a predicate fn."
  [schema resolve-ns]
  (cond
    ;; predicate function
    (fn? schema)
    schema

    ;; symbol — resolve in the defining namespace
    (symbol? schema)
    (compile-schema* (resolve-sym schema resolve-ns) resolve-ns)

    ;; var — deref and compile
    (var? schema)
    (compile-schema* @schema resolve-ns)

    ;; bare keyword — treat as enum singleton (Malli compat)
    (keyword? schema)
    (fn [v] (= schema v))

    ;; structured schemas
    (vector? schema)
    (let [[tag & args] schema]
      (case tag
        :or
        (let [subs (mapv #(compile-schema* % resolve-ns) args)]
          (fn [v] (boolean (some #(% v) subs))))

        :and
        (let [subs (mapv #(compile-schema* % resolve-ns) args)]
          (fn [v] (boolean (every? #(% v) subs))))

        :enum
        (let [allowed (set args)]
          (fn [v] (contains? allowed v)))

        :map
        (let [entries args  ;; each element is [key opts? spec]
              compiled (mapv (fn [entry]
                               (let [k (first entry)
                                     tail (rest entry)
                                     [opts spec] (if (and (map? (first tail))
                                                          (contains? (first tail) :optional))
                                                   [(first tail) (second tail)]
                                                   [nil (first tail)])
                                     optional? (boolean (:optional opts))
                                     pred (compile-schema* spec resolve-ns)]
                                 [k optional? pred]))
                             entries)]
          (fn [v]
            (and (map? v)
                 (every? (fn [[k optional? pred]]
                           (if (contains? v k)
                             (pred (get v k))
                             optional?))
                         compiled))))

        :map-of
        (let [[k-spec v-spec] args
              k-pred (compile-schema* k-spec resolve-ns)
              v-pred (compile-schema* v-spec resolve-ns)]
          (fn [v]
            (and (map? v)
                 (every? (fn [[k val]]
                           (and (k-pred k) (v-pred val)))
                         v))))

        :set
        (let [item-pred (compile-schema* (first args) resolve-ns)]
          (fn [v] (and (set? v) (every? item-pred v))))

        :vector
        (let [item-pred (compile-schema* (first args) resolve-ns)]
          (fn [v] (and (vector? v) (every? item-pred v))))

        :sequential
        (let [item-pred (compile-schema* (first args) resolve-ns)]
          (fn [v] (and (sequential? v) (every? item-pred v))))

        :re
        (let [pat (first args)]
          (fn [v] (and (string? v) (boolean (re-matches pat v)))))

        :>=
        (let [n (first args)]
          (fn [v] (and (number? v) (>= v n))))

        :<=
        (let [n (first args)]
          (fn [v] (and (number? v) (<= v n))))

        :>
        (let [n (first args)]
          (fn [v] (and (number? v) (> v n))))

        :<
        (let [n (first args)]
          (fn [v] (and (number? v) (< v n))))

        :=
        (let [expected (first args)]
          (fn [v] (= expected v)))

        :fn
        (let [f (resolve-sym (first args) resolve-ns)]
          (if (fn? f) f (throw (IllegalArgumentException.
                                (str "schema.core: :fn requires a function, got " f)))))

        ;; unknown tag — fail fast
        (throw (IllegalArgumentException.
                (str "schema.core: unknown schema tag " tag)))))

    ;; bare map predicate
    (= schema map?)
    map?

    ;; any other value — identity predicate
    :else
    (fn [v] true)))

(defn validator
  "Compile a schema into a reusable predicate function.
  Supports :or, :and, :enum, :map, :map-of, :set, :vector, :sequential, :re, :fn,
  predicate functions, and nested schema references via symbols or vars."
  ([schema]
   (compile-schema* schema *ns*))
  ([schema resolve-ns]
   (compile-schema* schema resolve-ns)))

;; ============================================================================
;; Validation API
;; ============================================================================

(defn valid?
  "Return true when compiled validator accepts value."
  [compiled-validator value]
  (boolean (compiled-validator value)))

(defn explain
  "Return explanation data for a failed value.
  Pure-Clojure fallback — provides schema and value for debugging."
  [schema value]
  {:contract-violation true
   :expected (if (vector? schema) (first schema) (pr-str schema))
   :actual value})

(defn contract-ex-info
  [contract value explain-data]
  (ex-info (str contract " contract violation")
           {:contract contract
            :value value
            :explain explain-data}))

(defn require-valid
  "Validate value with a compiled validator and throw standardized ex-info on failure."
  [schema compiled-validator contract value]
  (if (valid? compiled-validator value)
    value
    (throw (contract-ex-info contract value (explain schema value)))))

;; ============================================================================
;; Lazy validator — AOT-safe deferred compilation
;; ============================================================================

(defn lazy-validator
  "Returns a 0-arg fn that compiles `schema` on first call and caches the result.

  Captures `*ns*` at definition time so that symbol resolution inside nested
  schemas works correctly even when called from a different namespace later.

  Uses `atom` for AOT-safe lazy caching. Do NOT use `delay` for this purpose —
  `delay` is a macro that expands to an anonymous class which, under AOT, leaks
  into downstream eval contexts and triggers `Can't take value of a macro`."
  [schema]
  (let [cache (atom nil)
        resolve-ns *ns*]
    (fn []
      (if-let [c @cache]
        c
        (let [v (validator schema resolve-ns)]
          (reset! cache v)
          v)))))
