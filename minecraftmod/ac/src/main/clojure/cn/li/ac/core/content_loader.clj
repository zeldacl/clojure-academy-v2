(remove-ns 'cn.li.ac.core.content-loader)

(ns cn.li.ac.core.content-loader
  "AC runtime content loading orchestration."
  (:require [cn.li.ac.gui.platform-adapter :as platform-gui]
            [cn.li.ac.registry.content-namespaces :as content-ns]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.ac.wireless.gui.screen-factory :as screen-factory]
            [cn.li.mcmod.events.metadata :as event-metadata]
            [cn.li.mcmod.gui.registry-core :as gui-adapter]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed-gui]
            [cn.li.mcmod.util.log :as log]))

(defonce runtime-content-loader
  (delay
    (content-ns/load-all!)
    (let [gui-ids (gui-adapter/get-all-gui-ids)]
      (log/info "Registering screen factories for GUI IDs:" gui-ids)
      (doseq [gui-id gui-ids]
        (when-let [gui-type (platform-gui/get-gui-type gui-id)]
          (let [declared-screen-fn-kw (platform-gui/get-screen-factory-fn-kw gui-id)
                screen-fn-kw (or declared-screen-fn-kw
                                 (keyword (str "create-" (name gui-type) "-screen")))]
            (gui-adapter/register-screen-factory!
              screen-fn-kw
              (partial screen-factory/create-screen gui-type))
            (log/info "Registered screen factory" screen-fn-kw "for GUI ID" gui-id
                      "gui-type=" gui-type "declared?" (boolean declared-screen-fn-kw))))))
    (when-let [f (requiring-resolve 'cn.li.mcmod.events.metadata/init-event-metadata!)]
      (f))
    (hooks/call-all-network-handlers!)
    (tabbed-gui/register-set-tab-handler!)))

(defn activate-runtime-content!
  "Load and initialize AC runtime content once. Safe to call repeatedly."
  []
  (force runtime-content-loader)
  nil)
