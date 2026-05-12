(ns cn.li.mcmod.gui.adapter.platform-registry
  "Platform callback registry for unified GUI adapter.")

(defonce ^:private platform-impl
  (atom nil))

(defn register-gui-platform-impl!
  [impl-map]
  (when-not (map? impl-map)
    (throw (ex-info "Expected map for register-gui-platform-impl!"
                    {:impl-map-type (type impl-map)})))
  (reset! platform-impl impl-map)
  nil)

(defn platform-impl-fn!
  [k]
  (let [m @platform-impl]
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
