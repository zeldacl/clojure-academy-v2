(ns cn.li.ac.block.wireless-matrix.gui-reactive
  "Complete reactive replacement for wireless_matrix/gui.clj screen rendering.
   Container/slot/network functional logic delegates to the old gui.clj's
   public defns (create-container, get-slot-count, etc.) — those functions
   contain complex capability-proxy + ownership-policy logic that is reused
   as-is rather than reimplemented, avoiding behavior drift."
  (:require [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.gui.spec :as gui-reg]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.gui.block-gui-reactive :as bgui]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.ac.block.wireless-matrix.gui :as old-gui]))

;; ============================================================================
;; Reactive rendering bindings
;; ============================================================================

(defn attach-binds! [r container _signals]
  (let [clock (rt/clock-ms-sig r)]
    (rt/put-user-signal! r :ssid
      (sig/computed-o [clock] (fn [_] (or @(:ssid container) "..."))))
    (rt/put-user-signal! r :bandwidth
      (sig/computed-o [clock] (fn [_] (str (or @(:bandwidth container) 0) " MHz"))))
    (rt/put-user-signal! r :connections
      (sig/computed-o [clock] (fn [_] (str (or @(:connections container) 0)))))))

(defn create-screen [container menu player]
  (bgui/create-screen
    {:page-xml "guis/rework/new/page_matrix.xml" :texture-name "matrix"
     :container container :menu menu
     :histograms [(bgui/hist-energy 0xFF4488CC)]
     :properties {:ssid #(or @(:ssid container) "...")
                  :bandwidth #(str (or @(:bandwidth container) 0) " MHz")
                  :connections #(str (or @(:connections container) 0))}
     :wireless? true :wireless-role :machine :custom-bind! attach-binds!}))

(def update! bgui/update-signals!)
(def open! bgui/open!)

;; ============================================================================
;; Registration — delegates container/slot/network logic to old-gui (as-is)
;; ============================================================================

(defonce-guard matrix-reactive-installed?)
(defn init-wireless-matrix-reactive! []
  (with-init-guard matrix-reactive-installed?
    (gui-reg/register-block-gui!
      (gui-manifest/gui-name :wireless-matrix)
      (merge (gui-manifest/gui-registration :wireless-matrix)
        {:container-fn old-gui/create-container
         :screen-fn create-screen
         :server-menu-sync-fn old-gui/server-menu-sync!
         :validate-fn old-gui/still-valid?
         :close-fn old-gui/on-close
         :button-click-fn old-gui/handle-button-click!
         :slot-count-fn old-gui/get-slot-count
         :slot-get-fn old-gui/get-slot-item
         :slot-set-fn old-gui/set-slot-item!
         :slot-can-place-fn old-gui/can-place-item?
         :slot-changed-fn old-gui/slot-changed!
         :quick-move-fn old-gui/quick-move-stack}))
    (log/info "Wireless Matrix GUI initialized (reactive render + delegated container logic)")))
