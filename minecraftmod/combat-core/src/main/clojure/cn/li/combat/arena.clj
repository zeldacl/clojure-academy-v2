(ns cn.li.combat.arena
  "Allocation-free reset/allocate/free operations for a generic slot arena."
  (:import [cn.li.mcmod.runtime.effect SlotArena]))

(set! *warn-on-reflection* true)

(defn reset-arena! [^SlotArena arena]
  (let [^ints free-list (.-freeList arena)
        n (alength free-list)]
    (loop [i (long 0)]
      (when (< i n)
        (aset-int free-list i (int i))
        (recur (inc i))))
    (set! (.-freeCount arena) n))
  arena)

(defn allocate! [^SlotArena arena]
  (let [free-count (.-freeCount arena)]
    (when (pos? free-count)
      (let [^ints free-list (.-freeList arena)
            next-count (dec free-count)
            index (aget free-list next-count)]
        (set! (.-freeCount arena) next-count)
        index))))

(defn free! [^SlotArena arena ^long index]
  (let [free-count (.-freeCount arena)]
    (aset-int ^ints (.-freeList arena) free-count (int index))
    (set! (.-freeCount arena) (inc free-count)))
  nil)
