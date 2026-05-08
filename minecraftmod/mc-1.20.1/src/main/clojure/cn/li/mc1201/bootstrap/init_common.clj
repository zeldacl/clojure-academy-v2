(ns cn.li.mc1201.bootstrap.init-common
  "Shared platform init orchestration for Java entrypoints."
  (:require [cn.li.mcmod.platform.dispatch :as platform-dispatch]
            [cn.li.mcmod.platform.resource :as platform-resource]
            [cn.li.mcmod.platform.position :as platform-position]
            [cn.li.mcmod.platform.nbt :as platform-nbt]
            [cn.li.mcmod.platform.item :as platform-item]
            [cn.li.mcmod.util.log :as log]))

(defn set-platform-version!
  [platform-key]
  (alter-var-root #'platform-dispatch/*platform-version*
                  (constantly platform-key))
  (log/info "Set platform dispatch to" platform-key))

(defn assert-platform-ready!
  [platform-key]
  (let [checks [{:k :resource :ok (platform-resource/factory-initialized?)}
                {:k :position :ok (platform-position/factory-initialized?)}
                {:k :nbt :ok (platform-nbt/factory-initialized?)}
                {:k :item :ok (platform-item/factory-initialized?)}]
        missing (->> checks (remove :ok) (map :k) vec)]
    (when (seq missing)
      (throw (ex-info "Platform bootstrap incomplete - init-platform! must run before init-from-java"
                      {:platform platform-key
                       :missing missing})))))

(defn compilation-context?
  []
  (let [aot? (boolean *compile-files*)
        cphant? (boolean (System/getProperty "clojure.server.clojurephant"))
        check? (= "true" (System/getProperty "ac.check.clojure"))]
    {:aot aot?
     :clojurephant cphant?
     :ac-check check?
     :skip? (or aot? cphant? check?)}))

(defn init-from-java!
  [platform-key on-runtime-init]
  (let [{:keys [aot clojurephant ac-check skip?] :as ctx} (compilation-context?)]
    (log/info "[BOOTSTRAP_TRACE_INIT] init-from-java enter"
              {:platform platform-key
               :aot aot
               :clojurephant clojurephant
               :ac-check ac-check})
    (if skip?
      (log/info "[BOOTSTRAP_TRACE_INIT] skip content init during compilation/check" {:platform platform-key})
      (do
        (assert-platform-ready! platform-key)
        (set-platform-version! platform-key)
        (when (fn? on-runtime-init)
          (on-runtime-init))
        (log/info "Platform adapter initialized" {:platform platform-key :ctx (dissoc ctx :skip?)})))))
