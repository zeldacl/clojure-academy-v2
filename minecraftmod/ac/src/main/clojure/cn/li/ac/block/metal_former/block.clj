(ns cn.li.ac.block.metal-former.block
  "Metal Former block - metal forming/shaping machine.

  Architecture:
  All persistent state lives in ScriptedBlockEntity.customState as Clojure maps."
  (:require [clojure.string :as str]
            [cn.li.mcmod.block.dsl :as bdsl]
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

(def former-block-state-properties
  {:facing {:name "facing"
            :type :horizontal-facing
            :default "north"}})

;; ============================================================================
;; Inventory
;; ============================================================================

(def input-slot 0)
(def output-slot 1)
(def energy-slot 2)
(def total-slots 3)

(defn- stack-empty?
  [stack]
  (or (nil? stack)
      (try (boolean (pitem/item-is-empty? stack))
           (catch Exception _ false))))

(defn- stack-count
  [stack]
  (if (stack-empty? stack)
    0
    (try (int (pitem/item-get-count stack))
         (catch Exception _ 0))))

(defn- rebuild-stack
  [stack new-count]
  (when (and stack (pos? (int new-count)))
    (when-let [item-id (recipes/item-id-from-stack stack)]
      (let [new-stack (pitem/create-item-stack-by-id item-id (int new-count))]
        (when new-stack
          (try
            (when-let [damage-fn (requiring-resolve 'cn.li.mcmod.platform.item/item-get-damage)]
              (when-let [set-damage-fn (requiring-resolve 'cn.li.mcmod.platform.item/item-set-damage!)]
                (set-damage-fn new-stack (int (damage-fn stack)))))
            (catch Exception _ nil))
          new-stack)))))

(defn- consume-stack
  [stack amount]
  (let [left (- (stack-count stack) (int amount))]
    (when (pos? left)
      (rebuild-stack stack left))))

(defn- merge-stack-count
  [existing produced]
  (cond
    (stack-empty? produced) existing
    (stack-empty? existing) produced
    :else (rebuild-stack existing (+ (stack-count existing) (stack-count produced)))))

(defn- current-mode
  [state]
  (recipes/normalize-mode (:mode state :plate)))

(defn- face-name
  [face]
  (some-> face name str/lower-case))

(defn- get-current-recipe
  [state]
  (when-let [rid (:current-recipe-id state)]
    (recipes/get-recipe-by-id rid)))

(defn- reset-work
  [state]
  (assoc state
         :working false
         :work-counter 0
         :current-recipe-id ""))

;; ============================================================================
;; Forming Logic
;; ============================================================================

(defn- cycle-mode
  [state delta]
  (let [idx (or (first (keep-indexed (fn [i mode]
                                       (when (= mode (current-mode state))
                                         i))
                                     recipes/mode-order))
                -1)
        current-idx (if (neg? idx) 0 idx)
        next-idx (mod (+ current-idx (int delta)) (count recipes/mode-order))]
    (assoc state :mode (recipes/mode->string (nth recipes/mode-order next-idx)))))

(defn- tick-forming
  [state]
  (let [recipe (get-current-recipe state)
        input-item (get-in state [:inventory input-slot])
        output-item (get-in state [:inventory output-slot])
        energy (double (:energy state 0.0))]
    (if (and recipe
             (recipes/can-form? recipe input-item output-item (current-mode state))
             (>= energy former-config/energy-per-tick))
      (let [new-counter (inc (int (:work-counter state 0)))
            new-energy (- energy former-config/energy-per-tick)]
        (if (>= new-counter former-config/work-ticks)
          (let [produced (recipes/build-output-stack recipe)
                new-input (consume-stack input-item (get-in recipe [:input :count] 1))
                new-output (merge-stack-count output-item produced)]
            (-> state
                (assoc :energy new-energy)
                (assoc-in [:inventory input-slot] new-input)
                (assoc-in [:inventory output-slot] new-output)
                reset-work))
          (assoc state
                 :energy new-energy
                 :working true
                 :work-counter new-counter)))
      (reset-work state))))

(defn- try-find-recipe
  [state]
  (let [counter (inc (int (:work-counter state 0)))]
    (if (>= counter former-config/recipe-check-interval)
      (let [recipe (recipes/find-recipe (get-in state [:inventory input-slot])
                                        (current-mode state))]
        (if recipe
          (assoc state
                 :working true
                 :work-counter 0
                 :current-recipe-id (:id recipe))
          (assoc state :work-counter 0)))
      (assoc state :work-counter counter))))

;; ============================================================================
;; Tick Logic
;; ============================================================================

(defn- former-tick-fn
  "Tick handler for metal former"
  [level _block-pos _block-state be]
  (when (and level (not (world/world-is-client-side* level)))
    (let [orig-state (or (platform-be/get-custom-state be) former-default-state)
          ticker (inc (int (get orig-state :update-ticker 0)))
          state (assoc orig-state
                       :update-ticker ticker
                       :max-energy (double former-config/max-energy))

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
                  (try-find-recipe state))]

      (when (not= state orig-state)
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
       :can-place-through-face? (fn [_be slot item face]
                     (or (and (= slot input-slot)
                        (or (nil? face)
                          (= (face-name face) "up")))
                                   (and (= slot energy-slot)
                        (not= (face-name face) "down")
                                        (energy/is-energy-item-supported? item))))
     :can-take-through-face? (fn [_be slot _item face]
                   (let [f (face-name face)]
                   (and (= f "down")
                    (or (= slot output-slot)
                      (= slot energy-slot)))))})

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
         :work-counter (:work-counter state 0)
         :mode (or (:mode state) "plate")
         :working (:working state false)})
      {:energy 0.0 :max-energy 0.0 :work-counter 0 :mode "plate" :working false})))

(defn- handle-alternate [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)
        delta (int (or (:dir payload) 0))]
    (if-not tile
      {:success false}
      (let [state (or (platform-be/get-custom-state tile) former-default-state)
            next-state (cycle-mode state delta)]
        (platform-be/set-custom-state! tile next-state)
        (platform-be/set-changed! tile)
        {:success true
         :mode (:mode next-state)}))))

(defn register-network-handlers! []
  (net-server/register-handler (msg :get-status) handle-get-status)
  (net-server/register-handler (msg :alternate) handle-alternate)
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
    (msg-registry/register-block-messages! :metal-former [:get-status :alternate])
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
              :hardness 3.0
                    :resistance 6.0
                    :requires-tool true
                    :harvest-tool :pickaxe
                    :harvest-level 1
                    :sounds :metal}
            :rendering {:model-parent "minecraft:block/cube_all"
               :textures {:all (modid/asset-path "block" "metal_former_front")}
                     :flat-item-icon? true}
            :block-state {:block-state-properties former-block-state-properties}
         :events {:on-right-click open-former-gui!}}))
    (hooks/register-network-handler! register-network-handlers!)
    (log/info "Initialized Metal Former block")))

