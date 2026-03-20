(ns cn.li.forge1201.datagen.event-handler
  "Forge 1.20.1 DataGenerator Event Handler
   
   Pure Clojure implementation. The actual event handling is done here,
   while Java wrapper (DataGeneratorSetup.java) only serves as annotation container.
   
   Key insight: Java annotations are metadata; the real logic is in Clojure.
   The wrapper class with @Mod.EventBusSubscriber just delegates to this namespace."
  (:require [cn.li.forge1201.datagen.setup :as dg-setup]
            [cn.li.ac.config.modid :as modid])
  (:import [net.minecraftforge.data.event GatherDataEvent]))

;; ============================================================================
;; Event Handler Logic
;; ============================================================================

(defn handle-gather-data-event
  "Handle GatherDataEvent - register all data providers
   
   This is the core business logic, separated from Java annotations.
   Called by Java wrapper class with @Mod.EventBusSubscriber.
   
   Args:
     event: GatherDataEvent from Forge"
  [^GatherDataEvent event]
  (try
    (let [generator (.getGenerator event)
          exfile-helper (.getExistingFileHelper event)]
      
      (println (str "[" modid/MOD-ID "] Gathering data generators..."))
      (dg-setup/-gatherData event)
      (println (str "[" modid/MOD-ID "] DataGenerator event processed!")))
    
    (catch Exception e
      (println (str "Error handling GatherDataEvent: " e))
      (.printStackTrace e))))

;; ============================================================================
;; Static Wrapper (called by Java)
;; ============================================================================

(defn static-gather-data
  "Static entry point for Java wrapper class
   
   Args:
     event: GatherDataEvent"
  [event]
  (handle-gather-data-event event))

