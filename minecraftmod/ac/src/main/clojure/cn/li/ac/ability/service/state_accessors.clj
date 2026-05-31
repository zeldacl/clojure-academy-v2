(ns cn.li.ac.ability.service.state-accessors
  "Domain-specific read/write accessors for player-state payload sections."
  (:require [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- resolve-session-id
  []
  (runtime-hooks/require-player-state-session-id "state-accessors"))

(defn- runtime-player-state
  [uuid-str]
  (store/get-player-state* (resolve-session-id)
                           uuid-str))

(defn runtime-player-state-in-session
  [session-id uuid-str]
  (store/get-player-state* session-id uuid-str))

(declare update-ability-data-in-session!
         update-resource-data-in-session!
         update-preset-data-in-session!
         update-develop-data-in-session!)

(defn update-cooldown-data-in-session!
  "Update cooldown data using an explicit store session id.
  Use this when the caller already resolved session ownership and should not
  depend on runtime-hooks dynamic owner state."
  [session-id uuid-str f & args]
  (command-rt/run-command-in-session!
   session-id
   uuid-str
   {:command :transform-cooldown-data
    :transform-fn f
    :transform-args args})
  nil)

(defn get-ability-data [uuid-str]
  (:ability-data (runtime-player-state uuid-str)))

(defn get-resource-data [uuid-str]
  (:resource-data (runtime-player-state uuid-str)))

(defn get-cooldown-data [uuid-str]
  (:cooldown-data (runtime-player-state uuid-str)))

(defn get-preset-data [uuid-str]
  (:preset-data (runtime-player-state uuid-str)))

(defn get-develop-data [uuid-str]
  (:develop-data (runtime-player-state uuid-str)))

(defn update-ability-data! [uuid-str f & args]
  (apply update-ability-data-in-session!
         (resolve-session-id)
         uuid-str
         f
         args))

(defn update-ability-data-in-session!
  [session-id uuid-str f & args]
  (command-rt/run-command-in-session!
   session-id
   uuid-str
   {:command :transform-ability-data
    :transform-fn f
    :transform-args args})
  nil)

(defn update-resource-data! [uuid-str f & args]
  (apply update-resource-data-in-session!
         (resolve-session-id)
         uuid-str
         f
         args))

(defn update-resource-data-in-session!
  [session-id uuid-str f & args]
  (command-rt/run-command-in-session!
   session-id
   uuid-str
   {:command :transform-resource-data
    :transform-fn f
    :transform-args args})
  nil)

(defn update-cooldown-data! [uuid-str f & args]
  (apply update-cooldown-data-in-session!
         (resolve-session-id)
         uuid-str
         f
         args))

(defn update-preset-data! [uuid-str f & args]
  (apply update-preset-data-in-session!
         (resolve-session-id)
         uuid-str
         f
         args))

(defn update-preset-data-in-session!
  [session-id uuid-str f & args]
  (command-rt/run-command-in-session!
   session-id
   uuid-str
   {:command :transform-preset-data
    :transform-fn f
    :transform-args args})
  nil)

(defn update-develop-data! [uuid-str f & args]
  (apply update-develop-data-in-session!
         (resolve-session-id)
         uuid-str
         f
         args))

(defn update-develop-data-in-session!
  [session-id uuid-str f & args]
  (command-rt/run-command-in-session!
   session-id
   uuid-str
   {:command :transform-develop-data
    :transform-fn f
    :transform-args args})
  nil)
