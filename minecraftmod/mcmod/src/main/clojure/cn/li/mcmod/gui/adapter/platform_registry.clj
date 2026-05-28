(ns cn.li.mcmod.gui.adapter.platform-registry
  "Platform callback registry for unified GUI adapter.")

(defn create-gui-platform-registry-runtime
  ([] (create-gui-platform-registry-runtime {}))
  ([{:keys [state*]}]
   {:cn.li.mcmod.gui.adapter.platform-registry/runtime ::gui-platform-registry-runtime
    :state* (or state* (atom nil))}))

(def ^:dynamic *gui-platform-registry-runtime* nil)

(defonce ^:private installed-gui-platform-registry-runtime
  (create-gui-platform-registry-runtime))

(defn- platform-impl-atom []
  (:state* (or *gui-platform-registry-runtime* installed-gui-platform-registry-runtime)))

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
                       :hint "Call cn.li.mcmod.gui.adapter/register-gui-platform-impl! during content init."})))
    (when-not (contains? m k)
      (throw (ex-info "Missing GUI platform callback"
                      {:missing-key k
                       :available-keys (keys m)})))
    (get m k)))

(defn invoke-platform!
  [k & args]
  (apply (platform-impl-fn! k) args))
