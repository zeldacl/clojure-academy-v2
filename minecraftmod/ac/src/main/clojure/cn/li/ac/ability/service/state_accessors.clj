(ns cn.li.ac.ability.service.state-accessors
  "Domain-specific read/write accessors for player-state payload sections."
  (:require [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- runtime-player-state
  [uuid-str]
  (store/get-player-state* (runtime-hooks/require-player-state-session-id "state-accessors")
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
  (store/update-player-state!* session-id
                               uuid-str
                               #(apply update % :cooldown-data f args))
  (store/mark-player-dirty! session-id uuid-str))

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
         (runtime-hooks/require-player-state-session-id "state-accessors")
         uuid-str
         f
         args))

(defn update-ability-data-in-session!
  [session-id uuid-str f & args]
  (store/update-player-state!* session-id
                               uuid-str
                               #(apply update % :ability-data f args))
  (store/mark-player-dirty! session-id uuid-str))

(defn update-resource-data! [uuid-str f & args]
  (apply update-resource-data-in-session!
         (runtime-hooks/require-player-state-session-id "state-accessors")
         uuid-str
         f
         args))

(defn update-resource-data-in-session!
  [session-id uuid-str f & args]
  (store/update-player-state!* session-id
                               uuid-str
                               #(apply update % :resource-data f args))
  (store/mark-player-dirty! session-id uuid-str))

(defn update-cooldown-data! [uuid-str f & args]
  (apply update-cooldown-data-in-session!
         (runtime-hooks/require-player-state-session-id "state-accessors")
         uuid-str
         f
         args))

(defn update-preset-data! [uuid-str f & args]
  (apply update-preset-data-in-session!
         (runtime-hooks/require-player-state-session-id "state-accessors")
         uuid-str
         f
         args))

(defn update-preset-data-in-session!
  [session-id uuid-str f & args]
  (store/update-player-state!* session-id
                               uuid-str
                               #(apply update % :preset-data f args))
  (store/mark-player-dirty! session-id uuid-str))

(defn update-develop-data! [uuid-str f & args]
  (apply update-develop-data-in-session!
         (runtime-hooks/require-player-state-session-id "state-accessors")
         uuid-str
         f
         args))

(defn update-develop-data-in-session!
  [session-id uuid-str f & args]
  (store/update-player-state!* session-id
                               uuid-str
                               #(apply update % :develop-data f args))
  (store/mark-player-dirty! session-id uuid-str))
