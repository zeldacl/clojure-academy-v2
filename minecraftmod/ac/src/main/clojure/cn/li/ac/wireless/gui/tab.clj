(ns cn.li.ac.wireless.gui.tab
  "Shared Wireless tab (page_wireless.xml) for all TechUI screens.

  Roles:
  - :node      -> connect a Wireless Node to a Matrix network (SSID list)
  - :generator -> connect a Generator (SolarGen) to a Wireless Node"
  (:require [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.xml-parser :as cgui-doc]
            [cn.li.mcmod.gui.container-state :as container-state]
            [cn.li.mcmod.gui.container.action-payload :as action-payload]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.ac.wireless.gui.tab.role-config :as role-config]
            [cn.li.ac.wireless.gui.tab.view :as tab-view]
            [cn.li.mcmod.util.log :as log]))

(defn- panel-network-owner
  [container]
  (container-state/owner-from-container container))

(defn- install-panel-rebuild!
  "Generic rebuild loop for any wireless-panel role.
  `panel`   - the panel_wireless widget
  `payload` - base server message payload (tile position)
  `cfg`     - entry from role-config
  `opts`    - optional: {:connected-row-logo-path ...}"
  [panel owner container cfg {:keys [connected-row-logo-path]}]
  (let [{:keys [list-msg disconnect-msg connect-msg name-fn connect-payload-fn]} cfg
        routing-payload (action-payload/action-payload container {})]
    (letfn [(rebuild! []
              (log/info "[install-panel-rebuild!] sending" (list-msg) "payload=" (pr-str routing-payload))
              (net-client/send-to-server
                owner
                (list-msg)
                routing-payload
                (fn [resp]
                  (log/info "[install-panel-rebuild!] response received, resp=" (pr-str resp))
                  (log/info "[install-panel-rebuild!] avail=" (pr-str (:avail resp)))
                  (tab-view/rebuild-page! panel
                                          {:linked       (:linked resp)
                                           :avail        (vec (:avail resp []))
                                           :name-fn      name-fn
                                           :encrypted?-fn (fn [t] (boolean (:is-encrypted? t)))
                                           :disconnect-fn (fn [_linked]
                                                            (net-client/send-to-server
                                                              owner
                                                              (disconnect-msg) routing-payload
                                                              (fn [_] (rebuild!))))
                                           :connect-fn   (fn [target pass]
                                                           (net-client/send-to-server
                                                             owner
                                                             (connect-msg)
                                                             (connect-payload-fn routing-payload target pass)
                                                             (fn [_] (rebuild!))))})
                  (tab-view/set-connected-row-logo! panel connected-row-logo-path))))]
      (rebuild!))))

(defn create-wireless-panel
  "Unified wireless panel factory.

   opts:
   - :role                  :node | :generator | :receiver  (required)
   - :container             the GUI container (must have :tile-entity)  (required)
   - :tab-logo-path         override top-left icon path
   - :connected-row-logo-path  logo for the connected-row in :receiver mode
   - :defer-initial-rebuild?   skip first list fetch until lazy activator runs"
  [{:keys [role container menu tab-logo-path connected-row-logo-path
           defer-initial-rebuild?]}]
  (let [cfg     (get role-config/role-config role)
        _       (when-not cfg
                  (throw (ex-info "Unknown wireless panel role" {:role role})))
        container (cond-> container
                    menu (assoc :minecraft-container menu))
        doc     (tab-view/base-wireless-doc)
        root    (cgui-doc/get-widget doc "main")
        panel   (tab-view/wireless-panel-from-main root)
        owner   (panel-network-owner container)]
    (tab-view/setup-panel-logo! root cfg tab-logo-path)
    (when-not defer-initial-rebuild?
      (install-panel-rebuild! panel owner container cfg
                              {:connected-row-logo-path connected-row-logo-path}))
    root))

(defn developer-wireless-tab-lazy-activator
  "Return a zero-arg fn: first call runs install-panel-rebuild! (use with :defer-initial-rebuild?).
  `main-root` must be the root widget returned by `create-wireless-panel` with :role :receiver."
  [main-root container connected-row-logo-path]
  (let [done? (atom false)
        panel (tab-view/wireless-panel-from-main main-root)
        owner (panel-network-owner container)]
    (fn []
      (when (compare-and-set! done? false true)
        (install-panel-rebuild! panel owner container (:receiver role-config/role-config)
                                {:connected-row-logo-path connected-row-logo-path})))))

(defn create-embedded-developer-wireless-panel!
  "Detach `panel_wireless` from `page_wireless.xml` and attach under `host-widget` (classic `parent_right/area`).
  Same list/connect behavior as the Wireless tab, without editing `page_developer.xml`."
  [container host-widget & [{:keys [connected-row-logo-path]}]]
  (when (and container (:tile-entity container) host-widget)
    (let [doc (tab-view/base-wireless-doc)
          main-root (cgui-doc/get-widget doc "main")
          panel (tab-view/wireless-panel-from-main main-root)
          owner (panel-network-owner container)]
      (cgui-core/remove-widget! main-root panel)
      (cgui-core/add-widget! host-widget panel)
      (cgui-core/set-position! panel 0 0)
      ;; `panel_wireless` transform is CENTER in XML; wide `parent_right/area` would offset it.
      (cgui-core/set-w-align! panel :left)
      (cgui-core/set-h-align! panel :top)
      (install-panel-rebuild! panel owner container (:receiver role-config/role-config)
                              {:connected-row-logo-path connected-row-logo-path})
      panel)))
