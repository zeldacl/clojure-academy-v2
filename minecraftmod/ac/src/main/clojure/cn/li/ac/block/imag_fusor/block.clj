(ns cn.li.ac.block.imag-fusor.block
  "Imaginary Fusor block - crafting machine with custom recipes.

  Features:
  - Directional facing
  - Energy consumption during crafting
  - Custom recipe system
  - GUI with progress bar

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
            [cn.li.ac.block.imag-fusor.config :as fusor-config]
            [cn.li.ac.block.imag-fusor.schema :as fusor-schema]
            [cn.li.ac.block.imag-fusor.recipes :as recipes]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.config.modid :as modid]))

;; ============================================================================
;; Message Registration
;; ============================================================================

(msg-registry/register-block-messages! :imag-fusor
  [:get-status])

(defn- msg [action] (msg-registry/msg :imag-fusor action))

;; ============================================================================
;; Schema Generation
;; ============================================================================

(def fusor-state-schema
  (state-schema/filter-server-fields fusor-schema/imag-fusor-schema))

(def fusor-default-state
  (state-schema/schema->default-state fusor-state-schema))

(def fusor-scripted-load-fn
  (state-schema/schema->load-fn fusor-state-schema))

(def fusor-scripted-save-fn
  (state-schema/schema->save-fn fusor-state-schema))

;; ============================================================================
;; Inventory Helpers
;; ============================================================================

(def input-slot-0 0)
(def input-slot-1 1)
(def output-slot 2)
(def energy-slot fusor-config/energy-slot-index)
(def total-slots 4)

(defn- get-input-items
  "Get items from input slots"
  [state]
  [(get-in state [:inventory input-slot-0])
   (get-in state [:inventory input-slot-1])])

(defn- get-output-item
  "Get item from output slot"
  [state]
  (get-in state [:inventory output-slot]))

;; ============================================================================
;; Crafting Logic
;; ============================================================================

(defn- can-start-crafting?
  "Check if crafting can start"
  [state]
  (let [inputs (get-input-items state)
        recipe (recipes/find-recipe inputs)
        energy (:energy state 0.0)]
    (and recipe
         (recipes/can-craft? recipe inputs energy)
         (not (:working state false)))))

(defn- tick-crafting
  "Process one tick of crafting"
  [state]
  (if (:working state false)
    (let [progress (:crafting-progress state 0)
          max-prog (:max-progress state fusor-config/max-progress)
          energy (:energy state 0.0)
          energy-cost fusor-config/energy-per-tick]
      (if (>= energy energy-cost)
        (let [new-progress (inc progress)
              new-energy (- energy energy-cost)]
          (if (>= new-progress max-prog)
            ;; Crafting complete
            (assoc state
                   :crafting-progress 0
                   :working false
                   :energy new-energy
                   :current-recipe-id "")
            ;; Continue crafting
            (assoc state
                   :crafting-progress new-progress
                   :energy new-energy)))
        ;; Not enough energy, stop crafting
        (assoc state :working false)))
    state))

(defn- try-start-crafting
  "Try to start a new crafting operation"
  [state]
  (if (can-start-crafting? state)
    (let [inputs (get-input-items state)
          recipe (recipes/find-recipe inputs)]
      (assoc state
             :working true
             :crafting-progress 0
             :current-recipe-id (:id recipe "")
             :max-progress (or (:time recipe) fusor-config/craft-time-ticks)))
    state))

;; ============================================================================
;; Tick Logic
;; ============================================================================

(defn- fusor-tick-fn
  "Tick handler for imaginary fusor"
  [level pos _block-state be]
  (when (and level (not (world/world-is-client-side level)))
    (let [state (or (platform-be/get-custom-state be) fusor-default-state)
          ticker (inc (get state :update-ticker 0))
          state (assoc state :update-ticker ticker)

          ;; Charge from energy slot
          energy-item (get-in state [:inventory energy-slot])
          state (if (and energy-item (energy/is-energy-item-supported? energy-item))
                  (let [cur-energy (double (:energy state 0.0))
                        max-energy (double (:max-energy state fusor-config/max-energy))
                        needed (- max-energy cur-energy)
                        pulled (double (energy/pull-energy-from-item energy-item needed false))]
                    (if (pos? pulled)
                      (assoc state :energy (+ cur-energy pulled))
                      state))
                  state)

          ;; Process crafting
          state (if (:working state false)
                  (tick-crafting state)
                  (try-start-crafting state))]

      (when (not= state (platform-be/get-custom-state be))
        (platform-be/set-custom-state! be state)
        (platform-be/set-changed! be)))))

;; ============================================================================
;; Container Functions
;; ============================================================================

(def fusor-container-fns
  {:get-size (fn [_be] total-slots)

   :get-item (fn [be slot]
               (get-in (or (platform-be/get-custom-state be) fusor-default-state)
                       [:inventory slot]))

   :set-item! (fn [be slot item]
                (let [state (or (platform-be/get-custom-state be) fusor-default-state)
                      state' (assoc-in state [:inventory slot] item)]
                  (platform-be/set-custom-state! be state')))

   :remove-item (fn [be slot amount]
                  (let [state (or (platform-be/get-custom-state be) fusor-default-state)
                        item (get-in state [:inventory slot])]
                    (when item
                      (let [cnt (pitem/item-get-count item)]
                        (if (<= cnt amount)
                          (do (platform-be/set-custom-state! be (assoc-in state [:inventory slot] nil))
                              item)
                          (pitem/item-split item amount))))))

   :remove-item-no-update (fn [be slot]
                            (let [state (or (platform-be/get-custom-state be) fusor-default-state)
                                  item (get-in state [:inventory slot])]
                              (platform-be/set-custom-state! be (assoc-in state [:inventory slot] nil))
                              item))

   :clear! (fn [be]
             (platform-be/set-custom-state! be
               (assoc (or (platform-be/get-custom-state be) fusor-default-state)
                      :inventory (vec (repeat total-slots nil)))))

   :still-valid? (fn [_be _player] true)

   :can-place-through-face? (fn [_be slot item _face]
                               (or (< slot output-slot)
                                   (and (= slot energy-slot)
                                        (energy/is-energy-item-supported? item))))

   :can-take-through-face? (fn [_be slot _item _face]
                             (or (= slot output-slot)
                                 (= slot energy-slot)))})

;; ============================================================================
;; Event Handlers
;; ============================================================================

(defn- open-fusor-gui!
  [{:keys [player world pos sneaking] :as _ctx}]
  (when (and player world pos (not sneaking))
    (try
      (if-let [open-gui-by-type (requiring-resolve 'cn.li.ac.wireless.gui.registry/open-gui-by-type)]
        (open-gui-by-type player :imag-fusor world pos)
        (do (log/error "Imag Fusor GUI open fn not found") nil))
      (catch Exception e
        (log/error "Failed to open Imag Fusor GUI:" (ex-message e))
        nil))))

;; ============================================================================
;; Network Handlers
;; ============================================================================

(defn- handle-get-status [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      (let [state (or (platform-be/get-custom-state tile) fusor-default-state)]
        {:energy (:energy state 0.0)
         :max-energy (:max-energy state fusor-config/max-energy)
         :crafting-progress (:crafting-progress state 0)
         :max-progress (:max-progress state fusor-config/max-progress)
         :working (:working state false)})
      {:energy 0.0 :max-energy 0.0 :crafting-progress 0 :max-progress 100 :working false})))

(defn register-network-handlers! []
  (net-server/register-handler (msg :get-status) handle-get-status)
  (log/info "Imaginary Fusor network handlers registered"))

;; ============================================================================
;; Tile Registration
;; ============================================================================

(tdsl/deftile imag-fusor-tile
  :id "imag-fusor"
  :registry-name "imag_fusor"
  :impl :scripted
  :blocks ["imag-fusor"]
  :tick-fn fusor-tick-fn
  :read-nbt-fn fusor-scripted-load-fn
  :write-nbt-fn fusor-scripted-save-fn)

(tile-logic/register-container! "imag-fusor" fusor-container-fns)

;; ============================================================================
;; Block Definition
;; ============================================================================

(bdsl/defblock imag-fusor
  :registry-name "imag_fusor"
  :physical {:material :metal
             :hardness 3.5
             :resistance 6.0
             :requires-tool true
             :harvest-tool :pickaxe
             :harvest-level 1
             :sounds :metal}
  :rendering {:model-parent "minecraft:block/cube_all"
              :textures {:all (modid/asset-path "block" "imag_fusor")}
              :flat-item-icon? true}
  :events {:on-right-click open-fusor-gui!})

;; ============================================================================
;; Auto-Registration
;; ============================================================================

(hooks/register-network-handler! register-network-handlers!)

(defn init-imag-fusor!
  []
  (log/info "Initialized Imaginary Fusor block"))
