(ns cn.li.vfx.vm
  "Clojure VFX graph update/sample logic over fixed-layout instance slots."
  (:import [cn.li.mcmod.runtime.effect CompiledProgram VfxInstanceTable]))

(set! *warn-on-reflection* true)

(defn update-instance!
  ([^CompiledProgram program ^VfxInstanceTable table ^long index ^double delta-ticks]
   (update-instance! program table index delta-ticks Double/POSITIVE_INFINITY nil))
  ([^CompiledProgram program ^VfxInstanceTable table index delta-ticks
    duration-ticks free-fn]
  (let [index (long index)
        delta-ticks (double delta-ticks)
        duration-ticks (double duration-ticks)]
    (when (aget ^booleans (.-alive table) index)
    (aset-double ^doubles (.-ages table) index
                 (+ (aget ^doubles (.-ages table) index) delta-ticks))
    (when (and (< duration-ticks Double/POSITIVE_INFINITY)
               (>= (aget ^doubles (.-ages table) index) duration-ticks))
      (if free-fn
        (free-fn table index)
        (aset-boolean ^booleans (.-alive table) index false))))
  nil)))

(defn tick!
  "Advance all live instances and destroy transient slots at duration." 
  [^CompiledProgram program ^VfxInstanceTable table delta-ticks
   durations free-fn]
  (let [^booleans alive (.-alive table)
        n (alength alive)]
    (loop [index (long 0)]
      (when (< index n)
        (when (aget alive index)
          (let [effect-index (aget ^ints (.-effectIndices table) index)
                duration (double (get durations effect-index Double/POSITIVE_INFINITY))]
            (update-instance! program table index delta-ticks duration free-fn)))
        (recur (inc index)))))
  nil)

(defn sample-instance
  [^CompiledProgram program ^VfxInstanceTable table ^long index]
  (when (aget ^booleans (.-alive table) index)
    {:effect-index (aget ^ints (.-effectIndices table) index)
     :owner (aget ^objects (.-owners table) index)
     :age (aget ^doubles (.-ages table) index)
     :seed (aget ^longs (.-seeds table) index)}))
