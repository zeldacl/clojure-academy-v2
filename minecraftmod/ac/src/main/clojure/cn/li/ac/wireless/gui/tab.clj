(ns cn.li.ac.wireless.gui.tab
  "Shared Wireless tab (page_wireless.xml) for all TechUI screens.

  Modes:
  - :node      -> connect a Wireless Node to a Matrix network (SSID list)
  - :generator -> connect a Generator (SolarGen) to a Wireless Node"
  (:require [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.xml-parser :as cgui-doc]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.ac.wireless.gui.tab.role-config :as role-config]
            [cn.li.ac.wireless.gui.tab.view :as tab-view]))

(defn- install-panel-rebuild!
  "Generic rebuild loop for any wireless-panel role.
  `panel`   - the panel_wireless widget
  `payload` - base server message payload (tile position)
  `cfg`     - entry from role-config
  `opts`    - optional: {:connected-row-logo-path ...}"
  [panel payload cfg {:keys [connected-row-logo-path]}]
  (let [{:keys [list-msg disconnect-msg connect-msg name-fn connect-payload-fn]} cfg]
    (letfn [(rebuild! []
              (net-client/send-to-server
                (list-msg)
                payload
                (fn [resp]
                  (tab-view/rebuild-page! panel
                                          {:linked       (:linked resp)
                                           :avail        (vec (:avail resp []))
                                           :name-fn      name-fn
                                           :encrypted?-fn (fn [t] (boolean (:is-encrypted? t)))
                                           :disconnect-fn (fn [_linked]
                                                            (net-client/send-to-server
                                                              (disconnect-msg) payload
                                                              (fn [_] (rebuild!))))
                                           :connect-fn   (fn [target pass]
                                                           (net-client/send-to-server
                                                             (connect-msg)
                                                             (connect-payload-fn payload target pass)
                                                             (fn [_] (rebuild!))))})
                  (tab-view/set-connected-row-logo! panel connected-row-logo-path))))]
      (rebuild!))))

(defn create-wireless-panel-by-role
  "Unified wireless panel factory.

   opts:
   - :role                  :node | :generator | :receiver  (required)
   - :container             the GUI container (must have :tile-entity)  (required)
   - :tab-logo-path         override top-left icon path
   - :connected-row-logo-path  logo for the connected-row in :receiver mode
   - :defer-initial-rebuild?   skip first list fetch until lazy activator runs"
  [{:keys [role container tab-logo-path connected-row-logo-path
           defer-initial-rebuild?]}]
  (let [cfg     (get role-config/role-config role)
        _       (when-not cfg
                  (throw (ex-info "Unknown wireless panel role" {:role role})))
        doc     (tab-view/base-wireless-doc)
        root    (cgui-doc/get-widget doc "main")
        panel   (tab-view/wireless-panel-from-main root)
        payload (net-helpers/tile-pos-payload (:tile-entity container))]
    (tab-view/setup-panel-logo! root cfg tab-logo-path)
    (when-not defer-initial-rebuild?
      (install-panel-rebuild! panel payload cfg
                              {:connected-row-logo-path connected-row-logo-path}))
    root))

(defn create-wireless-panel-node
  "Wireless tab for Wireless Node: connect node -> matrix network (SSID list)."
  [container]
  (create-wireless-panel-by-role {:role :node :container container}))

(defn create-wireless-panel-generator
  "Wireless tab for Generator (SolarGen): connect generator -> wireless node."
  [container]
  (create-wireless-panel-by-role {:role :generator :container container}))

(defn create-wireless-panel-receiver
  "Wireless tab for IF receivers: link receiver -> wireless node.

  opts:
  - :tab-logo-path              top-left tab icon
  - :connected-row-logo-path    elem_connected row icon
  - :defer-initial-rebuild?     skip first list fetch until lazy activator runs"
  [container & [{:keys [tab-logo-path connected-row-logo-path defer-initial-rebuild?]}]]
  (create-wireless-panel-by-role {:role                     :receiver
                                  :container                container
                                  :tab-logo-path            tab-logo-path
                                  :connected-row-logo-path  connected-row-logo-path
                                  :defer-initial-rebuild?   defer-initial-rebuild?}))

(defn developer-wireless-tab-lazy-activator
  "Return a zero-arg fn: first call runs install-panel-rebuild! (use with :defer-initial-rebuild?).
  `main-root` must be the root widget returned by `create-wireless-panel-receiver`."
  [main-root container connected-row-logo-path]
  (let [done? (atom false)
        panel (tab-view/wireless-panel-from-main main-root)
        payload (net-helpers/tile-pos-payload (:tile-entity container))]
    (fn []
      (when (compare-and-set! done? false true)
        (install-panel-rebuild! panel payload (:receiver role-config/role-config)
                                {:connected-row-logo-path connected-row-logo-path})))))

(defn create-embedded-developer-wireless-panel!
  "Detach `panel_wireless` from `page_wireless.xml` and attach under `host-widget` (classic `parent_right/area`).
  Same list/connect behavior as the Wireless tab, without editing `page_developer.xml`."
  [container host-widget & [{:keys [connected-row-logo-path]}]]
  (when (and container (:tile-entity container) host-widget)
    (let [doc (tab-view/base-wireless-doc)
          main-root (cgui-doc/get-widget doc "main")
          panel (tab-view/wireless-panel-from-main main-root)
          payload (net-helpers/tile-pos-payload (:tile-entity container))]
      (cgui-core/remove-widget! main-root panel)
      (cgui-core/add-widget! host-widget panel)
      (cgui-core/set-position! panel 0 0)
      ;; `panel_wireless` transform is CENTER in XML; wide `parent_right/area` would offset it.
      (cgui-core/set-w-align! panel :left)
      (cgui-core/set-h-align! panel :top)
      (install-panel-rebuild! panel payload (:receiver role-config/role-config)
                              {:connected-row-logo-path connected-row-logo-path})
      panel)))

(defn create-wireless-panel
  "Create shared wireless tab panel.

  opts:
  - :mode      :node | :generator | :receiver
  - :container the GUI container (must have :tile-entity)
  - :receiver-tab-logo-path / :receiver-connected-logo-path — only for :receiver
  - :receiver-defer-initial-rebuild? — receiver only: skip first list fetch until lazy activator (developer GUI)"
  [{:keys [mode container receiver-tab-logo-path receiver-connected-logo-path
           receiver-defer-initial-rebuild?]}]
  (case mode
    :generator (create-wireless-panel-generator container)
    :receiver (create-wireless-panel-receiver container
                {:tab-logo-path receiver-tab-logo-path
                 :connected-row-logo-path receiver-connected-logo-path
                 :defer-initial-rebuild? receiver-defer-initial-rebuild?})
    :node (create-wireless-panel-node container)
    (do
      (log/warn "Unknown wireless-tab mode:" mode ", falling back to :node")
      (create-wireless-panel-node container))))
