(ns cn.li.mcmod.gui.parser
  "Parse/build helpers for GUI specifications."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.gui.schema :as schema]
            [cn.li.mcmod.gui.validator :as validator]))

(def default-gui-width 176)
(def default-gui-height 166)
(def default-button-width 60)
(def default-button-height 20)

(defn any-item-filter [_item] true)

(defn parse-slot [slot-map]
  (schema/map->SlotSpec
    {:index (:index slot-map)
     :x (:x slot-map 0)
     :y (:y slot-map 0)
     :filter (or (:filter slot-map) any-item-filter)
     :on-change (or (:on-change slot-map) (fn [_ _] nil))}))

(defn parse-button [button-map]
  (schema/map->ButtonSpec
    {:id (:id button-map)
     :x (:x button-map 0)
     :y (:y button-map 0)
     :width (or (:width button-map) default-button-width)
     :height (or (:height button-map) default-button-height)
     :text (:text button-map "Button")
     :on-click (or (:on-click button-map) (fn [] (log/info "Button clicked")))}))

(defn parse-label [label-map]
  (schema/map->LabelSpec
    {:x (:x label-map 0)
     :y (:y label-map 0)
     :text (:text label-map "")
     :color (or (:color label-map) 0x404040)}))

(defn create-gui-spec [gui-id options]
  (let [registration-opts (:registration options)
        lifecycle-opts (:lifecycle options)
        sync-opts (:sync options)
        operations-opts (:operations options)
        slot-operations-opts (:slot-operations options)
        layout-opts (:layout options)

        slots-vec (mapv parse-slot (or (:slots layout-opts)
                     (when (vector? (:slots options)) (:slots options))
                     []))
        buttons-vec (mapv parse-button (or (:buttons layout-opts) (:buttons options) []))
        labels-vec (mapv parse-label (or (:labels layout-opts) (:labels options) []))

        registration (schema/map->RegistrationConfig
                 {:display-name (:display-name registration-opts)
                :gui-type (:gui-type registration-opts)
                :registry-name (:registry-name registration-opts)
                :screen-factory-fn-kw (:screen-factory-fn-kw registration-opts)
                :slot-layout (:slot-layout registration-opts)})

        lifecycle (schema/map->LifecycleHandlers
              {:container-fn (:container-fn lifecycle-opts)
               :container-predicate (:container-predicate lifecycle-opts)
               :screen-fn (:screen-fn lifecycle-opts)
               :tick-fn (:tick-fn lifecycle-opts)})

        sync (schema/map->SyncConfig
             {:sync-get (:sync-get sync-opts)
            :sync-apply (:sync-apply sync-opts)
            :payload-sync-apply-fn (:payload-sync-apply-fn sync-opts)})

        operations (schema/map->OperationHandlers
               {:validate-fn (:validate-fn operations-opts)
                :close-fn (:close-fn operations-opts)
                :button-click-fn (:button-click-fn operations-opts)
                :text-input-fn (:text-input-fn operations-opts)})

        slot-operations (schema/map->SlotOperations
                          {:slot-count-fn (:slot-count-fn slot-operations-opts)
                           :slot-get-fn (:slot-get-fn slot-operations-opts)
                           :slot-set-fn (:slot-set-fn slot-operations-opts)
                           :slot-can-place-fn (:slot-can-place-fn slot-operations-opts)
                           :slot-changed-fn (:slot-changed-fn slot-operations-opts)})

        layout (schema/map->Layout
           {:title (or (:title layout-opts) (:title options) "GUI")
            :width (or (:width layout-opts) (:width options) default-gui-width)
            :height (or (:height layout-opts) (:height options) default-gui-height)
            :slots slots-vec
            :buttons buttons-vec
            :labels labels-vec
            :background (or (:background layout-opts) (:background options) :default)})

        spec (schema/map->GuiSpec
               {:id gui-id
                :gui-id (:gui-id options)
                :registration registration
                :lifecycle lifecycle
                :sync sync
                :operations operations
                :slot-operations slot-operations
                    :layout layout})]
    (validator/validate-gui-spec spec)
    spec))
