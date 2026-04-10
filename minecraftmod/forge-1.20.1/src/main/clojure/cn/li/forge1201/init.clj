(ns cn.li.forge1201.init
  "Forge 1.20.1 initialization and version-specific implementations"
  (:require [cn.li.mcmod.platform.dispatch :as platform-dispatch]
            [cn.li.mcmod.platform.events :as platform-events]
            [cn.li.mcmod.lifecycle :as lifecycle]
            [cn.li.mcmod.content :as content]
            [cn.li.forge1201.wireless-imc :as wireless-imc]
            [cn.li.mcmod.util.log :as log]))

(defn set-version!
  "Set the forge version for multimethod dispatch"
  []
  (alter-var-root #'cn.li.mcmod.platform.dispatch/*platform-version*
                  (constantly :forge-1.20.1))
  (log/info "Set platform dispatch to :forge-1.20.1"))

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
        (set-version!)
        ;; Bind platform-neutral event bridge to Forge IMC dispatcher.
        (alter-var-root #'platform-events/*fire-event-fn*
                        (constantly wireless-imc/dispatch-event!))
        ;; Ensure shared content init is registered (without Forge referencing
        ;; the shared content namespace directly).
        (content/ensure-content-init-registered!)
        (lifecycle/run-content-init!)))))
