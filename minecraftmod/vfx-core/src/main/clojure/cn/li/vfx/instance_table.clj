(ns cn.li.vfx.instance-table
  "Clojure lifecycle operations over the Java VFX slot carrier."
  (:import [cn.li.mcmod.runtime.effect VfxInstanceTable]))

(set! *warn-on-reflection* true)

(defn allocate-instance!
  [^VfxInstanceTable table effect-index owner world-id seed]
  (let [free-count (.-freeCount table)]
    (when (pos? free-count)
      (let [next-count (dec free-count)
            ^ints free-list (.-freeList table)
            index (aget free-list next-count)]
        (set! (.-freeCount table) next-count)
        (aset-boolean ^booleans (.-alive table) index true)
        (aset-int ^ints (.-effectIndices table) index (int (long effect-index)))
        (aset ^objects (.-owners table) index owner)
        (aset ^objects (.-worldIds table) index world-id)
        (aset-long ^longs (.-seeds table) index (long seed))
        (aset-double ^doubles (.-ages table) index 0.0)
        (aset-long ^longs (.-eventSequences table) index 0)
        index))))

(defn free-instance! [^VfxInstanceTable table ^long index]
  (let [^booleans alive (.-alive table)
        ^ints free-list (.-freeList table)
        free-count (.-freeCount table)]
    (when (aget alive index)
      (aset-boolean alive index false)
      (aset ^objects (.-owners table) index nil)
      (aset ^objects (.-worldIds table) index nil)
      (aset-int free-list free-count (int index))
      (set! (.-freeCount table) (inc free-count))))
  nil)

(defn tick! [^VfxInstanceTable table ^double delta-ticks]
  (let [^booleans alive (.-alive table)
        ^doubles ages (.-ages table)
        n (alength alive)]
    (loop [index (long 0)]
      (when (< index n)
        (when (aget alive index)
          (aset-double ages index (+ (aget ages index) delta-ticks)))
        (recur (inc index)))))
  nil)
