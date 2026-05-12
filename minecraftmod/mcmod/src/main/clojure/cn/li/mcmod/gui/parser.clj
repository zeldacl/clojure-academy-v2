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
        slots-opts (:slots options)
        legacy-layout-opts (:legacy-layout options)

        slots-vec (mapv parse-slot (or (:slots legacy-layout-opts) (:slots options) []))
        buttons-vec (mapv parse-button (or (:buttons legacy-layout-opts) (:buttons options) []))
        labels-vec (mapv parse-label (or (:labels legacy-layout-opts) (:labels options) []))

        registration (schema/map->RegistrationConfig
                       {:display-name (or (:display-name registration-opts) (:display-name options))
                        :gui-type (or (:gui-type registration-opts) (:gui-type options))
                        :registry-name (or (:registry-name registration-opts) (:registry-name options))
                        :screen-factory-fn-kw (or (:screen-factory-fn-kw registration-opts) (:screen-factory-fn-kw options))
                        :slot-layout (or (:slot-layout registration-opts) (:slot-layout options))})

        lifecycle (schema/map->LifecycleHandlers
                    {:container-fn (or (:container-fn lifecycle-opts) (:container-fn options))
                     :container-predicate (or (:container-predicate lifecycle-opts) (:container-predicate options))
                     :screen-fn (or (:screen-fn lifecycle-opts) (:screen-fn options))
                     :tick-fn (or (:tick-fn lifecycle-opts) (:tick-fn options))})

        sync (schema/map->SyncConfig
               {:sync-get (or (:sync-get sync-opts) (:sync-get options))
                :sync-apply (or (:sync-apply sync-opts) (:sync-apply options))
                :payload-sync-apply-fn (or (:payload-sync-apply-fn sync-opts) (:payload-sync-apply-fn options))})

        operations (schema/map->OperationHandlers
                     {:validate-fn (or (:validate-fn operations-opts) (:validate-fn options))
                      :close-fn (or (:close-fn operations-opts) (:close-fn options))
                      :button-click-fn (or (:button-click-fn operations-opts) (:button-click-fn options))
                      :text-input-fn (or (:text-input-fn operations-opts) (:text-input-fn options))})

        slot-operations (schema/map->SlotOperations
                          {:slot-count-fn (or (:slot-count-fn slots-opts) (:slot-count-fn options))
                           :slot-get-fn (or (:slot-get-fn slots-opts) (:slot-get-fn options))
                           :slot-set-fn (or (:slot-set-fn slots-opts) (:slot-set-fn options))
                           :slot-can-place-fn (or (:slot-can-place-fn slots-opts) (:slot-can-place-fn options))
                           :slot-changed-fn (or (:slot-changed-fn slots-opts) (:slot-changed-fn options))})

        legacy-layout (schema/map->LegacyLayout
                        {:title (or (:title legacy-layout-opts) (:title options) "GUI")
                         :width (or (:width legacy-layout-opts) (:width options) default-gui-width)
                         :height (or (:height legacy-layout-opts) (:height options) default-gui-height)
                         :slots slots-vec
                         :buttons buttons-vec
                         :labels labels-vec
                         :background (or (:background legacy-layout-opts) (:background options) :default)})

        spec (schema/map->GuiSpec
               {:id gui-id
                :gui-id (:gui-id options)
                :registration registration
                :lifecycle lifecycle
                :sync sync
                :operations operations
                :slots slot-operations
                :legacy-layout legacy-layout
                :display-name (:display-name registration)
                :gui-type (:gui-type registration)
                :registry-name (:registry-name registration)
                :screen-factory-fn-kw (:screen-factory-fn-kw registration)
                :slot-layout (:slot-layout registration)
                :container-fn (:container-fn lifecycle)
                :container-predicate (:container-predicate lifecycle)
                :screen-fn (:screen-fn lifecycle)
                :tick-fn (:tick-fn lifecycle)
                :sync-get (:sync-get sync)
                :sync-apply (:sync-apply sync)
                :payload-sync-apply-fn (:payload-sync-apply-fn sync)
                :validate-fn (:validate-fn operations)
                :close-fn (:close-fn operations)
                :button-click-fn (:button-click-fn operations)
                :text-input-fn (:text-input-fn operations)
                :slot-count-fn (:slot-count-fn slot-operations)
                :slot-get-fn (:slot-get-fn slot-operations)
                :slot-set-fn (:slot-set-fn slot-operations)
                :slot-can-place-fn (:slot-can-place-fn slot-operations)
                :slot-changed-fn (:slot-changed-fn slot-operations)})]
    (validator/validate-gui-spec spec)
    spec))
