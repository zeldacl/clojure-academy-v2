(ns cn.li.ac.wireless.gui.tab-reactive
  "Reactive Wireless Tab — network list + connect/disconnect on embedded page_wireless."
  (:require [cn.li.ac.client.toast :as toast]
            [cn.li.ac.wireless.gui.tab.role-config :as role-config]
            [cn.li.ac.wireless.gui.tab.view-reactive :as view]
            [cn.li.mcmod.gui.container-state :as container-state]
            [cn.li.mcmod.gui.container.action-payload :as action-payload]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]))

(defn- panel-network-owner [container]
  (container-state/owner-from-container container))

(defn attach-panel!
  "Wire wireless panel on an existing runtime that already contains page_wireless.xml.
   Returns rebuild! fn (idempotent). Call once per screen; safe to call on first tab switch."
  [^UiRt rt {:keys [role container menu tab-logo-path connected-row-logo-path
                    defer-initial-rebuild?]}]
  (let [cfg (get role-config/role-config role)
        _ (when-not cfg
            (throw (ex-info "Unknown wireless panel role" {:role role})))
        container (cond-> container
                    menu (assoc :minecraft-container menu))
        owner (panel-network-owner container)
        routing-payload (action-payload/action-payload container {})
        {:keys [list-msg disconnect-msg connect-msg name-fn connect-payload-fn]} cfg
        _ (rt/put-user-signal! rt :wireless-scroll (sig/signal-d 0.0))
        _ (rt/put-user-signal! rt :wireless-avail-count (sig/signal-l 0))
        _ (view/attach-scroll-buttons! rt)
        _ (view/setup-panel-logo! rt cfg tab-logo-path)
        rebuild!
        (fn rebuild! []
          (log/info "[wireless-reactive] list request" (list-msg) routing-payload)
          (net-client/send-to-server
            owner
            (list-msg)
            routing-payload
            (fn [resp]
              (log/info "[wireless-reactive] list response" (pr-str (select-keys resp [:linked :avail])))
              (view/rebuild-page!
                rt
                {:linked (:linked resp)
                 :avail (vec (:avail resp []))
                 :name-fn name-fn
                 :encrypted?-fn (fn [t] (boolean (:is-encrypted? t)))
                 :disconnect-fn (fn [_linked]
                                  (net-client/send-to-server
                                    owner (disconnect-msg) routing-payload
                                    (fn [r]
                                      (doseq [m (:messages r)]
                                        (toast/show-toast! {:message-key (:key m) :args (:args m)}))
                                      (rebuild!))))
                 :connect-fn (fn [target pass]
                               (net-client/send-to-server
                                 owner
                                 (connect-msg)
                                 (connect-payload-fn routing-payload target pass)
                                 (fn [r]
                                   (doseq [m (:messages r)]
                                     (toast/show-toast! {:message-key (:key m) :args (:args m)}))
                                   (rebuild!))))})
              (view/set-connected-row-logo! rt connected-row-logo-path))))]
    (when-not defer-initial-rebuild?
      (rebuild!))
    rebuild!))
