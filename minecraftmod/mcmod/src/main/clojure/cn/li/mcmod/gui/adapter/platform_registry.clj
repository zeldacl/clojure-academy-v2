(ns cn.li.mcmod.gui.adapter.platform-registry
  "Platform callback registry for GUI container operations.")

(defn create-gui-platform-registry-runtime
  ([] (create-gui-platform-registry-runtime {}))
  ([{:keys [state*]}]
   {:cn.li.mcmod.gui.adapter.platform-registry/runtime ::gui-platform-registry-runtime
    :state* (or state* (atom nil))}))

(def ^:private _gui-platform-registry-runtime (delay (create-gui-platform-registry-runtime)))

(def ^:dynamic *gui-platform-registry-runtime* nil)

(defn- platform-impl-atom []
  (:state* (or *gui-platform-registry-runtime*
                  @_gui-platform-registry-runtime)))

(defn register-gui-platform-impl!
  [impl-map]
  (when-not (map? impl-map)
    (throw (ex-info "Expected map for register-gui-platform-impl!"
                    {:impl-map-type (type impl-map)})))
  (reset! (platform-impl-atom) impl-map)
  nil)

(defn platform-impl-fn!
  [k]
  (let [m @(platform-impl-atom)]
    (when-not m
      (throw (ex-info "GUI platform implementation not registered"
                      {:missing-key k
                       :hint "Call register-gui-platform-impl! during content init."})))
    (when-not (contains? m k)
      (throw (ex-info "Missing GUI platform callback"
                      {:missing-key k
                       :available-keys (keys m)})))
    (get m k)))

(defn invoke-platform!
  [k & args]
  (apply (platform-impl-fn! k) args))

(defn server-menu-sync! [container] (invoke-platform! :server-menu-sync! container))
(defn safe-validate [container player] (invoke-platform! :safe-validate container player))
(defn safe-close! [container] (invoke-platform! :safe-close! container))
(defn slot-count [container] (invoke-platform! :slot-count container))
(defn slot-get-item [container idx] (invoke-platform! :slot-get-item container idx))
(defn slot-set-item! [container idx item] (invoke-platform! :slot-set-item! container idx item))
(defn slot-changed! [container idx] (invoke-platform! :slot-changed! container idx))
(defn slot-can-place? [container idx stack] (invoke-platform! :slot-can-place? container idx stack))
(defn get-container-type [container] (invoke-platform! :get-container-type container))
(defn get-gui-id-for-container [container] (invoke-platform! :get-gui-id-for-container container))
(defn execute-quick-move-forge [menu container slot-index slot stack]
  (invoke-platform! :execute-quick-move-forge menu container slot-index slot stack))
