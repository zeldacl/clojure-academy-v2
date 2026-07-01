(ns cn.li.mcmod.gui.registry
  "GUI registry, metadata queries, and screen factory registration."
  (:require [cn.li.mcmod.util.log :as log]))

(defn- default-gui-registry-runtime-state []
  {:by-id {}
   :by-gui-id {}
   :screen-factories {}})

(defn create-gui-registry-runtime
  ([] (create-gui-registry-runtime {}))
  ([{:keys [state*]}]
   {:cn.li.mcmod.gui.registry/runtime ::gui-registry-runtime
    :state* (or state* (atom (default-gui-registry-runtime-state)))}))

(def ^:private _gui-registry-runtime (delay (create-gui-registry-runtime)))

(def ^:dynamic *gui-registry-runtime* nil)

(defn- gui-registry-atom []
  (:state* (or *gui-registry-runtime*
                  @_gui-registry-runtime)))

(defn- gui-registry-snapshot []
  @(gui-registry-atom))

(defn register-gui! [gui-spec]
  (log/info "Registering GUI:" (:id gui-spec))
  (swap! (gui-registry-atom)
         (fn [reg]
           (let [id (:id gui-spec)
                 gui-id (:gui-id gui-spec)
                 existing-by-id (get-in reg [:by-id id])
                 existing-by-gui-id (when (some? gui-id)
                                      (get-in reg [:by-gui-id gui-id]))]
             (when existing-by-id
               (throw (ex-info "Duplicate GUI registry id"
                               {:id id
                                :existing existing-by-id
                                :new gui-spec})))
             (when existing-by-gui-id
               (throw (ex-info "Duplicate platform GUI id"
                               {:gui-id gui-id
                                :existing-id (:id existing-by-gui-id)
                                :new-id id})))
             (cond-> reg
               true (assoc-in [:by-id id] gui-spec)
               (some? gui-id) (assoc-in [:by-gui-id gui-id] gui-spec)))))
  gui-spec)

(defn register-screen-factory!
  [screen-fn-kw screen-fn]
  (swap! (gui-registry-atom) assoc-in [:screen-factories (keyword screen-fn-kw)] screen-fn)
  nil)

(defn get-screen-factory-fn
  [screen-fn-kw]
  (if-let [f (get-in (gui-registry-snapshot) [:screen-factories (keyword screen-fn-kw)])]
    f
    (throw (ex-info "Screen factory not registered" {:screen-fn-kw screen-fn-kw}))))

(defn get-gui [gui-id]
  (get-in (gui-registry-snapshot) [:by-id gui-id]))

(defn list-guis []
  (keys (:by-id (gui-registry-snapshot))))

(defn get-gui-by-gui-id [gui-id]
  (get-in (gui-registry-snapshot) [:by-gui-id gui-id]))

(defn list-gui-ids []
  (keys (:by-gui-id (gui-registry-snapshot))))

(defn get-all-gui-ids []
  (seq (list-gui-ids)))

(defn has-gui-id? [gui-id]
  (contains? (:by-gui-id (gui-registry-snapshot)) gui-id))

(defn get-registry-name [gui-id]
  (some-> (get-gui-by-gui-id gui-id) (get-in [:registration :registry-name])))

(defn get-screen-factory-fn-kw [gui-id]
  (some-> (get-gui-by-gui-id gui-id) (get-in [:registration :screen-factory-fn-kw])))

(defn get-gui-type [gui-id]
  (some-> (get-gui-by-gui-id gui-id) (get-in [:registration :gui-type])))

(defn get-slot-layout [gui-id]
  (some-> (get-gui-by-gui-id gui-id) (get-in [:registration :slot-layout])))

(defn get-display-name [gui-id]
  (some-> (get-gui-by-gui-id gui-id) (get-in [:registration :display-name])))

(defn get-container-fn [gui-id]
  (some-> (get-gui-by-gui-id gui-id) (get-in [:lifecycle :container-fn])))

(defn get-server-menu-sync-fn [gui-id]
  (some-> (get-gui-by-gui-id gui-id) (get-in [:lifecycle :server-menu-sync-fn])))

(defn get-screen-fn [gui-id]
  (some-> (get-gui-by-gui-id gui-id) (get-in [:lifecycle :screen-fn])))

(defn get-container-predicate [gui-id]
  (some-> (get-gui-by-gui-id gui-id) (get-in [:lifecycle :container-predicate])))

(defn get-slot-range [gui-id section]
  (if-let [layout (get-slot-layout gui-id)]
    (get-in layout [:ranges section] [0 0])
    [0 0]))

(defn get-gui-by-type [gui-type]
  (some (fn [[_gui-id spec]]
          (when (= (get-in spec [:registration :gui-type]) gui-type)
            spec))
        (:by-gui-id (gui-registry-snapshot))))

(defn get-gui-id-for-type [gui-type]
  (some (fn [[gui-id spec]]
          (when (= (get-in spec [:registration :gui-type]) gui-type)
            gui-id))
        (:by-gui-id (gui-registry-snapshot))))

(defn get-config-by-container [container]
  (some (fn [[_gui-id spec]]
          (when-let [pred (get-in spec [:lifecycle :container-predicate])]
            (when (pred container)
              spec)))
        (:by-gui-id (gui-registry-snapshot))))

(defn validate-gui-metadata []
  (let [errors (atom [])]
    (doseq [gui-id (get-all-gui-ids)]
      (let [display-name (get-display-name gui-id)
            gui-type (get-gui-type gui-id)
            registry-name (get-registry-name gui-id)
            screen-factory-fn-kw (get-screen-factory-fn-kw gui-id)]
        (when-not display-name
          (swap! errors conj (str "Missing display name for GUI ID " gui-id)))
        (when-not gui-type
          (swap! errors conj (str "Missing type for GUI ID " gui-id)))
        (when-not registry-name
          (swap! errors conj (str "Missing registry name for GUI ID " gui-id)))
        (when-not screen-factory-fn-kw
          (swap! errors conj (str "Missing screen-factory-fn-kw for GUI ID " gui-id)))))
    @errors))

(defn assert-valid-metadata! []
  (let [errors (validate-gui-metadata)]
    (when (seq errors)
      (throw (ex-info "Invalid GUI metadata" {:errors errors})))))
