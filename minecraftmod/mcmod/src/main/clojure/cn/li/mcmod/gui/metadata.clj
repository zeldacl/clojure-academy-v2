(ns cn.li.mcmod.gui.metadata
  "Centralized GUI metadata API built on top of mcmod.gui.dsl."
  (:require [cn.li.mcmod.gui.dsl :as gui-dsl]
            [cn.li.mcmod.util.log :as log]))

(defn register-gui!
  "Register GUI metadata via gui-dsl."
  [gui-key {:keys [id display-name type registry-name screen-fn-kw slot-layout] :as cfg}]
  (when-not (integer? id)
    (throw (ex-info "register-gui!: :id must be an integer" {:gui-key gui-key :cfg cfg})))
  (log/info "Registering GUI metadata" {:gui-key gui-key :id id})
  (gui-dsl/register-gui!
    (gui-dsl/create-gui-spec (name gui-key)
                             {:gui-id id
                              :display-name display-name
                              :gui-type type
                              :registry-name registry-name
                              :screen-factory-fn-kw screen-fn-kw
                              :slot-layout slot-layout}))
  nil)

(defn get-all-gui-ids []
  (gui-dsl/get-all-gui-ids))

(defn valid-gui-id? [gui-id]
  (gui-dsl/has-gui-id? gui-id))

(defn get-display-name [gui-id]
  (or (gui-dsl/get-display-name gui-id)
      "Unknown GUI"))

(defn get-gui-type [gui-id]
  (or (gui-dsl/get-gui-type gui-id)
      :unknown))

(defn get-registry-name [gui-id]
  (or (gui-dsl/get-registry-name gui-id)
      "unknown_gui"))

(defn get-screen-factory-fn [gui-id]
  (gui-dsl/get-screen-factory-fn-kw gui-id))

(defn get-slot-layout [gui-id]
  (gui-dsl/get-slot-layout gui-id))

(defn get-slot-range [gui-id section]
  (gui-dsl/get-slot-range gui-id section))

(defn get-gui-id-for-type [container-type]
  (gui-dsl/get-gui-id-for-type container-type))

(defonce platform-menu-types
  ^{:doc "Platform-specific menu type registry: {:platform {:gui-id menu-type}}"}
  (atom {}))

(defn register-menu-type! [platform gui-id menu-type]
  (swap! platform-menu-types assoc-in [platform gui-id] menu-type)
  (log/info "Registered MenuType for" platform "GUI" gui-id))

(defn get-menu-type [platform gui-id]
  (get-in @platform-menu-types [platform gui-id]))

(defn unregister-menu-types! []
  (reset! platform-menu-types {}))

(defn validate-gui-metadata []
  (let [errors (atom [])]
    (doseq [gui-id (get-all-gui-ids)]
      (let [display-name (gui-dsl/get-display-name gui-id)
            gui-type (gui-dsl/get-gui-type gui-id)
            registry-name (gui-dsl/get-registry-name gui-id)
            screen-factory-fn-kw (gui-dsl/get-screen-factory-fn-kw gui-id)]
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
