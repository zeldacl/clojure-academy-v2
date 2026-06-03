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

(defn install-render-runtime!
  "Install a named render runtime into a namespace via dynamic var.

  Returns map:
  {:runtime-var sym
   :installed-var sym
   :create-fn (fn [initial-cache] -> runtime)
   :current-fn sym
   :with-macro sym
   :call-with-fn sym}"
  [{:keys [ns-sym runtime-sym installed-sym cache-keys]
    :or {cache-keys [:cache]}}]
  (let [create-sym (symbol (str (name ns-sym) "/create-" (name runtime-sym)))
        current-sym (symbol (str (name ns-sym) "/current-" (name runtime-sym)))
        with-sym (symbol (str (name ns-sym) "/with-" (name runtime-sym)))
        call-sym (symbol (str (name ns-sym) "/call-with-" (name runtime-sym)))]
    {:create-fn
     (fn create-render-runtime*
       ([] (create-render-runtime* {}))
       ([initial-cache]
        (create-render-runtime
          (into {}
                (for [k cache-keys]
                  [k (get initial-cache k {})])))))
     :cache-keys cache-keys
     :runtime-var runtime-sym
     :installed-var installed-sym
     :current-fn current-sym
     :with-macro with-sym
     :call-with-fn call-sym}))

(defn register-client-renderer-init!
  [init-sym]
  (when-let [register-fn (try (requiring-resolve 'cn.li.mcmod.client.render.init/register-renderer-init-fn!)
                              (catch Exception _ nil))]
    (register-fn init-sym)
    (log/info "Registered client renderer init" init-sym)))
