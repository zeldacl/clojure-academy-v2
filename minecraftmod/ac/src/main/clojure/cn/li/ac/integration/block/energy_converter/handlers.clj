(ns cn.li.ac.integration.block.energy-converter.handlers
  "Wireless link handlers for energy converter receiver/generator variants."
  (:require [cn.li.ac.block.machine.wireless-handlers :as wireless-handlers]
            [cn.li.ac.wireless.api :as wireless-api]
            [cn.li.ac.wireless.data.node-conn :as node-conn]
            [cn.li.mcmod.platform.be :as platform-be]))

(def ^:private generator-ids #{"rf-output" "eu-output"})
(defn- generator? [tile]
  (contains? generator-ids (str (platform-be/get-block-id tile))))
(defn- linked-node [tile]
  (let [conn (if (generator? tile)
               (wireless-api/get-node-conn-by-generator tile)
               (wireless-api/get-node-conn-by-receiver tile))]
    (when conn
      (node-conn/get-node conn (platform-be/be-get-world-safe tile)))))
(defn- link! [tile node password need-auth?]
  (if (generator? tile)
    (wireless-api/link-generator-to-node! tile node password need-auth?)
    (wireless-api/link-receiver-to-node! tile node password need-auth?)))
(defn- unlink! [tile]
  (if (generator? tile)
    (wireless-api/unlink-generator-from-node! tile)
    (wireless-api/unlink-receiver-from-node! tile)))
(defn register-network-handlers! []
  (wireless-handlers/register-link-handlers!
    {:message-domain :energy-converter
     :get-linked-node linked-node :link! link! :unlink! unlink!
     :log-label "Energy Converter wireless"}))