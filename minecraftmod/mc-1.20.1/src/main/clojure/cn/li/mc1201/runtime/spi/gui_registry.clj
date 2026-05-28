(ns cn.li.mc1201.runtime.spi.gui-registry
  "Platform-neutral GUI registry contract for loader-specific registry implementations.")

(def ^:private gui-registry-lock
  (Object.))

(def ^:private ^:dynamic *registry-impl*
  {})

(defn- registry-impl-snapshot
  []
  (var-get #'*registry-impl*))

(defn register-registry-impl!
  [platform {:keys [register-menu-type! get-menu-type list-menu-types invalidate-menu-registry!] :as impl}]
  (when-not (keyword? platform)
    (throw (ex-info "registry-api platform key must be keyword" {:platform platform})))
  (doseq [[k f] [[:register-menu-type! register-menu-type!]
                 [:get-menu-type get-menu-type]]]
    (when-not (fn? f)
      (throw (ex-info (str "registry-api requires " k " fn") {:platform platform :impl impl}))))
  (locking gui-registry-lock
    (alter-var-root #'*registry-impl*
                    assoc
                    platform
                    {:register-menu-type! register-menu-type!
                     :get-menu-type get-menu-type
                     :list-menu-types list-menu-types
                     :invalidate-menu-registry! invalidate-menu-registry!}))
  nil)

(defn- impl!
  [platform]
  (or (get (registry-impl-snapshot) platform)
      (throw (ex-info "GUI registry impl not installed" {:platform platform :installed (keys (registry-impl-snapshot))}))))

(defn register-menu-type!
  [platform gui-id menu-type]
  ((:register-menu-type! (impl! platform)) gui-id menu-type))

(defn get-menu-type
  [platform gui-id]
  ((:get-menu-type (impl! platform)) gui-id))

(defn list-menu-types
  [platform]
  (if-let [f (:list-menu-types (impl! platform))]
    (f)
    {}))

(defn invalidate-menu-registry!
  [platform]
  (when-let [f (:invalidate-menu-registry! (impl! platform))]
    (f))
  nil)
