(ns cn.li.ac.core
  (:require [cn.li.mcmod.lifecycle :as lifecycle]
            [cn.li.mcmod.spi.entity-render-registry :as entity-render-registry]
            [cn.li.ac.core.init :as core-init]
            [cn.li.ac.core.content-loader :as content-loader]
            [cn.li.ac.terminal.client.actions :as terminal-actions]
            [cn.li.ac.client.platform-hooks :as platform-hooks]
            [cn.li.ac.client.font-init :as font-init]
            [cn.li.ac.datagen.bootstrap :as datagen-bootstrap]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.ac.testing.smoke-manifest :as smoke-manifest]))

(defn default-lifecycle-hooks-runtime-state
  []
  false)

(defn create-lifecycle-hooks-runtime
  ([]
   (create-lifecycle-hooks-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-lifecycle-hooks-runtime-state))}}]
   {::runtime ::lifecycle-hooks-runtime
    :state* state*}))

(def ^:private _lifecycle-hooks-runtime (delay (create-lifecycle-hooks-runtime)))

(def ^:dynamic *lifecycle-hooks-runtime* nil)

(defn- lifecycle-hooks-runtime?
  [runtime]
  (and (map? runtime)
       (= ::lifecycle-hooks-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-lifecycle-hooks-runtime
  [runtime f]
  (when-not (lifecycle-hooks-runtime? runtime)
    (throw (ex-info "Expected lifecycle hooks runtime"
                    {:runtime runtime})))
  (binding [*lifecycle-hooks-runtime* runtime]
    (f)))

(defmacro with-lifecycle-hooks-runtime
  [runtime & body]
  `(call-with-lifecycle-hooks-runtime ~runtime (fn [] ~@body)))

(defn- current-lifecycle-hooks-runtime
  []
  (or *lifecycle-hooks-runtime*
      @_lifecycle-hooks-runtime))

(defn- lifecycle-hooks-registered-atom
  []
  (:state* (current-lifecycle-hooks-runtime)))

(defn lifecycle-hooks-guard-snapshot
  []
  @(lifecycle-hooks-registered-atom))

(defn reset-lifecycle-hooks-guard-for-test!
  ([]
   (reset-lifecycle-hooks-guard-for-test! false))
  ([registered?]
   (reset! (lifecycle-hooks-registered-atom) (boolean registered?))
   nil))

(defn init
  []
  (core-init/init))

(defn activate-runtime-content!
  []
  (content-loader/activate-runtime-content!))

(defn register-datagen-metadata!
  []
  (datagen-bootstrap/register-datagen-metadata!))

;; Register client-side initialization callback
(defn- init-client-renderers
  "Run content-owned client renderer initialization.
  Called by mcmod during client initialization."
  []
  ;; Register entity render namespaces into the neutral mcmod registry
  ;; so that mc-1.20.1 Java renderer classes can resolve them without
  ;; hardcoding AC namespace strings.
  (entity-render-registry/register-entity-render-ns!
    "silbarn" "cn.li.ac.content.entities.silbarn-render")
  (terminal-actions/install-ui-hooks!)
  (platform-hooks/install-client-content-actions!)
  (font-init/init-fonts!)
  (hooks/load-all-client-renderers!))

(defn register-lifecycle-hooks!
  "Register AC lifecycle hooks with mcmod.

  This is the explicit bootstrap entrypoint used by ServiceLoader and fallback
  content discovery. Requiring this namespace alone must not mutate lifecycle
  state."
  []
  (when (compare-and-set! (lifecycle-hooks-registered-atom) false true)
    (smoke-manifest/register!)
    (lifecycle/register-content-init! #'init)
    (lifecycle/register-runtime-content-activation! #'activate-runtime-content!)
    (lifecycle/register-datagen-metadata-init! #'register-datagen-metadata!)
    (lifecycle/register-client-init! init-client-renderers))
  nil)
