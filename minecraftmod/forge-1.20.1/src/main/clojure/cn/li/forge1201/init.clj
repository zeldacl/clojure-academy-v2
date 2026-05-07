(ns cn.li.forge1201.init
  "Forge 1.20.1 initialization and version-specific implementations"
  (:require [cn.li.mcmod.platform.dispatch :as platform-dispatch]
            [cn.li.mcmod.platform.events :as platform-events]
            [cn.li.mcmod.platform.resource :as platform-resource]
            [cn.li.mcmod.platform.position :as platform-position]
            [cn.li.mcmod.platform.nbt :as platform-nbt]
            [cn.li.mcmod.platform.item :as platform-item]
            [cn.li.mcmod.lifecycle :as lifecycle]
            [cn.li.mcmod.content :as content]
            [cn.li.forge1201.event-imc :as event-imc]
            [cn.li.forge1201.achievement.bridge :as achievement-bridge]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.forge1201.trigger ModTriggers]))

(defn set-version!
  "Set the forge version for multimethod dispatch"
  []
  (alter-var-root #'cn.li.mcmod.platform.dispatch/*platform-version*
                  (constantly :forge-1.20.1))
  (log/info "Set platform dispatch to :forge-1.20.1"))

(defn- assert-platform-ready!
  []
  (let [checks [{:k :resource :ok (platform-resource/factory-initialized?)}
                {:k :position :ok (platform-position/factory-initialized?)}
                {:k :nbt :ok (platform-nbt/factory-initialized?)}
                {:k :item :ok (platform-item/factory-initialized?)}]
        missing (->> checks (remove :ok) (map :k) vec)]
    (when (seq missing)
      (throw (ex-info "Platform bootstrap incomplete - init-platform! must run before init-from-java"
                      {:platform :forge-1.20.1
                       :missing missing})))))

(defn init-from-java
  "Called from Java @Mod constructor - sets up version dispatch"
  []
  (let [aot? (boolean *compile-files*)
        cphant? (boolean (System/getProperty "clojure.server.clojurephant"))
        check? (= "true" (System/getProperty "ac.check.clojure"))]
    (log/info "[BOOTSTRAP_TRACE_INIT] init-from-java enter"
              {:aot aot?
               :clojurephant cphant?
               :ac-check check?})
    (if (or aot? cphant? check?)
      (log/info "[BOOTSTRAP_TRACE_INIT] skip content init during compilation/check")
      (do
        (log/info "Initializing Forge 1.20.1 adapter")
        (assert-platform-ready!)
        (set-version!)
        ;; Bind platform-neutral event bridge to Forge IMC dispatcher.
        (alter-var-root #'platform-events/*fire-event-fn*
            (constantly event-imc/dispatch-event!))
        ;; Register custom advancement triggers.
        (ModTriggers/init)
        ;; Ensure shared content init is registered (without Forge referencing
        ;; the shared content namespace directly).
        (content/ensure-content-init-registered!)
        (lifecycle/run-content-init!)
        (achievement-bridge/init!)))))
