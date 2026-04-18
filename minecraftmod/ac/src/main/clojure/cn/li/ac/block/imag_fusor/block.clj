(ns cn.li.ac.block.imag-fusor.block
  "Imaginary Fusor block - AcademyCraft parity implementation.

  Ported behavior:
  - 1 crystal input slot + 1 crystal output slot
  - MatterUnit phase-liquid input/output pair with internal liquid tank
  - Tick-based work loop with per-tick energy consume and liquid consume on finish
  - Slot IO rules matching legacy container semantics"
  (:require [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.block.state-schema :as state-schema]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.item :as pitem]
            [cn.li.mcmod.platform.nbt :as nbt]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.block.imag-fusor.config :as fusor-config]
            [cn.li.ac.block.imag-fusor.schema :as fusor-schema]
            [cn.li.ac.block.imag-fusor.recipes :as recipes]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.ac.registry.hooks :as hooks]))

(defn- log-info
  [& xs]
  (when-let [f (requiring-resolve 'cn.li.mcmod.util.log/info)]
    (apply f xs)))

(defn- log-error
  [& xs]
  (when-let [f (requiring-resolve 'cn.li.mcmod.util.log/error)]
    (apply f xs)))

(defn- asset-path
  [category filename]
  (if-let [f (requiring-resolve 'cn.li.ac.config.modid/asset-path)]
    (f category filename)
    (str "my_mod:" category "/" filename)))

;; ============================================================================
;; Message Registration
;; ============================================================================

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

(def fusor-blockstate-fields
  (filterv :block-state fusor-schema/imag-fusor-schema))

(def fusor-block-state-properties
  (merge (state-schema/extract-block-state-properties fusor-blockstate-fields)
         {:facing {:name "facing"
                   :type :horizontal-facing
                   :default "north"}}))

(def fusor-blockstate-updater
  (state-schema/build-block-state-updater fusor-blockstate-fields))

;; ============================================================================
;; Inventory Helpers
;; ============================================================================

(def input-slot 0)
(def output-slot 1)
(def imag-input-slot fusor-config/imag-input-slot-index)
(def energy-slot fusor-config/energy-slot-index)
(def imag-output-slot fusor-config/imag-output-slot-index)
(def total-slots fusor-config/total-slots)

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

(defn- stack-id
  [stack]
  (recipes/item-id-from-stack stack))

(defn- rebuild-stack
  [stack new-count]
  (when (and stack (pos? (int new-count)))
    (when-let [item-id (stack-id stack)]
      (pitem/create-item-stack-by-id item-id (int new-count)))))

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

(defn- get-input-item
  [state]
  (get-in state [:inventory input-slot]))

(defn- get-output-item
  [state]
  (get-in state [:inventory output-slot]))

(defn- matter-unit-kind
  [stack]
  (when (and (not (stack-empty? stack))
             (= (stack-id stack) fusor-config/matter-unit-item-id))
    (let [tag (try (pitem/item-get-tag-compound stack) (catch Exception _ nil))
          tag-kind (when tag (try (nbt/nbt-get-string tag "matterKind") (catch Exception _ nil)))]
      (or (case (some-> tag-kind str)
            "none" :none
            "phase-liquid" :phase-liquid
            nil)
          (case (int (try (pitem/item-get-damage stack) (catch Exception _ -1)))
            0 :none
            1 :phase-liquid
            nil)))))

(defn- phase-liquid-unit?
  [stack]
  (= :phase-liquid (matter-unit-kind stack)))

(defn- empty-matter-unit?
  [stack]
  (= :none (matter-unit-kind stack)))

(defn- make-empty-matter-unit
  [count]
  (let [stack (pitem/create-item-stack-by-id fusor-config/matter-unit-item-id (int count))]
    (when stack
      (try
        (let [tag (pitem/item-get-or-create-tag stack)]
          (nbt/nbt-set-string! tag "matterKind" "none"))
        (catch Exception _ nil))
      (try
        (pitem/item-set-damage! stack fusor-config/matter-unit-none-meta)
        (catch Exception _ nil))
      stack)))

;; ============================================================================
;; Crafting Logic
;; ============================================================================

(defn- get-current-recipe
  [state]
  (when-let [rid (:current-recipe-id state)]
    (recipes/get-recipe-by-id rid)))

(defn- can-start-crafting?
  [state]
  (let [input-item (get-input-item state)
        recipe (when-not (stack-empty? input-item)
                 (recipes/find-recipe input-item))]
    (and recipe
         (not (:working state false))
         recipe)))

(defn- start-working
  [state recipe]
  (assoc state
         :working true
         :work-progress 0.0
         :crafting-progress 0
         :max-progress (int (or (:time recipe) fusor-config/craft-time-ticks))
         :current-recipe-id (:id recipe "")
         :current-recipe-liquid (int (:consume-liquid recipe 0))))

(defn- abort-working
  [state]
  (assoc state
         :working false
         :work-progress 0.0
         :crafting-progress 0
         :current-recipe-id ""
         :current-recipe-liquid 0))

(defn- finish-working
  [state recipe]
  (let [input-item (get-input-item state)
        output-item (get-output-item state)
        produced (recipes/build-output-stack recipe)
        consume-count (int (get-in recipe [:input :count] 1))
        consume-liquid (int (:consume-liquid recipe 0))
        new-input (consume-stack input-item consume-count)
        new-output (merge-stack-count output-item produced)
        new-liquid (max 0 (- (int (:liquid-amount state 0)) consume-liquid))]
    (if (stack-empty? produced)
      (abort-working state)
      (-> state
          (assoc-in [:inventory input-slot] new-input)
          (assoc-in [:inventory output-slot] new-output)
          (assoc :liquid-amount new-liquid
                 :check-cooldown 0)
          (abort-working)))))

(defn- tick-crafting
  [state]
  (if (:working state false)
    (let [recipe (get-current-recipe state)
          input-item (get-input-item state)
          output-item (get-output-item state)
          energy (:energy state 0.0)
          liquid-amount (int (:liquid-amount state 0))
          max-progress (max 1 (int (:max-progress state fusor-config/craft-time-ticks)))]
      (if (recipes/can-craft? recipe input-item output-item energy liquid-amount)
        (let [progress (double (:work-progress state 0.0))
              work-step (/ 1.0 (double max-progress))
              new-progress (+ progress work-step)
              new-energy (- (double energy) fusor-config/energy-per-tick)
              percent (int (Math/floor (* 100.0 (min 1.0 new-progress))))
              next-state (assoc state
                                :energy new-energy
                                :work-progress (min 1.0 new-progress)
                                :crafting-progress percent)]
          (if (>= new-progress 1.0)
            (finish-working next-state recipe)
            next-state))
        (abort-working state)))
    state))

(defn- try-start-crafting
  [state]
  (if (can-start-crafting? state)
    (let [recipe (recipes/find-recipe (get-input-item state))]
      (if recipe
        (start-working state recipe)
        state))
    state))

(defn- can-convert-phase-unit?
  [state]
  (let [input-unit (get-in state [:inventory imag-input-slot])
        output-unit (get-in state [:inventory imag-output-slot])
        liquid (int (:liquid-amount state 0))
        tank-size (int (:tank-size state fusor-config/tank-size))
        has-input (and (phase-liquid-unit? input-unit)
                       (pos? (stack-count input-unit)))
        can-store (<= (+ liquid fusor-config/liquid-per-unit) tank-size)
        can-output (or (stack-empty? output-unit)
                       (and (empty-matter-unit? output-unit)
                            (< (stack-count output-unit)
                               (int (or (try (pitem/item-get-max-stack-size output-unit)
                                             (catch Exception _ 16))
                                        16)))))]
    (and has-input can-store can-output)))

(defn- convert-phase-unit
  [state]
  (if (can-convert-phase-unit? state)
    (let [input-unit (get-in state [:inventory imag-input-slot])
          output-unit (get-in state [:inventory imag-output-slot])
          new-input (consume-stack input-unit 1)
          add-count (if (stack-empty? output-unit) 1 (inc (stack-count output-unit)))
          new-output (or (when-not (stack-empty? output-unit)
                           (rebuild-stack output-unit add-count))
                         (make-empty-matter-unit add-count))]
      (-> state
          (assoc :liquid-amount (+ (int (:liquid-amount state 0)) fusor-config/liquid-per-unit))
          (assoc-in [:inventory imag-input-slot] new-input)
          (assoc-in [:inventory imag-output-slot] new-output)))
    state))

(defn- update-idle-state
  [state]
  (let [cooldown (int (:check-cooldown state fusor-config/check-interval))
        next-cooldown (dec cooldown)]
    (if (<= next-cooldown 0)
      (-> state
          (assoc :check-cooldown fusor-config/check-interval)
          (try-start-crafting))
      (assoc state :check-cooldown next-cooldown))))

(defn- animated-frame
  [working? day-time]
  (if working?
    (inc (mod (quot (long day-time) 8) 4))
    0))

(defn- update-be-state!
  [be level pos state]
  (platform-be/set-custom-state! be state)
  (platform-be/set-changed! be)
  (when (seq fusor-blockstate-fields)
    (fusor-blockstate-updater state level pos)))

;; ============================================================================
;; Tick Logic
;; ============================================================================

(defn- fusor-tick-fn
  [level pos _block-state be]
  (when (and level (not (world/world-is-client-side* level)))
    (let [state (or (platform-be/get-custom-state be) fusor-default-state)
          ticker (inc (get state :update-ticker 0))
          state (assoc state
                       :update-ticker ticker
                       :tank-size (int (:tank-size state fusor-config/tank-size))
                       :check-cooldown (int (:check-cooldown state fusor-config/check-interval)))

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

          ;; Matter unit => liquid tank
          state (convert-phase-unit state)

          ;; Process crafting
          state (if (:working state false)
                  (tick-crafting state)
                  (update-idle-state state))

          state (assoc state :frame (animated-frame (:working state false)
                                                    (world/world-get-day-time* level)))]

      (when (not= state (platform-be/get-custom-state be))
        (update-be-state! be level pos state)))))

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
                   (or (and (= slot input-slot)
                      (boolean (recipes/find-recipe item)))
                     (and (= slot imag-input-slot)
                      (phase-liquid-unit? item))
                                   (and (= slot energy-slot)
                                        (energy/is-energy-item-supported? item))))

   :can-take-through-face? (fn [_be slot _item _face]
                 (or (= slot output-slot)
                   (= slot imag-output-slot)
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
        (do (log-error "Imag Fusor GUI open fn not found") nil))
      (catch Exception e
        (log-error "Failed to open Imag Fusor GUI:" (ex-message e))
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
         :work-progress (:work-progress state 0.0)
         :max-progress (:max-progress state fusor-config/craft-time-ticks)
         :current-recipe-liquid (:current-recipe-liquid state 0)
         :liquid-amount (:liquid-amount state 0)
         :tank-size (:tank-size state fusor-config/tank-size)
         :working (:working state false)})
      {:energy 0.0 :max-energy 0.0 :crafting-progress 0 :work-progress 0.0
       :max-progress fusor-config/craft-time-ticks :current-recipe-liquid 0
       :liquid-amount 0 :tank-size fusor-config/tank-size :working false})))

(defn register-network-handlers! []
  (net-server/register-handler (msg :get-status) handle-get-status)
  (log-info "Imaginary Fusor network handlers registered"))

;; ============================================================================
;; Tile Registration
;; ============================================================================

;; ============================================================================
;; Runtime Installation (Scheme A)
;; ============================================================================

(defonce ^:private imag-fusor-installed? (atom false))

(defn init-imag-fusor!
  []
  (when (compare-and-set! imag-fusor-installed? false true)
    (msg-registry/register-block-messages! :imag-fusor [:get-status])
    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "imag-fusor"
        {:registry-name "imag_fusor"
         :impl :scripted
         :blocks ["imag-fusor"]
         :tick-fn fusor-tick-fn
         :read-nbt-fn fusor-scripted-load-fn
         :write-nbt-fn fusor-scripted-save-fn}))
    (tile-logic/register-container! "imag-fusor" fusor-container-fns)
    (bdsl/register-block!
      (bdsl/create-block-spec
        "imag-fusor"
        {:registry-name "imag_fusor"
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
           :block-state {:block-state-properties fusor-block-state-properties}
         :events {:on-right-click open-fusor-gui!}}))
    (hooks/register-network-handler! register-network-handlers!)
          (log-info "Initialized Imaginary Fusor block")))

