(ns cn.li.mcmod.entity.hook-resolver
  "Platform-neutral scripted entity hook resolver registry.

  Business/content modules register hook-id → impl-key resolver functions at
  runtime. Shared Minecraft code consumes this namespace without depending on
  the business module that owns the hook ids.")

(defonce ^:private resolvers
  (atom {}))

(defn register-resolver!
  "Register a resolver for `resolver-key`.

  `resolver-fn` receives a hook-id and returns a platform-neutral impl-key
  keyword, or nil when the hook-id is unknown. Registering the same key again
  replaces the previous resolver, making initialization idempotent."
  [resolver-key resolver-fn]
  (when-not (keyword? resolver-key)
    (throw (ex-info "Hook resolver key must be a keyword"
                    {:resolver-key resolver-key})))
  (when-not (fn? resolver-fn)
    (throw (ex-info "Hook resolver must be a function"
                    {:resolver-key resolver-key})))
  (swap! resolvers assoc resolver-key resolver-fn)
  nil)

(defn get-resolver
  "Return the registered resolver function for `resolver-key`, or nil."
  [resolver-key]
  (get @resolvers resolver-key))

(defn resolve-impl-key
  "Resolve a business hook-id through the registered resolver.

  Returns nil when no resolver is installed or the resolver does not recognize
  the hook-id."
  [resolver-key hook-id]
  (when-let [resolver (get-resolver resolver-key)]
    (resolver hook-id)))

(defn clear-resolvers!
  "Clear all registered resolvers. Intended for tests."
  []
  (reset! resolvers {})
  nil)
