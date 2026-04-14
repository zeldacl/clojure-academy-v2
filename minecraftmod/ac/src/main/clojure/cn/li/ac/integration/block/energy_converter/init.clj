(ns cn.li.ac.integration.block.energy-converter.init
  (:require [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.integration.block.energy-converter.base :as ec-base]
            [cn.li.ac.block.energy-converter.wireless-impl :as ec-wireless]
            [cn.li.ac.integration.block.energy-converter.rf-input :as rf-in]
            [cn.li.ac.integration.block.energy-converter.rf-output :as rf-out]
            [cn.li.ac.integration.block.energy-converter.eu-input :as eu-in]
            [cn.li.ac.integration.block.energy-converter.eu-output :as eu-out])
  (:import [cn.li.acapi.energy IEnergyCapable]
           [cn.li.acapi.wireless IWirelessGenerator IWirelessReceiver]))

(defonce ^:private installed? (atom false))

(defn- converter-block?
  [block-id]
  (contains? #{"rf-input" "rf-output" "eu-input" "eu-output"} (str block-id)))

(defn- output-converter?
  [block-id]
  (contains? #{"rf-output" "eu-output"} (str block-id)))

(defn- input-converter?
  [block-id]
  (contains? #{"rf-input" "eu-input"} (str block-id)))

(defn- open-converter-gui!
  [{:keys [player world pos sneaking] :as _ctx}]
  (when (and player world pos (not sneaking))
    (try
      (if-let [open-gui-by-type (requiring-resolve 'cn.li.ac.wireless.gui.registry/open-gui-by-type)]
        (open-gui-by-type player :energy-converter world pos)
        (do
          (log/error "Energy Converter GUI open fn not found")
          nil))
      (catch Exception e
        (log/error "Failed to open Energy Converter GUI:" (ex-message e))
        nil))))

(defn- register-block! [id registry-name texture-name]
  (bdsl/register-block!
    (bdsl/create-block-spec
      id
      {:registry-name registry-name
       :physical {:material :metal
                  :hardness 3.0
                  :resistance 6.0
                  :requires-tool true
                  :harvest-tool :pickaxe
                  :harvest-level 1
                  :sounds :metal}
                :rendering {:model-parent "minecraft:block/cube_all"
                       :textures {:all (modid/asset-path "block" texture-name)}
                       :flat-item-icon? true}
                :events {:on-right-click open-converter-gui!}})))

(defn- register-tile! [tile-id registry-name block-id]
  (tdsl/register-tile!
    (tdsl/create-tile-spec
      tile-id
      {:registry-name registry-name
       :impl :scripted
       :blocks [block-id]
       :read-nbt-fn ec-base/read-nbt-fn
       :write-nbt-fn ec-base/write-nbt-fn})))

(defn load-converters! []
  (register-block! rf-in/block-id rf-in/registry-name rf-in/texture)
  (register-block! rf-out/block-id rf-out/registry-name rf-out/texture)
  (register-block! eu-in/block-id eu-in/registry-name eu-in/texture)
  (register-block! eu-out/block-id eu-out/registry-name eu-out/texture)
  (log/info "Loaded integration converter blocks"))

(defn- make-wireless-generator
  [be]
  (ec-wireless/create-wireless-generator
    be
    (fn [] (or (platform-be/get-custom-state be) {}))
    (fn [s]
      (platform-be/set-custom-state! be s)
      (platform-be/set-changed! be))))

(defn- make-wireless-receiver
  [be]
  (ec-wireless/create-wireless-receiver
    be
    (fn [] (or (platform-be/get-custom-state be) {}))
    (fn [s]
      (platform-be/set-custom-state! be s)
      (platform-be/set-changed! be))))

(defn- compose-capability-factory
  [existing-factory converter-factory-pred]
  (fn [be side]
    (let [block-id (str (platform-be/get-block-id be))]
      (if (converter-block? block-id)
        (converter-factory-pred be block-id)
        (when existing-factory
          (existing-factory be side))))))

(defn init-converters! []
  (when (compare-and-set! installed? false true)
    (register-tile! rf-in/block-id rf-in/registry-name rf-in/block-id)
    (register-tile! rf-out/block-id rf-out/registry-name rf-out/block-id)
    (register-tile! eu-in/block-id eu-in/registry-name eu-in/block-id)
    (register-tile! eu-out/block-id eu-out/registry-name eu-out/block-id)

    (platform-cap/declare-capability! :energy-converter IEnergyCapable
      (fn [be _side]
        (ec-base/make-energy-capability be (platform-be/get-block-id be))))

    ;; Extend global wireless generator/receiver capability factories so converter
    ;; tiles participate in wireless links without breaking existing block behavior.
    (let [existing-gen-factory (platform-cap/get-handler-factory :wireless-generator)
          existing-recv-factory (platform-cap/get-handler-factory :wireless-receiver)]
      (platform-cap/declare-capability! :wireless-generator IWirelessGenerator
        (compose-capability-factory
          existing-gen-factory
          (fn [be block-id]
            (when (output-converter? block-id)
              (make-wireless-generator be)))))
      (platform-cap/declare-capability! :wireless-receiver IWirelessReceiver
        (compose-capability-factory
          existing-recv-factory
          (fn [be block-id]
            (when (input-converter? block-id)
              (make-wireless-receiver be))))))

    (doseq [tile-id [rf-in/block-id rf-out/block-id eu-in/block-id eu-out/block-id]]
      (tile-logic/register-tile-capability! tile-id :energy-converter))

    (doseq [tile-id [rf-out/block-id eu-out/block-id]]
      (tile-logic/register-tile-capability! tile-id :wireless-generator))

    (doseq [tile-id [rf-in/block-id eu-in/block-id]]
      (tile-logic/register-tile-capability! tile-id :wireless-receiver))

    (log/info "Initialized 4 integration converters (RF in/out + EU in/out)")))

(defn converter-status []
  {:rf-input "active"
   :rf-output "active"
   :eu-input "active"
   :eu-output "active"})