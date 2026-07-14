(ns cn.li.ac.core
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.lifecycle :as lifecycle]
            [cn.li.mcmod.spi.entity-render-registry :as entity-render-registry]
            [cn.li.ac.bootstrap :as ac-bootstrap]
            [cn.li.ac.core.init :as core-init]
            [cn.li.ac.core.content-loader :as content-loader]
            [cn.li.ac.terminal.client.actions :as terminal-actions]
            [cn.li.ac.terminal.client.install-effect-reactive :as install-effect-reactive]
            [cn.li.ac.client.platform-hooks :as platform-hooks]
            [cn.li.ac.client.font-init :as font-init]
            [cn.li.ac.datagen.bootstrap :as datagen-bootstrap]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.ac.testing.smoke-manifest :as smoke-manifest]))

;; Lifecycle hooks guard — Framework [:service :ac-lifecycle-hooks]

(def ^:private guard-path [:service :ac-lifecycle-hooks])

(defn lifecycle-hooks-guard-snapshot []
  (if-let [fw-atom (fw/fw-atom)]
    (boolean (get-in @fw-atom guard-path))
    false))

(defn reset-lifecycle-hooks-guard-for-test!
  ([]
   (reset-lifecycle-hooks-guard-for-test! false))
  ([registered?]
   (when-let [fw-atom (fw/fw-atom)]
     (swap! fw-atom assoc-in guard-path (boolean registered?)))
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
  ;; cn.li.ac.terminal.client.actions/install-ui-hooks!
  (terminal-actions/install-ui-hooks!)
  ;; Push handler for terminal install-effect (moved out of shell to break circular dep)
  (install-effect-reactive/install-push-handler!)
  (platform-hooks/install-client-content-actions!)
  (font-init/init-fonts!)
  (hooks/load-all-client-renderers!))

(defn- register-lifecycle-hooks-body!
  []
  (smoke-manifest/register!)
  (ac-bootstrap/register-post-spi-init!)
  (lifecycle/register-content-init! #'init)
  (lifecycle/register-runtime-content-activation! #'activate-runtime-content!)
  (lifecycle/register-datagen-metadata-init! #'register-datagen-metadata!)
  (lifecycle/register-client-init! init-client-renderers))

(defn register-lifecycle-hooks!
  "Register AC lifecycle hooks with mcmod.

  This is the explicit bootstrap entrypoint used by ServiceLoader and fallback
  content discovery. Requiring this namespace alone must not mutate lifecycle
  state."
  []
  (let [should-register?
        (if-let [fw-atom (fw/fw-atom)]
          (let [k guard-path]
            (when (nil? (get-in @fw-atom k))
              (swap! fw-atom assoc-in k true)
              true))
          true)]
    (when should-register?
      (register-lifecycle-hooks-body!)))
  nil)
