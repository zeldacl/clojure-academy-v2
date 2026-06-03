(ns cn.li.ac.block.imag-fusor.logic
  "Imaginary Fusor business logic."
  (:require [cn.li.ac.block.imag-fusor.config :as fusor-config]
            [cn.li.ac.block.imag-fusor.recipes :as recipes]
            [cn.li.ac.block.imag-fusor.schema :as fusor-schema]
            [cn.li.ac.block.machine.container :as machine-container]
            [cn.li.ac.block.machine.inventory-stack :as inv]
            [cn.li.ac.block.machine.matter-unit :as matter-unit]
            [cn.li.ac.block.machine.runtime :as machine-runtime]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.mcmod.platform.item :as pitem]
            [cn.li.mcmod.platform.world :as world]))

(def ^:private fusor-rt
  (machine-runtime/schema-runtime fusor-schema/imag-fusor-schema :server-only? true))

(def fusor-state-schema (:server-schema fusor-rt))
(def fusor-default-state (:default-state fusor-rt))
(def fusor-scripted-load-fn (:load-fn fusor-rt))
(def fusor-scripted-save-fn (:save-fn fusor-rt))

(def fusor-block-state-properties
  (:block-state-properties fusor-rt))

(def fusor-blockstate-updater
  (:blockstate-updater fusor-rt))

;; Inventory helpers
(def input-slot 0)
(def output-slot 1)
(def imag-input-slot fusor-config/imag-input-slot-index)
(def energy-slot fusor-config/energy-slot-index)
(def imag-output-slot fusor-config/imag-output-slot-index)
(def total-slots fusor-config/total-slots)

(def ^:private stack-empty? inv/stack-empty?)
(def ^:private stack-count inv/stack-count)

(defn- rebuild-stack [stack new-count]
  (when (and stack (pos? (int new-count)))
    (when-let [item-id (recipes/item-id-from-stack stack)]
      (pitem/create-item-stack-by-id item-id (int new-count)))))

(defn- consume-stack [stack amount]
  (let [left (- (stack-count stack) (int amount))]
    (when (pos? left) (rebuild-stack stack left))))

(defn- merge-stack-count [existing produced]
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

(defn- phase-liquid-unit? [stack]
  (matter-unit/phase-liquid-unit? stack fusor-config/matter-unit-item-id))

;; Crafting logic
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

(defn- convert-phase-unit [state]
  (matter-unit/convert-phase-unit-state
    state
    {:liquid-in-slot imag-input-slot
     :liquid-out-slot imag-output-slot
     :liquid-per-unit fusor-config/liquid-per-unit
     :tank-size (int (:tank-size state fusor-config/tank-size))
     :matter-unit-item-id fusor-config/matter-unit-item-id
     :max-output-stack 16}))

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

(defn fusor-tick-state
  [state {:keys [level]}]
  (let [state (assoc state
                     :update-ticker (inc (long (get state :update-ticker 0)))
                     :tank-size (int (:tank-size state fusor-config/tank-size))
                     :check-cooldown (int (:check-cooldown state fusor-config/check-interval)))
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
        state (convert-phase-unit state)
        state (if (:working state false)
                (tick-crafting state)
                (update-idle-state state))]
    (assoc state :frame (animated-frame (:working state false)
                                        (world/world-get-day-time* level)))))

(def fusor-tick-fn
  (machine-runtime/make-tick-fn
    {:default-state fusor-default-state
     :tick-state fusor-tick-state
     :blockstate-updater fusor-blockstate-updater}))

(defn- can-place? [_be slot item _face]
  (or (and (= slot input-slot) (boolean (recipes/find-recipe item)))
      (and (= slot imag-input-slot) (phase-liquid-unit? item))
      (and (= slot energy-slot) (energy/is-energy-item-supported? item))))

(defn- can-take? [_be slot _item _face]
  (or (= slot output-slot) (= slot imag-output-slot) (= slot energy-slot)))

(def fusor-container-fns
  (machine-container/make-inventory-container-fns
    {:default-state fusor-default-state
     :slot-count total-slots
     :can-place? can-place?
     :can-take? can-take?}))

(def open-fusor-gui!
  (machine-runtime/make-open-gui-handler :imag-fusor))
