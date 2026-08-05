(ns cn.li.neoforge262.integration.events.loot
  "Forge loot-table load event handlers." 
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.protocol.metadata :as registry-metadata])
  (:import [cn.li.neoforge262.loot LootInjectionHelper]
           [net.neoforged.neoforge.event LootTableLoadEvent]))

(defn handle-loot-table-load
  [^LootTableLoadEvent evt]
  (try
    (let [table-id (str (.getName evt))
          injections (registry-metadata/get-loot-injections-for-table table-id)]
      (when (seq injections)
        (doseq [spec injections]
          (LootInjectionHelper/addItemInjection
            evt
            (:item-id spec)
            (int (or (:weight spec) 1))
            (int (or (:quality spec) 0))
            (float (or (:min-count spec) 1.0))
            (float (or (:max-count spec) 1.0))))))
    (catch Throwable t
      (log/error "Error handling loot table load event:" (.getMessage t))
      (.printStackTrace t))))
