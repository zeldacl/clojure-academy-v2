(ns cn.li.mc1201.gui.slots.common
  "Shared slot construction/layout helpers for platform menu bridges."
  (:require [cn.li.mcmod.gui.slot-registry :as slot-registry]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.world.inventory Slot AbstractContainerMenu]
           [net.minecraft.world.entity.player Inventory]
           [net.minecraft.world.item ItemStack]))

(defn create-energy-slot
  [inventory slot-index x y]
  (proxy [Slot] [inventory (int slot-index) (int x) (int y)]
    (mayPlace [^ItemStack stack]
      (let [pred (slot-registry/get-slot-validator :energy)]
        (and pred (pred stack))))
    (getMaxStackSize [& _] 1)))

(defn create-plate-slot
  [inventory slot-index x y]
  (proxy [Slot] [inventory (int slot-index) (int x) (int y)]
    (mayPlace [^ItemStack stack]
      (let [pred (slot-registry/get-slot-validator :plate)]
        (and pred (pred stack))))
    (getMaxStackSize [& _] 1)))

(defn create-core-slot
  [inventory slot-index x y]
  (proxy [Slot] [inventory (int slot-index) (int x) (int y)]
    (mayPlace [^ItemStack stack]
      (let [pred (slot-registry/get-slot-validator :core)]
        (and pred (pred stack))))
    (getMaxStackSize [& _] 1)))

(defn create-output-slot
  [inventory slot-index x y]
  (proxy [Slot] [inventory (int slot-index) (int x) (int y)]
    (mayPlace [_stack] false)
    (mayPickup [_player] true)))

(defn create-standard-slot
  [inventory slot-index x y]
  (Slot. inventory (int slot-index) (int x) (int y)))

(defn create-conditional-slot
  [inventory slot-index x y active?-fn]
  (proxy [Slot] [inventory (int slot-index) (int x) (int y)]
    (mayPlace [_stack]
      (boolean (active?-fn)))
    (mayPickup [_player]
      (boolean (active?-fn)))))

(defn create-conditional-energy-slot
  [inventory slot-index x y active?-fn]
  (proxy [Slot] [inventory (int slot-index) (int x) (int y)]
    (mayPlace [stack]
      (let [pred (slot-registry/get-slot-validator :energy)]
        (and (active?-fn) pred (pred stack))))
    (mayPickup [_player] (boolean (active?-fn)))
    (getMaxStackSize [& _] 1)))

(defn create-conditional-plate-slot
  [inventory slot-index x y active?-fn]
  (proxy [Slot] [inventory (int slot-index) (int x) (int y)]
    (mayPlace [stack]
      (let [pred (slot-registry/get-slot-validator :plate)]
        (and (active?-fn) pred (pred stack))))
    (mayPickup [_player] (boolean (active?-fn)))
    (getMaxStackSize [& _] 1)))

(defn create-conditional-core-slot
  [inventory slot-index x y active?-fn]
  (proxy [Slot] [inventory (int slot-index) (int x) (int y)]
    (mayPlace [stack]
      (let [pred (slot-registry/get-slot-validator :core)]
        (and (active?-fn) pred (pred stack))))
    (mayPickup [_player] (boolean (active?-fn)))
    (getMaxStackSize [& _] 1)))

(defn create-conditional-output-slot
  [inventory slot-index x y active?-fn]
  (proxy [Slot] [inventory (int slot-index) (int x) (int y)]
    (mayPlace [_stack] (and (active?-fn) false))
    (mayPickup [_player] (boolean (active?-fn)))))

(defn- slot-by-type-conditional
  [type inventory index abs-x abs-y active?-fn]
  (case type
    :energy (create-conditional-energy-slot inventory index abs-x abs-y active?-fn)
    :plate (create-conditional-plate-slot inventory index abs-x abs-y active?-fn)
    :core (create-conditional-core-slot inventory index abs-x abs-y active?-fn)
    :output (create-conditional-output-slot inventory index abs-x abs-y active?-fn)
    (create-conditional-slot inventory index abs-x abs-y active?-fn)))

(defn add-player-inventory-slots!
  ([add-slot! player-inventory x-offset y-offset]
   (add-player-inventory-slots! add-slot! player-inventory x-offset y-offset nil))
  ([add-slot! ^Inventory player-inventory x-offset y-offset active?-fn]
   (let [slot-fn (if active?-fn
                   (fn [inv idx x y] (create-conditional-slot inv idx x y active?-fn))
                   (fn [inv idx x y] (create-standard-slot inv idx x y)))]
     (doseq [row (range 3)
             col (range 9)]
       (let [slot-index (+ (* row 9) col 9)
             x (+ x-offset (* col 18))
             y (+ y-offset (* row 18))
             ^Slot s (slot-fn player-inventory slot-index x y)]
         (add-slot! s)))
     (doseq [col (range 9)]
       (let [slot-index col
             x (+ x-offset (* col 18))
             y (+ y-offset 58)
             ^Slot s (slot-fn player-inventory slot-index x y)]
         (add-slot! s))))))

(defn add-player-hotbar-slots!
  ([add-slot! player-inventory x-offset y-offset]
   (add-player-hotbar-slots! add-slot! player-inventory x-offset y-offset nil))
  ([add-slot! ^Inventory player-inventory x-offset y-offset active?-fn]
   (let [slot-fn (if active?-fn
                   (fn [inv idx x y] (create-conditional-slot inv idx x y active?-fn))
                   (fn [inv idx x y] (create-standard-slot inv idx x y)))]
     (doseq [col (range 9)]
       (let [slot-index col
             x (+ x-offset (* col 18))
             y (+ y-offset 58)
             ^Slot s (slot-fn player-inventory slot-index x y)]
         (add-slot! s))))))

(defn add-gui-slots!
  ([add-slot! get-slot-layout inventory gui-id x-offset y-offset]
   (add-gui-slots! add-slot! get-slot-layout inventory gui-id x-offset y-offset nil))
  ([add-slot! get-slot-layout inventory gui-id x-offset y-offset active?-fn]
   (when-let [layout (get-slot-layout gui-id)]
     (doseq [slot-def (:slots layout)]
       (let [{:keys [type index x y]} slot-def
             abs-x (+ x-offset x)
             abs-y (+ y-offset y)
             ^Slot slot (if active?-fn
                          (slot-by-type-conditional (or type :standard) inventory index abs-x abs-y active?-fn)
                          (case type
                            :energy (create-energy-slot inventory index abs-x abs-y)
                            :plate (create-plate-slot inventory index abs-x abs-y)
                            :core (create-core-slot inventory index abs-x abs-y)
                            :output (create-output-slot inventory index abs-x abs-y)
                            (create-standard-slot inventory index abs-x abs-y)))]
         (add-slot! slot))))))

(defn slot-in-range?
  [get-slot-range slot-index gui-id section]
  (let [[start end] (get-slot-range gui-id section)]
    (<= start slot-index end)))

(defn log-slot-contents
  [container]
  (log/debug "Container slots:")
  (let [n (.size (.getItems ^AbstractContainerMenu container))]
    (doseq [i (range n)]
      (let [^Slot slot (.getSlot ^AbstractContainerMenu container i)
            ^ItemStack stack (.getItem slot)]
        (when-not (.isEmpty stack)
          (log/debug "  Slot" i ":"
                     (.getCount stack) "x"
                     (-> stack .getItem .toString)))))))

(defn validate-slot-setup
  [container expected-count]
  (let [actual-count (.size (.getItems ^AbstractContainerMenu container))]
    (if (= actual-count expected-count)
      (do (log/info "Slot validation passed:" actual-count "slots") true)
      (do (log/error "Slot validation failed: expected" expected-count
                     "but got" actual-count) false))))