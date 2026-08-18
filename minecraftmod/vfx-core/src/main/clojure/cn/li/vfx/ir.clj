(ns cn.li.vfx.ir
  "Fixed-width VFX graph encoder shared with the neutral runtime carrier."
  (:import [cn.li.mcmod.runtime.effect CompiledProgram]))

(set! *warn-on-reflection* true)

(defn encode [ir]
  (let [nodes (vec ir)
        opcodes (int-array (repeat (count nodes) 1))
        operands (int-array (mapcat (fn [index] [index 0 0 0])
                                    (range (count nodes))))
        objects (object-array
                  (map (fn [node]
                         {:component (:component node)
                          :data (:data node)}) nodes))]
    (CompiledProgram.
      opcodes operands (double-array 0) (long-array 0) objects
      (int-array [0]) 0 0 0 (count objects))))
