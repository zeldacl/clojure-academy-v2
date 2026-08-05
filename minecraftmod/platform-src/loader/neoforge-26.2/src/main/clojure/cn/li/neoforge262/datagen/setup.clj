(ns cn.li.neoforge262.datagen.setup
  "NeoForge 26.2 GatherDataEvent wiring.

  ExistingFileHelper is gone. Server datapack providers vs Client asset providers
  are split across GatherDataEvent.Server / .Client."
  (:require [cn.li.mc262.datagen.advancement-provider-shell :as adv-shell]
            [cn.li.mc262.datagen.blockstate-provider-shell :as blockstate-shell]
            [cn.li.mc262.datagen.lang-provider-shell :as lang-shell]
            [cn.li.mc262.datagen.setup-common :as setup-common]
            [cn.li.mc262.datagen.worldgen-provider-shell :as worldgen-shell]
            [cn.li.neoforge262.datagen.item-model-provider :as item-model-provider]
            [cn.li.neoforge262.datagen.recipe-provider :as recipe-provider])
  (:import [net.neoforged.neoforge.data.event GatherDataEvent
            GatherDataEvent$Client GatherDataEvent$Server]))

(def ^:private blockstate-provider-name
  "NeoForge Blockstate Provider")

(defn -gatherData
  "Event handler for GatherDataEvent (Client or Server subclass)."
  [^GatherDataEvent event]
  (try
    (setup-common/ensure-content-loaded!)
    (let [pack-output (.getPackOutput (.getGenerator event))]
      (cond
        (instance? GatherDataEvent$Server event)
        (let [lookup (.getLookupProvider event)]
          (.addProvider event (recipe-provider/create pack-output lookup))
          (.addProvider event (adv-shell/create pack-output))
          (.addProvider event (worldgen-shell/create pack-output :forge)))

        (instance? GatherDataEvent$Client event)
        (do
          (.addProvider event (lang-shell/create pack-output))
          (.addProvider event (blockstate-shell/create-provider pack-output blockstate-provider-name))
          (.addProvider event (item-model-provider/create pack-output)))))
    (catch Throwable e
      (println (str "Error handling GatherDataEvent: " e))
      (.printStackTrace e))))

(defn static-gather-data
  "Static entry point used by Java listener."
  [^GatherDataEvent event]
  (-gatherData event))
