(ns cn.li.ac.block.metal-former.block
  "Metal Former block - metal forming/shaping machine.

  Architecture:
  All persistent state lives in ScriptedBlockEntity.customState as Clojure maps."
  (:require [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.block.state-schema :as state-schema]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.item :as pitem]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.ac.block.metal-former.config :as former-config]
            [cn.li.ac.block.metal-former.schema :as former-schema]
            [cn.li.ac.block.metal-former.recipes :as recipes]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.config.modid :as modid]))

;; ============================================================================
;; Message Registration
;; ============================================================================

(defn- msg [action] (msg-registry/msg :metal-former action))

;; ============================================================================
;; Schema Generation
;; ============================================================================

(def former-state-schema
  (state-schema/filter-server-fields former-schema/metal-former-schema))

(def former-default-state
  (state-schema/schema->default-state former-state-schema))

(def former-scripted-load-fn
  (state-schema/schema->load-fn former-state-schema))

(def former-scripted-save-fn
  (state-schema/schema->save-fn former-state-schema))

;; ============================================================================
;; Inventory
;; ============================================================================

(def input-slot 0)
(def output-slot 1)
(def energy-slot 2)
(def total-slots 3)

;; ============================================================================
;; Forming Logic
;; ============================================================================

(defn- can-start-forming?
  "Check if forming can start"
  [state]
  (let [input-item (get-in state [:inventory input-slot])
        recipe (when input-item (recipes/find-recipe input-item))
        energy (:energy state 0.0)]
    (and recipe
         (recipes/can-form? recipe input-item energy)
         (not (:working state false)))))

(defn- tick-forming
  "Process one tick of forming"
  [state]
  (if (:working state false)
    (let [progress (:crafting-progress state 0)
          max-prog (:max-progress state former-config/max-progress)
          energy (:energy state 0.0)
          energy-cost former-config/energy-per-tick]
      (if (>= energy energy-cost)
        (let [new-progress (inc progress)
              new-energy (- energy energy-cost)]
          (if (>= new-progress max-prog)
            (assoc state
                   :crafting-progress 0
                   :working false
                   :energy new-energy
                   :current-recipe-id "")
            (assoc state
                   :crafting-progress new-progress
                   :energy new-energy)))
        (assoc state :working false)))
    state))

(defn- try-start-forming
  "Try to start a new forming operation"
  [state]
  (if (can-start-forming? state)
    (let [input-item (get-in state [:inventory input-slot])
          recipe (recipes/find-recipe input-item)]
      (assoc state
             :working true
             :crafting-progress 0
             :current-recipe-id (:id recipe "")
             :max-progress (or (:time recipe) former-config/form-time-ticks)))
    state))

;; ============================================================================
;; Tick Logic
;; ============================================================================

(defn- former-tick-fn
  "Tick handler for metal former"
  [level _pos _block-state be]
  (when (and level (not (world/world-is-client-side* level)))
    (let [state (or (platform-be/get-custom-state be) former-default-state)
          ticker (inc (get state :update-ticker 0))
          state (assoc state :update-ticker ticker)

          ;; Charge from energy slot
          energy-item (get-in state [:inventory energy-slot])
          state (if (and energy-item (energy/is-energy-item-supported? energy-item))
                  (let [cur-energy (double (:energy state 0.0))
                        max-energy (double (:max-energy state former-config/max-energy))
                        needed (- max-energy cur-energy)
                        pulled (double (energy/pull-energy-from-item energy-item needed false))]
                    (if (pos? pulled)
                      (assoc state :energy (+ cur-energy pulled))
                      state))
                  state)

          ;; Process forming
          state (if (:working state false)
                  (tick-forming state)
                  (try-start-forming state))]

      (when (not= state (platform-be/get-custom-state be))
        (platform-be/set-custom-state! be state)
        (platform-be/set-changed! be)))))

;; ============================================================================
;; Container Functions
;; ============================================================================

(def former-container-fns
  {:get-size (fn [_be] total-slots)
   :get-item (fn [be slot]
               (get-in (or (platform-be/get-custom-state be) former-default-state)
                       [:inventory slot]))
   :set-item! (fn [be slot item]
                (let [state (or (platform-be/get-custom-state be) former-default-state)
                      state' (assoc-in state [:inventory slot] item)]
                  (platform-be/set-custom-state! be state')))
   :remove-item (fn [be slot amount]
                  (let [state (or (platform-be/get-custom-state be) former-default-state)
                        item (get-in state [:inventory slot])]
                    (when item
                      (let [cnt (pitem/item-get-count item)]
                        (if (<= cnt amount)
                          (do (platform-be/set-custom-state! be (assoc-in state [:inventory slot] nil))
                              item)
                          (pitem/item-split item amount))))))
   :remove-item-no-update (fn [be slot]
                            (let [state (or (platform-be/get-custom-state be) former-default-state)
                                  item (get-in state [:inventory slot])]
                              (platform-be/set-custom-state! be (assoc-in state [:inventory slot] nil))
                              item))
   :clear! (fn [be]
             (platform-be/set-custom-state! be
               (assoc (or (platform-be/get-custom-state be) former-default-state)
                      :inventory (vec (repeat total-slots nil)))))
   :still-valid? (fn [_be _player] true)
   :can-place-through-face? (fn [_be slot item _face]
                               (or (= slot input-slot)
                                   (and (= slot energy-slot)
                                        (energy/is-energy-item-supported? item))))
   :can-take-through-face? (fn [_be slot _item _face]
                             (or (= slot output-slot)
                                 (= slot energy-slot)))})

;; ============================================================================
;; Event Handlers
;; ============================================================================

(defn- open-former-gui!
  [{:keys [player world pos sneaking] :as _ctx}]
  (when (and player world pos (not sneaking))
    (try
      (if-let [open-gui-by-type (requiring-resolve 'cn.li.ac.wireless.gui.registry/open-gui-by-type)]
        (open-gui-by-type player :metal-former world pos)
        (do (log/error "Metal Former GUI open fn not found") nil))
      (catch Exception e
        (log/error "Failed to open Metal Former GUI:" (ex-message e))
        nil))))

;; ============================================================================
;; Network Handlers
;; ============================================================================

(defn- handle-get-status [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      (let [state (or (platform-be/get-custom-state tile) former-default-state)]
        {:energy (:energy state 0.0)
         :max-energy (:max-energy state former-config/max-energy)
         :crafting-progress (:crafting-progress state 0)
         :max-progress (:max-progress state former-config/max-progress)
         :working (:working state false)})
      {:energy 0.0 :max-energy 0.0 :crafting-progress 0 :max-progress 100 :working false})))

(defn register-network-handlers! []
  (net-server/register-handler (msg :get-status) handle-get-status)
  (log/info "Metal Former network handlers registered"))

;; ============================================================================
;; Tile Registration
;; ============================================================================

;; ============================================================================
;; Runtime Installation (Scheme A)
;; ============================================================================

(defonce ^:private metal-former-installed? (atom false))

(defn init-metal-former!
  []
  (when (compare-and-set! metal-former-installed? false true)
    (msg-registry/register-block-messages! :metal-former [:get-status])
    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "metal-former"
        {:registry-name "metal_former"
         :impl :scripted
         :blocks ["metal-former"]
         :tick-fn former-tick-fn
         :read-nbt-fn former-scripted-load-fn
         :write-nbt-fn former-scripted-save-fn}))
    (tile-logic/register-container! "metal-former" former-container-fns)
    (bdsl/register-block!
      (bdsl/create-block-spec
        "metal-former"
        {:registry-name "metal_former"
         :physical {:material :metal
                    :hardness 3.5
                    :resistance 6.0
                    :requires-tool true
                    :harvest-tool :pickaxe
                    :harvest-level 1
                    :sounds :metal}
         :rendering {:model-parent "minecraft:block/cube_all"
                     :textures {:all (modid/asset-path "block" "metal_former")}
                     :flat-item-icon? true}
         :events {:on-right-click open-former-gui!}}))
    (hooks/register-network-handler! register-network-handlers!)
    (log/info "Initialized Metal Former block")))

