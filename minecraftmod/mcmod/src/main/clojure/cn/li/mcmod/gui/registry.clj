(ns cn.li.mcmod.gui.registry
  "GUI registry and query API."
  (:require [cn.li.mcmod.util.log :as log]))

(defn- default-gui-registry-runtime-state []
  {:by-id {} :by-gui-id {}})

(defn create-gui-registry-runtime
  ([] (create-gui-registry-runtime {}))
  ([{:keys [state*]}]
   {:cn.li.mcmod.gui.registry/runtime ::gui-registry-runtime
    :state* (or state* (atom (default-gui-registry-runtime-state)))}))

(def ^:dynamic *gui-registry-runtime* nil)

(defonce ^:private installed-gui-registry-runtime
  (create-gui-registry-runtime))

(defn- gui-registry-atom []
  (:state* (or *gui-registry-runtime* installed-gui-registry-runtime)))

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
  (some-> (get-gui-by-gui-id gui-id)
          (get-in [:registration :registry-name])))

(defn get-screen-factory-fn-kw [gui-id]
  (some-> (get-gui-by-gui-id gui-id)
          (get-in [:registration :screen-factory-fn-kw])))

(defn get-gui-type [gui-id]
  (some-> (get-gui-by-gui-id gui-id)
          (get-in [:registration :gui-type])))

(defn get-slot-layout [gui-id]
  (some-> (get-gui-by-gui-id gui-id)
          (get-in [:registration :slot-layout])))

(defn get-display-name [gui-id]
  (some-> (get-gui-by-gui-id gui-id)
          (get-in [:registration :display-name])))

(defn get-container-fn [gui-id]
  (some-> (get-gui-by-gui-id gui-id)
          (get-in [:lifecycle :container-fn])))

(defn get-screen-fn [gui-id]
  (some-> (get-gui-by-gui-id gui-id)
          (get-in [:lifecycle :screen-fn])))

(defn get-container-predicate [gui-id]
  (some-> (get-gui-by-gui-id gui-id)
          (get-in [:lifecycle :container-predicate])))

(defn get-payload-sync-apply-fn [gui-id]
  (some-> (get-gui-by-gui-id gui-id)
          (get-in [:sync :payload-sync-apply-fn])))

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
