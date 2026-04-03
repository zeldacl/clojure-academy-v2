(ns cn.li.ac.ability.util.toggle
  "Utilities for toggle (持续激活) skills.

  Toggle skills remain active until manually deactivated or resources depleted.
  They consume resources per tick and maintain persistent state.

  No Minecraft imports."
  (:require [cn.li.ac.ability.context :as ctx]))

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
  (ctx/update-context! ctx-id update-in [:skill-state :toggle skill-id :total-ticks] (fnil inc 0)))

(defn deactivate-toggle!
  "Deactivate toggle skill."
  [ctx-id skill-id]
  (ctx/update-context! ctx-id update-in [:skill-state :toggle skill-id] assoc :active false))

(defn remove-toggle!
  "Remove toggle skill state completely."
  [ctx-id skill-id]
  (ctx/update-context! ctx-id update-in [:skill-state :toggle] dissoc skill-id))

(defn get-toggle-state
  "Get toggle skill state."
  [ctx-data skill-id]
  (get-in ctx-data [:skill-state :toggle skill-id]))

(defn activate-toggle!
  "Activate toggle skill in context."
  [ctx-id skill-id]
  (ctx/update-context! ctx-id assoc-in [:skill-state :toggle skill-id]
                       (init-toggle-state skill-id)))
