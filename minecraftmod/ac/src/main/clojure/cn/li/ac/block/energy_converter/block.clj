(ns cn.li.ac.block.energy-converter.block
  "Energy Converter block - converts between IF and external energy systems (Forge Energy).

  This file contains:
  - Block definition
  - Server-side logic (tick, NBT, item charging)
  - Container functions
  - Network handlers

  Architecture:
  All persistent state lives in ScriptedBlockEntity.customState as a Clojure
  persistent map."
  (:require [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.ac.block.energy-converter.schema :as converter-schema]
            [cn.li.ac.block.energy-converter.config :as converter-config]
            [cn.li.ac.block.energy-converter.capability-impl :as cap-impl]
            [cn.li.ac.block.energy-converter.wireless-impl :as wireless-impl]
            [cn.li.ac.block.energy-converter.face-config :as face-config]
            [cn.li.mcmod.platform.nbt :as nbt]
            [cn.li.mcmod.platform.item :as item]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.config.modid :as modid])
  (:import [cn.li.acapi.energy IEnergyCapable]
           [cn.li.acapi.wireless IWirelessGenerator IWirelessReceiver WirelessCapabilityKeys]))

;; Message Registration
(msg-registry/register-block-messages! :energy-converter
  [:get-status :set-mode])

(defn- msg [action] (msg-registry/msg :energy-converter action))

;; ============================================================================
;; Part 1: Server-Side Tick Logic
;; ============================================================================

(defn- converter-tick-fn
  "Tick handler for energy converter ScriptedBlockEntity.

  Handles:
  - Item charging in slots
  - Energy transfer tracking

  All mutable state is stored in BE customState as a Clojure keyword map."
  [level _pos _block-state be]
  (when (and level (not (world/world-is-client-side level)))
    (let [state (or (platform-be/get-custom-state be) {})
          tick-counter (int (get state :tick-counter 0))
          tick-interval (converter-config/tick-interval)]

      ;; Only process every N ticks
      (if (>= tick-counter tick-interval)
        (let [mode (get state :mode "charge-items")
              max-transfer (converter-config/transfer-rate)

              ;; Process based on mode
              new-state (case mode
                          "charge-items"
                          (cap-impl/charge-items-in-slots state max-transfer)

                          "export-fe"
                          ;; External mods will pull via capability
                          (assoc state :transfer-rate 0.0)

                          "import-fe"
                          ;; External mods will push via capability
                          (assoc state :transfer-rate 0.0)

                          ;; Default
                          state)

              ;; Reset tick counter
              new-state (assoc new-state :tick-counter 0)]

          (when (not= new-state state)
            (platform-be/set-custom-state! be new-state)
            (platform-be/set-changed! be)))

        ;; Increment tick counter
        (let [new-state (assoc state :tick-counter (inc tick-counter))]
          (platform-be/set-custom-state! be new-state))))))

;; ============================================================================
;; Part 2: NBT Serialization
;; ============================================================================

(defn- converter-read-nbt-fn
  "Deserialize CompoundTag → state keyword map (stored in BE customState)."
  [tag]
  {:energy      (if (nbt/nbt-has-key? tag "Energy")
                  (nbt/nbt-get-double tag "Energy")
                  0.0)
   :max-energy  (converter-config/max-energy)
   :mode        (if (nbt/nbt-has-key? tag "Mode")
                  (nbt/nbt-get-string tag "Mode")
                  "charge-items")
   :input-slot  (when (nbt/nbt-has-key? tag "InputSlot")
                  (item/create-item-from-nbt (nbt/nbt-get-compound tag "InputSlot")))
   :output-slot (when (nbt/nbt-has-key? tag "OutputSlot")
                  (item/create-item-from-nbt (nbt/nbt-get-compound tag "OutputSlot")))
   :wireless-enabled (if (nbt/nbt-has-key? tag "WirelessEnabled")
                       (nbt/nbt-get-boolean tag "WirelessEnabled")
                       false)
   :wireless-mode (if (nbt/nbt-has-key? tag "WirelessMode")
                    (nbt/nbt-get-string tag "WirelessMode")
                    "generator")
   :wireless-bandwidth (if (nbt/nbt-has-key? tag "WirelessBandwidth")
                         (nbt/nbt-get-double tag "WirelessBandwidth")
                         1000.0)
   :face-config (if (nbt/nbt-has-key? tag "FaceConfig")
                  (let [face-tag (nbt/nbt-get-compound tag "FaceConfig")]
                    {:north (if (nbt/nbt-has-key? face-tag "north")
                              (nbt/nbt-get-string face-tag "north")
                              "none")
                     :south (if (nbt/nbt-has-key? face-tag "south")
                              (nbt/nbt-get-string face-tag "south")
                              "none")
                     :east (if (nbt/nbt-has-key? face-tag "east")
                             (nbt/nbt-get-string face-tag "east")
                             "none")
                     :west (if (nbt/nbt-has-key? face-tag "west")
                             (nbt/nbt-get-string face-tag "west")
                             "none")
                     :up (if (nbt/nbt-has-key? face-tag "up")
                           (nbt/nbt-get-string face-tag "up")
                           "none")
                     :down (if (nbt/nbt-has-key? face-tag "down")
                             (nbt/nbt-get-string face-tag "down")
                             "none")})
                  (face-config/default-face-config))
   :tick-counter 0
   :transfer-rate 0.0})

(defn- converter-write-nbt-fn
  "Serialize BE customState → CompoundTag."
  [be tag]
  (let [state (or (platform-be/get-custom-state be) {})]
    (nbt/nbt-set-double! tag "Energy" (double (get state :energy 0.0)))
    (nbt/nbt-set-string! tag "Mode" (str (get state :mode "charge-items")))

    ;; Save input slot
    (let [input-stack (:input-slot state)]
      (when (and input-stack (not (item/item-is-empty? input-stack)))
        (let [sub (nbt/create-nbt-compound)]
          (item/item-save-to-nbt input-stack sub)
          (nbt/nbt-set-tag! tag "InputSlot" sub))))

    ;; Save output slot
    (let [output-stack (:output-slot state)]
      (when (and output-stack (not (item/item-is-empty? output-stack)))
        (let [sub (nbt/create-nbt-compound)]
          (item/item-save-to-nbt output-stack sub)
          (nbt/nbt-set-tag! tag "OutputSlot" sub))))

    ;; Save wireless settings
    (nbt/nbt-set-boolean! tag "WirelessEnabled" (boolean (get state :wireless-enabled false)))
    (nbt/nbt-set-string! tag "WirelessMode" (str (get state :wireless-mode "generator")))
    (nbt/nbt-set-double! tag "WirelessBandwidth" (double (get state :wireless-bandwidth 1000.0)))

    ;; Save face configuration
    (let [face-config (get state :face-config (face-config/default-face-config))
          face-tag (nbt/create-nbt-compound)]
      (nbt/nbt-set-string! face-tag "north" (str (get face-config :north "none")))
      (nbt/nbt-set-string! face-tag "south" (str (get face-config :south "none")))
      (nbt/nbt-set-string! face-tag "east" (str (get face-config :east "none")))
      (nbt/nbt-set-string! face-tag "west" (str (get face-config :west "none")))
      (nbt/nbt-set-string! face-tag "up" (str (get face-config :up "none")))
      (nbt/nbt-set-string! face-tag "down" (str (get face-config :down "none")))
      (nbt/nbt-set-tag! tag "FaceConfig" face-tag))))

;; ============================================================================
;; Part 3: Block Event Handlers
;; ============================================================================

(defn- open-converter-gui!
  [{:keys [player world pos sneaking] :as _ctx}]
  (when (and player world pos (not sneaking))
    (try
      (if-let [open-gui-by-type (requiring-resolve 'cn.li.ac.wireless.gui.registry/open-gui-by-type)]
        (open-gui-by-type player :energy-converter world pos)
        (do (log/error "EnergyConverter GUI open fn not found: cn.li.ac.wireless.gui.registry/open-gui-by-type") nil))
      (catch Exception e
        (log/error "Failed to open EnergyConverter GUI:" (ex-message e))
        nil))))

;; ============================================================================
;; Part 4: Network Handlers
;; ============================================================================

(defn- handle-get-status [payload player]
  (let [world (net-helpers/get-world player)
        tile  (net-helpers/get-tile-at world payload)]
    (if tile
      (let [state (or (platform-be/get-custom-state tile) {})]
        {:energy (double (get state :energy 0.0))
         :max-energy (converter-config/max-energy)
         :mode (str (get state :mode "charge-items"))
         :conversion-rate (converter-config/fe-conversion-rate)
         :transfer-rate (double (get state :transfer-rate 0.0))
         :wireless-transfer-rate (double (get state :wireless-transfer-rate 0.0))
         :fe-transfer-rate (double (get state :fe-transfer-rate 0.0))
         :eu-transfer-rate (double (get state :eu-transfer-rate 0.0))
         :efficiency (double (get state :efficiency 100.0))
         :total-converted (double (get state :total-converted 0.0))
         :wireless-enabled (boolean (get state :wireless-enabled false))
         :wireless-mode (str (get state :wireless-mode "generator"))})
      {:energy 0.0
       :max-energy (converter-config/max-energy)
       :mode "charge-items"
       :conversion-rate (converter-config/fe-conversion-rate)
       :transfer-rate 0.0
       :wireless-transfer-rate 0.0
       :fe-transfer-rate 0.0
       :eu-transfer-rate 0.0
       :efficiency 100.0
       :total-converted 0.0
       :wireless-enabled false
       :wireless-mode "generator"})))

(defn- handle-set-mode [payload player]
  (let [world (net-helpers/get-world player)
        tile  (net-helpers/get-tile-at world payload)
        new-mode (get payload :mode "charge-items")]
    (if tile
      (let [state (or (platform-be/get-custom-state tile) {})
            new-state (assoc state :mode new-mode)]
        (platform-be/set-custom-state! tile new-state)
        (platform-be/set-changed! tile)
        {:success true :mode new-mode})
      {:success false})))

(defn register-network-handlers! []
  (net-server/register-handler (msg :get-status) handle-get-status)
  (net-server/register-handler (msg :set-mode) handle-set-mode)
  (log/info "Energy Converter network handlers registered"))

;; ============================================================================
;; Part 5: Capability Implementation
;; ============================================================================

(defn- create-converter-capability [be]
  (cap-impl/create-energy-capable
    be
    (fn [] (or (platform-be/get-custom-state be) {}))
    (fn [new-state]
      (platform-be/set-custom-state! be new-state)
      (platform-be/set-changed! be))))

(defn- create-wireless-generator-capability [be]
  (wireless-impl/create-wireless-generator
    be
    (fn [] (or (platform-be/get-custom-state be) {}))
    (fn [new-state]
      (platform-be/set-custom-state! be new-state)
      (platform-be/set-changed! be))))

(defn- create-wireless-receiver-capability [be]
  (wireless-impl/create-wireless-receiver
    be
    (fn [] (or (platform-be/get-custom-state be) {}))
    (fn [new-state]
      (platform-be/set-custom-state! be new-state)
      (platform-be/set-changed! be))))

(defn- get-wireless-capability
  "Get wireless capability based on current state."
  [be _side]
  (let [state (or (platform-be/get-custom-state be) {})
        wireless-enabled? (wireless-impl/is-wireless-enabled? state)
        wireless-mode (wireless-impl/get-wireless-mode state)]
    (when wireless-enabled?
      (case wireless-mode
        "generator" (create-wireless-generator-capability be)
        "receiver" (create-wireless-receiver-capability be)
        nil))))

;; ============================================================================
;; Part 6: Registration
;; ============================================================================

;; Register tile logic
(tdsl/deftile energy-converter-tile
  :id "energy-converter"
  :registry-name "energy_converter"
  :impl :scripted
  :blocks ["energy-converter"]
  :tick-fn converter-tick-fn
  :read-nbt-fn converter-read-nbt-fn
  :write-nbt-fn converter-write-nbt-fn)

;; Register capability
(platform-cap/declare-capability! :energy-converter IEnergyCapable
  (fn [be _side] (create-converter-capability be)))

;; Register wireless capabilities
(platform-cap/declare-capability! :wireless-generator IWirelessGenerator
  get-wireless-capability)

(platform-cap/declare-capability! :wireless-receiver IWirelessReceiver
  get-wireless-capability)

(tile-logic/register-tile-capability! "energy-converter" :energy-converter)
(tile-logic/register-tile-capability! "energy-converter" :wireless-generator)
(tile-logic/register-tile-capability! "energy-converter" :wireless-receiver)

;; Define block
(bdsl/defblock energy-converter
  :registry-name "energy_converter"
  :physical {:material :metal
             :hardness 2.0
             :resistance 8.0
             :requires-tool true
             :harvest-tool :pickaxe
             :harvest-level 1
             :sounds :metal}
  :rendering {:model-parent "minecraft:block/cube_all"
              :textures {:all (modid/asset-path "block" "energy_converter")}
              :flat-item-icon? true}
  :events {:on-right-click open-converter-gui!})

;; Auto-Registration
(hooks/register-network-handler! register-network-handlers!)

;; Helper functions
(defn init-energy-converter!
  []
  (log/info "Initialized Energy Converter block"))
