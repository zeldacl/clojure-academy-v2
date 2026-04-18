(ns cn.li.ac.block.phase-gen.block
  "Phase Generator block - generates energy from imaginary phase liquid.

  Architecture:
  All persistent state lives in ScriptedBlockEntity.customState as Clojure maps."
  (:require [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.block.state-schema :as state-schema]
            [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.item :as pitem]
            [cn.li.mcmod.platform.nbt :as nbt]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.ac.block.role-impls :as impls]
            [cn.li.ac.block.phase-gen.config :as phase-config]
            [cn.li.ac.block.phase-gen.schema :as phase-schema]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.config.modid :as modid])
  (:import [cn.li.acapi.wireless IWirelessGenerator]))

(defn- msg [action] (msg-registry/msg :phase-gen action))

(def phase-state-schema
  (state-schema/filter-server-fields phase-schema/phase-gen-schema))

(def phase-default-state
  (state-schema/schema->default-state phase-state-schema))

(def phase-scripted-load-fn
  (state-schema/schema->load-fn phase-state-schema))

(def phase-scripted-save-fn
  (state-schema/schema->save-fn phase-state-schema))

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
  (when-not (stack-empty? stack)
    (try (some-> stack pitem/item-get-item pitem/item-get-registry-name str)
         (catch Exception _ nil))))

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

(defn- matter-unit-kind
  [stack]
  (when (and (not (stack-empty? stack))
             (= (stack-id stack) phase-config/matter-unit-item-id))
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
  (let [stack (pitem/create-item-stack-by-id phase-config/matter-unit-item-id (int count))]
    (when stack
      (try
        (let [tag (pitem/item-get-or-create-tag stack)]
          (nbt/nbt-set-string! tag "matterKind" "none"))
        (catch Exception _ nil))
      (try
        (pitem/item-set-damage! stack phase-config/matter-unit-none-meta)
        (catch Exception _ nil))
      stack)))

(defn- convert-phase-unit
  [state]
  (let [in-slot phase-config/liquid-in-slot
        out-slot phase-config/liquid-out-slot
        in-unit (get-in state [:inventory in-slot])
        out-unit (get-in state [:inventory out-slot])
        liquid (int (get state :liquid-amount 0))
        tank-size (int (get state :tank-size phase-config/tank-size))
        can-consume? (and (phase-liquid-unit? in-unit)
                          (pos? (stack-count in-unit))
                          (<= (+ liquid phase-config/liquid-per-unit) tank-size))
        can-output? (or (stack-empty? out-unit)
                        (and (empty-matter-unit? out-unit)
                             (< (stack-count out-unit)
                                (int (or (try (pitem/item-get-max-stack-size out-unit)
                                              (catch Exception _ 16))
                                         16)))))]
    (if (and can-consume? can-output?)
      (let [new-input (consume-stack in-unit 1)
            add-count (if (stack-empty? out-unit) 1 (inc (stack-count out-unit)))
            new-output (or (when-not (stack-empty? out-unit)
                             (rebuild-stack out-unit add-count))
                           (make-empty-matter-unit add-count))]
        (-> state
            (assoc :liquid-amount (+ liquid phase-config/liquid-per-unit))
            (assoc-in [:inventory in-slot] new-input)
            (assoc-in [:inventory out-slot] new-output)))
      state)))

(defn- maybe-charge-output-item
  [state]
  (let [slot phase-config/output-slot
        stack (get-in state [:inventory slot])
        cur (double (get state :energy 0.0))]
    (if (and stack (energy/is-energy-item-supported? stack) (pos? cur))
      (let [item-cur (double (energy/get-item-energy stack))
            item-max (double (energy/get-item-max-energy stack))
            need (max 0.0 (- item-max item-cur))
            amount (min cur need)
            leftover (double (energy/charge-energy-to-item stack amount false))
            accepted (max 0.0 (- amount leftover))]
        (if (pos? accepted)
          (assoc state :energy (- cur accepted))
          state))
      state)))

(defn- calc-generation
  [state]
  (let [liquid (double (max 0 (int (get state :liquid-amount 0))))
        current-energy (double (get state :energy 0.0))
        max-energy (double (get state :max-energy phase-config/max-energy))
        energy-room (max 0.0 (- max-energy current-energy))
        max-drain-by-config (double phase-config/liquid-consume-per-tick)
        max-drain-by-energy (if (pos? phase-config/gen-per-mb)
                              (/ energy-room phase-config/gen-per-mb)
                              0.0)
        drain (int (Math/floor (max 0.0 (min liquid max-drain-by-config max-drain-by-energy))))
        gen (* (double drain) phase-config/gen-per-mb)]
    {:drain drain
     :gen gen}))

(defn- phase-tick-fn
  "Tick handler for phase generator (TilePhaseGen parity)."
  [level _pos _block-state be]
  (when (and level (not (world/world-is-client-side* level)))
    (let [state0 (or (platform-be/get-custom-state be) phase-default-state)
          state1 (-> state0
                     (assoc :update-ticker (inc (int (get state0 :update-ticker 0))))
                     (assoc :tank-size (int (get state0 :tank-size phase-config/tank-size)))
                     (assoc :max-energy (double (get state0 :max-energy phase-config/max-energy))))
          state2 (convert-phase-unit state1)
          {:keys [drain gen]} (calc-generation state2)
          cur-energy (double (get state2 :energy 0.0))
          liquid-before (int (get state2 :liquid-amount 0))
          state3 (-> state2
                     (assoc :energy (+ cur-energy gen))
                     (assoc :liquid-amount (max 0 (- liquid-before drain)))
                     (assoc :gen-speed (double gen))
                     (assoc :status (cond
                                      (<= liquid-before 0) "NO_LIQUID"
                                      (pos? gen) "GENERATING"
                                      :else "IDLE")))
          state4 (maybe-charge-output-item state3)]
      (when (not= state4 state0)
        (platform-be/set-custom-state! be state4)
        (platform-be/set-changed! be)))))

(def phase-container-fns
  {:get-size (fn [_be] phase-config/total-slots)
   :get-item (fn [be slot]
               (get-in (or (platform-be/get-custom-state be) phase-default-state)
                       [:inventory slot]))
   :set-item! (fn [be slot item]
                (let [state (or (platform-be/get-custom-state be) phase-default-state)]
                  (platform-be/set-custom-state! be (assoc-in state [:inventory slot] item))))
   :remove-item (fn [be slot amount]
                  (let [state (or (platform-be/get-custom-state be) phase-default-state)
                        item (get-in state [:inventory slot])]
                    (when item
                      (let [cnt (stack-count item)]
                        (if (<= cnt amount)
                          (do (platform-be/set-custom-state! be (assoc-in state [:inventory slot] nil))
                              item)
                          (pitem/item-split item amount))))))
   :remove-item-no-update (fn [be slot]
                            (let [state (or (platform-be/get-custom-state be) phase-default-state)
                                  item (get-in state [:inventory slot])]
                              (platform-be/set-custom-state! be (assoc-in state [:inventory slot] nil))
                              item))
   :clear! (fn [be]
             (platform-be/set-custom-state! be
               (assoc (or (platform-be/get-custom-state be) phase-default-state)
                      :inventory (vec (repeat phase-config/total-slots nil)))))
   :still-valid? (fn [_be _player] true)
   :can-place-through-face? (fn [_be ^long slot item _face]
                              (case slot
                                0 (phase-liquid-unit? item)
                                2 (energy/is-energy-item-supported? item)
                                false))
   :can-take-through-face? (fn [_be ^long slot _item _face]
                             (or (= slot phase-config/liquid-out-slot)
                                 (= slot phase-config/output-slot)))})

(defn- open-phase-gen-gui!
  [{:keys [player world pos sneaking] :as _ctx}]
  (when (and player world pos (not sneaking))
    (try
      (if-let [open-gui-by-type (requiring-resolve 'cn.li.ac.wireless.gui.registry/open-gui-by-type)]
        (open-gui-by-type player :phase-gen world pos)
        (do (log/error "Phase Gen GUI open fn not found") nil))
      (catch Exception e
        (log/error "Failed to open Phase Gen GUI:" (ex-message e))
        nil))))

(defn- handle-get-status [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      (let [state (or (platform-be/get-custom-state tile) phase-default-state)]
        {:energy (:energy state 0.0)
         :max-energy (:max-energy state phase-config/max-energy)
         :gen-speed (:gen-speed state 0.0)
         :liquid-amount (:liquid-amount state 0)
         :tank-size (:tank-size state phase-config/tank-size)
         :status (:status state "IDLE")})
      {:energy 0.0 :max-energy 0.0 :gen-speed 0.0 :status "ERROR"})))

(defn register-network-handlers! []
  (net-server/register-handler (msg :get-status) handle-get-status)
  (log/info "Phase Generator network handlers registered"))

(defonce ^:private phase-gen-installed? (atom false))

(defn init-phase-gen!
  []
  (when (compare-and-set! phase-gen-installed? false true)
    (msg-registry/register-block-messages! :phase-gen [:get-status])
    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "phase-gen"
        {:registry-name "phase_gen"
         :impl :scripted
         :blocks ["phase-gen"]
         :tick-fn phase-tick-fn
         :read-nbt-fn phase-scripted-load-fn
         :write-nbt-fn phase-scripted-save-fn}))
    (tile-logic/register-container! "phase-gen" phase-container-fns)
    (platform-cap/declare-capability! :phase-generator IWirelessGenerator
      (fn [be _side] (impls/->WirelessGeneratorImpl be)))
    (tile-logic/register-tile-capability! "phase-gen" :phase-generator)
    (bdsl/register-block!
      (bdsl/create-block-spec
        "phase-gen"
        {:registry-name "phase_gen"
         :physical {:material :metal
                    :hardness 3.0
                    :resistance 6.0
                    :requires-tool true
                    :harvest-tool :pickaxe
                    :harvest-level 1
                    :sounds :metal}
         :rendering {:model-parent "minecraft:block/block"
                     :textures {:all (modid/asset-path "block" "phase_gen")}
                     :flat-item-icon? true}
         :events {:on-right-click open-phase-gen-gui!}}))
    (hooks/register-network-handler! register-network-handlers!)
    (hooks/register-client-renderer! 'cn.li.ac.block.phase-gen.render/init!)
    (log/info "Initialized Phase Generator block")))

