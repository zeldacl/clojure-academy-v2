(ns cn.li.ac.block.imag-fusor.handlers
  "Imaginary Fusor network handlers (wireless receiver linking)."
  (:require [cn.li.ac.block.machine.wireless-handlers :as wireless-handlers]
            [cn.li.ac.wireless.api :as wireless-api]
            [cn.li.ac.wireless.data.node-conn :as node-conn]
            [cn.li.mcmod.util.log :as log]))

(defn- get-linked-node-for-fusor [tile]
  (when-let [conn (try (wireless-api/get-node-conn-by-receiver tile) (catch Exception _ nil))]
    (try (node-conn/get-node conn) (catch Exception _ nil))))

(defn register-network-handlers!
  []
  (wireless-handlers/register-link-handlers!
    {:message-domain :developer
     :get-linked-node get-linked-node-for-fusor
     :link! wireless-api/link-receiver-to-node!
     :unlink! wireless-api/unlink-receiver-from-node!
     :log-label "Imag Fusor wireless"})
  (log/info "Imaginary Fusor network handlers registered"))
