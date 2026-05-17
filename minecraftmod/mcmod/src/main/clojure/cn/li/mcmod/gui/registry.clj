(ns cn.li.mcmod.gui.registry
  "GUI registry and query API."
  (:require [cn.li.mcmod.util.log :as log]))

(defonce ^{:doc "Registry for GUI specs.

Structure:
- :by-id       {string-id -> GuiSpec}
- :by-gui-id   {int-gui-id -> GuiSpec} (wireless/platform-visible GUIs only)"}
  gui-registry
  (atom {:by-id {} :by-gui-id {}}))

(defn register-gui! [gui-spec]
  (log/info "Registering GUI:" (:id gui-spec))
  (swap! gui-registry
         (fn [reg]
           (let [id (:id gui-spec)
                 gui-id (:gui-id gui-spec)]
             (when (and (some? gui-id) (contains? (:by-gui-id reg) gui-id))
               nil)
             (cond-> reg
               true (assoc-in [:by-id id] gui-spec)
               (some? gui-id) (assoc-in [:by-gui-id gui-id] gui-spec)))))
  gui-spec)

(defn get-gui [gui-id]
  (get-in @gui-registry [:by-id gui-id]))

(defn list-guis []
  (keys (:by-id @gui-registry)))

(defn get-gui-by-gui-id [gui-id]
  (get-in @gui-registry [:by-gui-id gui-id]))

(defn list-gui-ids []
  (keys (:by-gui-id @gui-registry)))

(defn get-all-gui-ids []
  (seq (list-gui-ids)))

(defn has-gui-id? [gui-id]
  (contains? (:by-gui-id @gui-registry) gui-id))

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
        (:by-gui-id @gui-registry)))

(defn get-gui-id-for-type [gui-type]
  (some (fn [[gui-id spec]]
          (when (= (get-in spec [:registration :gui-type]) gui-type)
            gui-id))
        (:by-gui-id @gui-registry)))

(defn get-config-by-container [container]
  (some (fn [[_gui-id spec]]
          (when-let [pred (get-in spec [:lifecycle :container-predicate])]
            (when (pred container)
              spec)))
        (:by-gui-id @gui-registry)))
