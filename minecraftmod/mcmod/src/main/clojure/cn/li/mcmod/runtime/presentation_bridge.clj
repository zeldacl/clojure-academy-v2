(ns cn.li.mcmod.runtime.presentation-bridge
  "Version-neutral game bridge for Presentation Runtime.

   mcmod owns this seam because it contains shared Minecraft domain protocols.
   It must not import Forge, Fabric or NeoForge; those adapters stay in
   minecraft/base and loader modules." )

(def host-kinds #{:hud :world-ui :vfx :first-person :camera :post-process :screen})

(defn host-descriptor [id kind width height input-policy]
  (when-not (contains? host-kinds kind)
    (throw (ex-info "unknown presentation host" {:kind kind})))
  {:id id :kind kind :width width :height height :input-policy input-policy})

(defn snapshot [revision values]
  {:revision revision :values values})

(defn action-codec [encode decode max-bytes]
  {:encode (fn [action payload]
             (let [bytes (encode action payload)]
               (when (> (count bytes) max-bytes)
                 (throw (ex-info "presentation action exceeds size limit" {:size (count bytes)})))
               bytes))
   :decode decode
   :max-bytes max-bytes})

(defn validate-action [allowed-actions action]
  (when-not (contains? allowed-actions action)
    (throw (ex-info "presentation action rejected" {:action action})))
  action)
