(ns cn.li.ac.ability.util.toggle
  "Utilities for toggle (持续激�? skills.

  Toggle skills remain active until manually deactivated or resources depleted.
  They consume resources per tick and maintain persistent state.

  No Minecraft imports."
  (:require [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.mcmod.util.log :as log]))

(defn- context-owner
  [ctx-id]
  (try
    (let [ctx-map (ctx/get-context ctx-id)
          sid (:session-id ctx-map)]
      {:ctx-map ctx-map
       :session-id (if (vector? sid) (first sid) sid)
       :player-uuid (:player-uuid ctx-map)})
    (catch Exception _
      {:ctx-map nil :session-id nil :player-uuid nil})))

(defn- run-toggle-command!
  [ctx-id command]
  (let [{:keys [session-id player-uuid]} (context-owner ctx-id)
        command* (assoc command :ctx-id ctx-id)]
    (if (and session-id player-uuid)
      (command-rt/run-command-in-session! session-id player-uuid command*)
      (do
        (log/warn "toggle command skipped: missing context owner/session" {:ctx-id ctx-id :command (:command command*)})
        nil))))

(defn init-toggle-state
  "Initialize toggle skill state in context.
  Returns initial state map."
  [skill-id]
  {:active true
   :skill-id skill-id
   :start-tick 0
   :total-ticks 0})

(defn is-toggle-active?
  "Check if toggle skill is currently active."
  [ctx-data skill-id]
  (let [toggle-state (get-in ctx-data [:skill-state :toggle skill-id])]
    (and toggle-state (:active toggle-state))))

(defn update-toggle-tick!
  "Update toggle skill tick counter.
  Should be called every tick while active."
  [ctx-id skill-id]
  (run-toggle-command! ctx-id
                       {:command :context-increment-skill-state
                        :ctx-id ctx-id
                        :k [:toggle skill-id :total-ticks]
                        :max Long/MAX_VALUE}))

(defn deactivate-toggle!
  "Deactivate toggle skill."
  [ctx-id skill-id]
  (run-toggle-command! ctx-id
                       {:command :context-set-toggle-active
                        :ctx-id ctx-id
                        :skill-id skill-id
                        :active false}))

(defn remove-toggle!
  "Remove toggle skill state completely."
  [ctx-id skill-id]
  (run-toggle-command! ctx-id
                       {:command :context-remove-toggle-state
                        :ctx-id ctx-id
                        :skill-id skill-id}))

(defn get-toggle-state
  "Get toggle skill state."
  [ctx-data skill-id]
  (get-in ctx-data [:skill-state :toggle skill-id]))

(defn activate-toggle!
  "Activate toggle skill in context."
  [ctx-id skill-id]
  (run-toggle-command! ctx-id
                       {:command :context-set-toggle-state
                        :ctx-id ctx-id
                        :skill-id skill-id
                        :toggle-state (init-toggle-state skill-id)}))
