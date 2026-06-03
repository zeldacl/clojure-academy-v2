(ns cn.li.ac.block.machine.render-runtime
  "Shared client render runtime: dynamic binding, cache atoms, renderer init registration."
  (:require [cn.li.mcmod.util.log :as log]))

(defn create-render-runtime
  "Create a render runtime map from cache key -> initial value pairs."
  [cache-spec]
  (into {} (map (fn [[k v]] [k (atom v)]) cache-spec)))

(defn cache-atom
  [runtime cache-key]
  (get runtime cache-key))

(defn cache-snapshot
  [runtime cache-key]
  @(cache-atom runtime cache-key))

(defn clear-cache!
  [runtime cache-key]
  (reset! (cache-atom runtime cache-key) {})
  nil)

(defn reset-cache-for-test!
  ([runtime cache-key]
   (clear-cache! runtime cache-key))
  ([runtime cache-key value]
   (reset! (cache-atom runtime cache-key) value)
   nil))

(defn create-cache-runtime
  "Create installed render runtime with one or more cache keys.

  Returns `{:runtime map :current-fn fn :with-binding macro usage via binding}`.
  Callers typically:
  - (defonce ^:private installed (create-cache-runtime :fan-rot-cache))
  - (def ^:dynamic *rt* (:runtime installed))
  - (defn current-rt [] *rt*)"
  ([cache-key]
   (create-cache-runtime cache-key {}))
  ([cache-key initial-value]
   (let [runtime (create-render-runtime {cache-key initial-value})]
     {:runtime runtime
      :cache-key cache-key
      :snapshot #(cache-snapshot runtime cache-key)
      :clear! #(clear-cache! runtime cache-key)
      :reset-for-test! (fn
                         ([] (reset-cache-for-test! runtime cache-key))
                         ([v] (reset-cache-for-test! runtime cache-key v)))})))

(defn lazy-resource
  "Return a thunk that loads `resource` once under `lock` and stores in `var-sym`."
  [lock var-sym loader]
  (fn []
    (or (var-get var-sym)
        (locking lock
          (or (var-get var-sym)
              (let [v (loader)]
                (alter-var-root var-sym (constantly v))
                v))))))

(defmacro with-bound-runtime
  "Bind `runtime` to dynamic var `runtime-var-sym` for test isolation."
  [runtime-var-sym runtime & body]
  `(binding [~runtime-var-sym ~runtime]
     ~@body))

(defn register-client-renderer-init!
  [init-sym]
  (when-let [register-fn (try (requiring-resolve 'cn.li.mcmod.client.render.init/register-renderer-init-fn!)
                              (catch Exception _ nil))]
    (register-fn init-sym)
    (log/info "Registered client renderer init" init-sym)))
