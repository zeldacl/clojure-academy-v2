(ns cn.li.ac.terminal.client.shell-reactive
  "Terminal Screen entry point for Presentation Runtime.

   Terminal state, app actions and network authority remain in Clojure; the
   Minecraft Screen boundary only receives an opaque mount token."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.ac.ability.util.uuid :as player-uuid]
            [cn.li.ac.terminal.client.presentation-terminal :as presentation-terminal]
            [cn.li.ac.terminal.client.runtime :as term-rt]
            [cn.li.ac.terminal.messages :as terminal-messages]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log]))

(defn- player-owner [player]
  (term-rt/player-owner (or (player-uuid/player-uuid player) (str player))))

(defn- query-terminal-state!
  ([owner callback] (query-terminal-state! owner callback true))
  ([owner callback gate-active?]
   (let [generation (term-rt/ensure-owner! owner)]
     (net-client/send-to-server owner (terminal-messages/msg-id :get-state) {}
       (fn [response]
         (if (or (not gate-active?) (term-rt/owner-active? owner generation))
           (do (term-rt/dispatch-event! owner :terminal/query-response response)
               (when callback (callback response)))
           (log/warn "[AC-Terminal] ignored stale state response")))))))

(defn- presentation-action-dispatch! [owner action payload]
  (cond
    (= action :terminal/set-page)
    (term-rt/dispatch-event! owner :terminal/set-page payload)

    (= action :terminal/query)
    (query-terminal-state! owner nil)

    (= action :terminal/submit-query)
    (do (term-rt/dispatch-event! owner :terminal/set-query payload)
        (query-terminal-state! owner nil))

    (contains? #{:terminal/install-app :terminal/uninstall-app} action)
    (let [install? (= action :terminal/install-app)
          result-event (if install? :terminal/install-app-result
                           :terminal/uninstall-app-result)
          message (if install? :install-app :uninstall-app)]
      (term-rt/dispatch-event! owner :terminal/install-app-start payload)
      (net-client/send-to-server owner (terminal-messages/msg-id message)
        {:app-id (:app-id payload)}
        (fn [response]
          (term-rt/dispatch-event! owner result-event response))))

    :else nil))

(defn open! [player]
  (let [owner (player-owner player)
        on-close (fn []
                   (term-rt/mark-ui-open! false)
                   (term-rt/clear-state! owner)
                   (bridge/terminal-cursor-show!))]
    (presentation-terminal/open-screen!
      owner (partial presentation-action-dispatch! owner) on-close)
    (term-rt/mark-ui-open! true)
    (bridge/terminal-cursor-hide!)
    owner))

(defn open-terminal! [player]
  (let [owner (player-owner player)]
    (query-terminal-state! owner
      (fn [response]
        (if (:terminal-installed? response)
          (open! player)
          (bridge/send-system-message!
            player (str "terminal." modid/MOD-ID ".notinstalled"))))
      false)))

(defn toggle! [player]
  (if (term-rt/ui-open?)
    (bridge/close-screen!)
    (open-terminal! player)))

(defn create-terminal-gui-reactive [player]
  {:type :presentation-screen :owner (player-owner player) :title "Terminal"})
