(ns cn.li.ac.gui.presentation
  "AC-owned adapter for the retained Presentation Runtime.

   Surface controllers provide plain state maps and action functions. This
   namespace owns artifact lookup, mount geometry, deterministic paint,
   and the opaque host bridge; it exposes no renderer implementation details."
  (:require [cn.li.presentation.core.artifact :as artifact]
            [cn.li.presentation.core.paint :as paint]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [clojure.string :as str]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.presentation.core HostGeometry]))

(defn- host-api []
  (or (bridge/call-adapter :presentation-host-api)
      (throw (ex-info "Presentation Runtime bridge is not installed" {}))))

(defn- stage-for [host-kind]
  (case host-kind
    :hud :hud
    :world :world
    :first-person :first-person
    :screen :screen
    :container :screen
    :screen))

(defn- view-id-for [view-id]
  (cond
    (keyword? view-id) view-id
    (string? view-id)
    (case view-id
      "academy:combat_hud" :academy.app/combat-hud
      "academy:terminal" :academy.app/terminal
      "academy:application" :academy.app/application
      "academy:settings" :academy.app/settings
      "academy:machine_container" :academy.app/machine-container
      "academy:developer" :academy.app/developer
      "academy:wireless_matrix" :academy.app/wireless-matrix
      "academy:wireless_node" :academy.app/wireless-node
      (keyword (str/replace view-id ":" "/")))
    :else view-id))

(defn mount-view!
  [{:keys [view-id host-kind state reduce run-effect! on-close dispatch-action!]
    :or {host-kind :screen state {}}}]
  (let [api (host-api)
        view-id (view-id-for view-id)
        artifact (artifact/load-view view-id)
        state* (atom state)
        dispatch-action! (or dispatch-action!
                             (fn [_action _payload current] current))
        reduce (or reduce
                   (fn [current action payload]
                     (let [next-state (dispatch-action! action payload current)]
                       {:state (if (map? next-state) next-state current)
                        :effects []
                        :event-result :consume})))
        reduce* (fn [current action payload]
                  (let [response (reduce current action payload)
                        next-state (if (and (map? response)
                                            (contains? response :state))
                                       (:state response)
                                       current)]
                    (reset! state* next-state)
                    response))
        mount ((:mount-view! api)
               {:host {:stage (stage-for host-kind)}
                :view-id view-id
                :artifact artifact
                :state state
                :reduce reduce*
                :run-effect! (or run-effect! (fn [_] nil))
                :close! (fn [_] (when on-close (on-close)))
                :paint-fn paint/paint-view})]
    {:mount mount
     :view-id view-id
     :artifact artifact
     :state state*
     :present! (fn [next-state]
                 (reset! state* next-state)
                 ((:present-view! api) mount next-state))
     :update-host! (fn [width height scale]
                     ((:update-host! api) mount (HostGeometry. 0.0 0.0
                                                               (int width) (int height)
                                                               (double scale))))
     :dispatch! (fn [action payload]
                  ((:dispatch-input! api) mount {:action action :payload payload}))
     :unmount! (fn [] ((:unmount! api) mount))}))

(defn dispatch! [{:keys [dispatch!]} action payload]
  (when dispatch!
    (dispatch! action payload)))

(defn present! [{:keys [present!]} state]
  (when present!
    (present! state)))

(defn unmount! [{:keys [unmount!]}]
  (when unmount! (unmount!)))

