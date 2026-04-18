(ns cn.li.ac.ability.state.player
  "Holds the four data maps (ability, resource, cooldown, preset) for every
  online player, keyed by UUID string.

  Also provides the server-tick aggregate: resource + cooldown + dirty-sync.

  This namespace owns the single mutable atom `player-states` and is the
  concrete binding point for mcmod's *player-ability-store* dynamic var."
  (:require [cn.li.ac.ability.model.ability   :as ad]
            [cn.li.ac.ability.model.resource  :as rd]
            [cn.li.ac.ability.model.cooldown  :as cd]
            [cn.li.ac.ability.model.preset    :as pd]
            [cn.li.ac.ability.model.develop   :as dev]
            [cn.li.ac.ability.server.service.resource     :as svc-res]
            [cn.li.ac.ability.server.service.cooldown     :as svc-cd]
            [cn.li.ac.ability.server.service.develop      :as svc-dev]
            [cn.li.ac.ability.registry.event                :as evt]
            [cn.li.mcmod.util.log                  :as log]))

;; ============================================================================
;; Main atom
;; ============================================================================

(defonce player-states
  (atom {}))

;; ============================================================================
;; Access
;; ============================================================================

(defn get-player-state [uuid-str]
  (get @player-states uuid-str))

(defn set-player-state! [uuid-str state]
  (swap! player-states assoc uuid-str state))

(defn update-player-state! [uuid-str f & args]
  (apply swap! player-states update uuid-str f args))

(defn mark-dirty! [uuid-str]
  (update-player-state! uuid-str assoc :dirty? true))

(defn mark-clean! [uuid-str]
  (update-player-state! uuid-str assoc :dirty? false))

(defn dirty? [uuid-str]
  (boolean (:dirty? (get-player-state uuid-str))))

;; ============================================================================
;; Lifecycle
;; ============================================================================

(defn fresh-state []
  {:ability-data  (ad/new-ability-data)
   :resource-data (rd/new-resource-data)
   :cooldown-data (cd/new-cooldown-data)
   :preset-data   (pd/new-preset-data)
   :develop-data  (dev/new-develop-data)
   :terminal-data {:terminal-installed? false
                   :installed-apps #{}}
   :dirty?        false})

(defn get-or-create-player-state! [uuid-str]
  (or (get-player-state uuid-str)
      (let [s (fresh-state)]
        (set-player-state! uuid-str s)
        s)))

(defn remove-player-state! [uuid-str]
  (swap! player-states dissoc uuid-str))

;; ============================================================================
;; Field-level helpers
;; ============================================================================

(defn get-ability-data [uuid-str]
  (:ability-data (get-player-state uuid-str)))

(defn get-resource-data [uuid-str]
  (:resource-data (get-player-state uuid-str)))

(defn get-cooldown-data [uuid-str]
  (:cooldown-data (get-player-state uuid-str)))

(defn get-preset-data [uuid-str]
  (:preset-data (get-player-state uuid-str)))

(defn get-develop-data [uuid-str]
  (:develop-data (get-player-state uuid-str)))

(defn update-ability-data! [uuid-str f & args]
  (apply update-player-state! uuid-str update :ability-data f args)
  (mark-dirty! uuid-str))

(defn update-resource-data! [uuid-str f & args]
  (apply update-player-state! uuid-str update :resource-data f args)
  (mark-dirty! uuid-str))

(defn update-cooldown-data! [uuid-str f & args]
  (apply update-player-state! uuid-str update :cooldown-data f args)
  (mark-dirty! uuid-str))

(defn update-preset-data! [uuid-str f & args]
  (apply update-player-state! uuid-str update :preset-data f args)
  (mark-dirty! uuid-str))

(defn update-develop-data! [uuid-str f & args]
  (apply update-player-state! uuid-str update :develop-data f args)
  (mark-dirty! uuid-str))

;; ============================================================================
;; Server tick
;; ============================================================================

(defn server-tick-player!
  "Called every server tick for each online player.
  Ticks resource recovery, cooldowns, development, and fires domain events.
  Returns a map of events emitted (for sync layer to broadcast)."
  [uuid-str sync-fn]
  (when-let [state (get-player-state uuid-str)]
    (let [{:keys [resource-data cooldown-data develop-data]} state
          ;; Resource tick — may emit events
          {:keys [data events]} (svc-res/server-tick resource-data)
          new-cd (svc-cd/tick-cooldowns cooldown-data)
          ;; Develop tick
          dev-result (when (and develop-data (dev/developing? develop-data))
                       (svc-dev/tick-develop develop-data))
          new-dev    (if dev-result (:develop-data dev-result) develop-data)
          ;; If development completed, apply result
          completion (when (and dev-result (:completed? dev-result))
                      (svc-dev/apply-completion
                       new-dev
                       (:ability-data state)
                       data
                       uuid-str))
          final-ability  (if completion (:ability-data completion) (:ability-data state))
          final-resource (if completion (:resource-data completion) data)
          final-dev      (if completion (:develop-data completion) new-dev)
          all-events     (into (vec events)
                               (when completion (:events completion)))]
      (swap! player-states update uuid-str
             assoc
             :ability-data  final-ability
             :resource-data final-resource
             :cooldown-data new-cd
             :develop-data  (or final-dev (dev/new-develop-data)))
      ;; Fire domain events
      (doseq [e all-events] (evt/fire-ability-event! e))
      ;; If sync-fn provided (injected by forge layer), call when dirty
      (when (and sync-fn (seq all-events))
        (mark-dirty! uuid-str))
      {:events all-events})))
