(ns cn.li.fabric1201.init
  "Fabric 1.20.1 initialization - sets version for multimethod dispatch"
  (:require [cn.li.mcmod.platform.dispatch :as platform-dispatch]
            [cn.li.mcmod.platform.resource :as platform-resource]
            [cn.li.mcmod.platform.position :as platform-position]
            [cn.li.mcmod.platform.nbt :as platform-nbt]
            [cn.li.mcmod.platform.item :as platform-item]
            [cn.li.mcmod.util.log :as log]))

(defn set-version!
  "Set the Fabric version for multimethod dispatch."
  []
  (alter-var-root #'platform-dispatch/*platform-version*
                  (constantly :fabric-1.20.1))
  (log/info "Set platform dispatch to :fabric-1.20.1"))

(defn- assert-platform-ready!
  []
  (let [checks [{:k :resource :ok (platform-resource/factory-initialized?)}
                {:k :position :ok (platform-position/factory-initialized?)}
                {:k :nbt :ok (platform-nbt/factory-initialized?)}
                {:k :item :ok (platform-item/factory-initialized?)}]
        missing (->> checks (remove :ok) (map :k) vec)]
    (when (seq missing)
      (throw (ex-info "Platform bootstrap incomplete - init-platform! must run before init-from-java"
                      {:platform :fabric-1.20.1
                       :missing missing})))))

(defn init-from-java
  "Called from Java ModInitializer to initialize Clojure environment."
  []
  (log/info "Initializing Fabric 1.20.1 adapter...")
  (assert-platform-ready!)
  (set-version!)
  (log/info "Fabric 1.20.1 adapter initialized"))
