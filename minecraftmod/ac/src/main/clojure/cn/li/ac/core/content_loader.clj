(ns cn.li.ac.core.content-loader
  "AC runtime content loading orchestration."
  (:require [cn.li.ac.gui.platform-adapter :as platform-gui]
            [cn.li.ac.gui.platform-adapter.sync-bootstrap :as gui-sync-bootstrap]
            [cn.li.ac.registry.content-namespaces :as content-ns]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.ac.wireless.gui.sync.handler :as wireless-sync-handler]
            [cn.li.ac.wireless.gui.screen-factory :as screen-factory]
            [cn.li.mcmod.block.state-schema :as state-schema]
            [cn.li.mcmod.gui.registry :as gui-registry]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed-gui]
            [cn.li.mcmod.util.log :as log]))

(defn- register-network-edit-helpers!
  []
  (state-schema/register-network-helper-fns!
    {:get-world wireless-sync-handler/get-world
     :get-tile-at wireless-sync-handler/get-tile-at}))

(def ^:private runtime-content-loader-lock
  (Object.))

(def ^:private ^:dynamic *runtime-content-loaded?*
  false)

(defn- load-runtime-content-once!
  []
  (platform-gui/install-into-mcmod!)
  (gui-sync-bootstrap/register-client-push-handler!)
  (register-network-edit-helpers!)
  (content-ns/load-all!)
  (let [gui-ids (gui-registry/get-all-gui-ids)]
    (log/info "Registering screen factories for GUI IDs:" gui-ids)
    (doseq [gui-id gui-ids]
      (when-let [gui-type (gui-registry/get-gui-type gui-id)]
        (let [declared-screen-fn-kw (gui-registry/get-screen-factory-fn-kw gui-id)
              screen-fn-kw (or declared-screen-fn-kw
                               (keyword (str "create-" (name gui-type) "-screen")))]
          (gui-registry/register-screen-factory!
            screen-fn-kw
            (partial screen-factory/create-screen gui-type))
          (log/info "Registered screen factory" screen-fn-kw "for GUI ID" gui-id
                    "gui-type=" gui-type "declared?" (boolean declared-screen-fn-kw))))))
  (hooks/call-all-network-handlers!)
  (tabbed-gui/register-set-tab-handler!)
  nil)

(defn activate-runtime-content!
  "Load and initialize AC runtime content once. Safe to call repeatedly."
  []
  (when-not (var-get #'*runtime-content-loaded?*)
    (locking runtime-content-loader-lock
      (when-not (var-get #'*runtime-content-loaded?*)
        (load-runtime-content-once!)
        (alter-var-root #'*runtime-content-loaded?* (constantly true)))))
  nil)
