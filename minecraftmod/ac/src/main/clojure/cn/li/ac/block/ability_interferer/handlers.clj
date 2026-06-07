(ns cn.li.ac.block.ability-interferer.handlers
  "Ability Interferer network handlers."
  (:require [clojure.string :as str]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.block.machine.handlers :as machine-handlers]
            [cn.li.ac.block.machine.runtime :as machine-runtime]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.ac.block.ability-interferer.logic :as interferer-logic]
            [cn.li.mcmod.util.log :as log]))

(defn- msg [action] (msg-registry/msg :ability-interferer action))

(defn- normalize-whitelist
  [names]
  (->> names
       (map #(str/trim (str %)))
       (remove str/blank?)
       distinct
       sort
       vec))

(defn- open-tile [payload player]
  (machine-handlers/open-container-tile payload player))

(defn- handle-change-range [payload player]
  (let [tile (open-tile payload player)
        requested (:range payload)]
    (if (and tile (number? requested))
      (let [state' (assoc (or (platform-be/get-custom-state tile) interferer-logic/interferer-default-state)
                          :range (interferer-logic/clamp-range requested))]
        (machine-runtime/commit-from-tile! tile interferer-logic/interferer-default-state state'
                                           :blockstate-updater interferer-logic/interferer-blockstate-updater)
        {:success true})
      {:success false})))

(defn- handle-toggle-enabled [payload player]
  (let [tile (open-tile payload player)
        new-enabled (boolean (:enabled payload))]
    (if tile
      (let [state (or (platform-be/get-custom-state tile) interferer-logic/interferer-default-state)
            state' (if new-enabled
                     (assoc state :enabled true)
                     (assoc state :enabled false :affected-player-count 0 :affected-player-uuids []))]
        (machine-runtime/commit-from-tile! tile interferer-logic/interferer-default-state state'
                                           :blockstate-updater interferer-logic/interferer-blockstate-updater
                                           :after-commit! interferer-logic/interferer-after-commit!)
        {:success true})
      {:success false})))

(defn- handle-set-whitelist [payload player]
  (let [tile (open-tile payload player)
        names (:whitelist payload)]
    (if (and tile (sequential? names))
      (let [cleaned (normalize-whitelist names)]
        (machine-runtime/commit-from-tile! tile interferer-logic/interferer-default-state
                                           (assoc (or (platform-be/get-custom-state tile) interferer-logic/interferer-default-state)
                                                  :whitelist cleaned)
                                           :blockstate-updater interferer-logic/interferer-blockstate-updater)
        {:success true})
      {:success false})))

(defn- handle-add-to-whitelist [payload player]
  (let [tile (open-tile payload player)
        player-name (:player-name payload)]
    (if (and tile (not (str/blank? (str player-name))))
      (let [state (or (platform-be/get-custom-state tile) interferer-logic/interferer-default-state)
            new-whitelist (normalize-whitelist (conj (vec (:whitelist state [])) player-name))]
        (machine-runtime/commit-from-tile! tile interferer-logic/interferer-default-state
                                           (assoc state :whitelist new-whitelist)
                                           :blockstate-updater interferer-logic/interferer-blockstate-updater)
        {:success true})
      {:success false})))

(defn- handle-remove-from-whitelist [payload player]
  (let [tile (open-tile payload player)
        player-name (:player-name payload)]
    (if (and tile (not (str/blank? (str player-name))))
      (let [state (or (platform-be/get-custom-state tile) interferer-logic/interferer-default-state)
            new-whitelist (normalize-whitelist (remove #(= % player-name) (:whitelist state [])))]
        (machine-runtime/commit-from-tile! tile interferer-logic/interferer-default-state
                                           (assoc state :whitelist new-whitelist)
                                           :blockstate-updater interferer-logic/interferer-blockstate-updater)
        {:success true})
      {:success false})))

(defn register-network-handlers! []
  (net-server/register-handler (msg :change-range) handle-change-range)
  (net-server/register-handler (msg :toggle-enabled) handle-toggle-enabled)
  (net-server/register-handler (msg :set-whitelist) handle-set-whitelist)
  (net-server/register-handler (msg :add-to-whitelist) handle-add-to-whitelist)
  (net-server/register-handler (msg :remove-from-whitelist) handle-remove-from-whitelist)
  (log/info "Ability Interferer network handlers registered"))
