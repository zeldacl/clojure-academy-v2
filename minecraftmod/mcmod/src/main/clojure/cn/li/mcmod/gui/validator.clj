(ns cn.li.mcmod.gui.validator
  "Validation rules for GuiSpec and nested GUI components."
  (:require [clojure.string :as str]
            [cn.li.mcmod.gui.schema :as schema]))

(defn validate-gui-spec
  [gui-spec]
  (when-not (and (:id gui-spec) (string? (:id gui-spec)) (not (str/blank? (:id gui-spec))))
    (throw (ex-info "GUI must have a non-empty string :id" {:id (:id gui-spec) :spec gui-spec})))

  (when (some? (:gui-id gui-spec))
    (let [registration (:registration gui-spec)]
      (when-not (integer? (:gui-id gui-spec))
        (throw (ex-info "GUI :gui-id must be an integer when provided" {:gui-id (:gui-id gui-spec) :id (:id gui-spec)})))
      (when-not (and (string? (:registry-name registration)) (not (str/blank? (:registry-name registration))))
        (throw (ex-info "GUI :registry-name must be a non-empty string when :gui-id is present"
                        {:id (:id gui-spec) :gui-id (:gui-id gui-spec) :registry-name (:registry-name registration)})))
      (when-not (keyword? (:gui-type registration))
        (throw (ex-info "GUI :gui-type must be a keyword when :gui-id is present"
                        {:id (:id gui-spec) :gui-id (:gui-id gui-spec) :gui-type (:gui-type registration)})))
      (when-not (keyword? (:screen-factory-fn-kw registration))
        (throw (ex-info "GUI :screen-factory-fn-kw must be a keyword when :gui-id is present"
                        {:id (:id gui-spec) :gui-id (:gui-id gui-spec) :screen-factory-fn-kw (:screen-factory-fn-kw registration)})))))

  (let [legacy-layout (:legacy-layout gui-spec)]
    (doseq [slot (:slots legacy-layout)]
      (when-not (number? (:index slot))
        (throw (ex-info "Slot must have an :index number" {:slot slot}))))
    (doseq [button (:buttons legacy-layout)]
      (when-not (number? (:id button))
        (throw (ex-info "Button must have an :id number" {:button button})))))

  true)
