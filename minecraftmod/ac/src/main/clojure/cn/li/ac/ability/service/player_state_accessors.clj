(ns cn.li.ac.ability.service.player-state-accessors
  "Domain-specific read/write accessors for player-state payload sections."
  (:require [cn.li.ac.ability.service.player-state-core :as core]
            [cn.li.ac.ability.service.player-state-dirty :as dirty]))

(defn get-ability-data [uuid-str]
  (:ability-data (core/get-player-state uuid-str)))

(defn get-resource-data [uuid-str]
  (:resource-data (core/get-player-state uuid-str)))

(defn get-cooldown-data [uuid-str]
  (:cooldown-data (core/get-player-state uuid-str)))

(defn get-preset-data [uuid-str]
  (:preset-data (core/get-player-state uuid-str)))

(defn get-develop-data [uuid-str]
  (:develop-data (core/get-player-state uuid-str)))

(defn update-ability-data! [uuid-str f & args]
  (apply core/update-player-state! uuid-str update :ability-data f args)
  (dirty/mark-dirty! uuid-str))

(defn update-resource-data! [uuid-str f & args]
  (apply core/update-player-state! uuid-str update :resource-data f args)
  (dirty/mark-dirty! uuid-str))

(defn update-cooldown-data! [uuid-str f & args]
  (apply core/update-player-state! uuid-str update :cooldown-data f args)
  (dirty/mark-dirty! uuid-str))

(defn update-preset-data! [uuid-str f & args]
  (apply core/update-player-state! uuid-str update :preset-data f args)
  (dirty/mark-dirty! uuid-str))

(defn update-develop-data! [uuid-str f & args]
  (apply core/update-player-state! uuid-str update :develop-data f args)
  (dirty/mark-dirty! uuid-str))
