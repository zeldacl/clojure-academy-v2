(ns cn.li.node.ir
  "Shape and structural invariants for the flat, block-based IR that
   cn.li.node.compile produces and cn.li.mcmod.runtime.effect.emit (mcmod,
   not node-core -- see verifyNodeCoreDependencyDirection) consumes.

   NOT literal SSA: each register is a mutable per-bank array slot compiled
   to a fixed [bank slot] address (see cn.li.node.types/bank), allocated
   once per compile-time binding site but free to be re-written across loop
   iterations at RUNTIME the same way a real bytecode VM's local slots are.
   Branch/loop merge points do not need explicit phi instructions because
   both arms write the SAME pre-allocated register instead of a fresh one --
   the simplification traded away is pure def-once SSA, not determinism or
   the ability to reason about a register's type, which is fixed for its
   whole lifetime regardless.

   A register reference is `[kind bank slot]`: kind is :reg (a mutable
   compile-time-allocated slot) or :const (an index into the IR's own
   :constants pool); bank is one of #{:doubles :longs :booleans :objects}
   (cn.li.node.types/bank); slot is a non-negative index within that
   kind+bank's array.")

(def register-banks #{:doubles :longs :booleans :objects})
(def register-kinds #{:reg :const})

(defn register? [v]
  (and (vector? v) (= 3 (count v))
       (contains? register-kinds (nth v 0))
       (contains? register-banks (nth v 1))
       (nat-int? (nth v 2))))

(def instr-ops
  #{:pure :get :tun :cap :query :action :vfx :event :state-read :state-write
    :copy :convert :map-lit :vec-lit :branch :jump :finish})

(def terminator-ops #{:branch :jump :finish})

(defn- check [pred msg data]
  (when-not pred (throw (ex-info msg data)))
  pred)

(defn validate-instr! [instr]
  (check (contains? instr-ops (:op instr)) "unknown IR op" {:instr instr})
  (check (string? (:nid instr)) "IR instruction missing :nid" {:instr instr})
  (doseq [k [:dst :src :test]]
    (when (contains? instr k)
      (check (register? (get instr k)) "IR field is not a register reference" {:instr instr :field k})))
  (when (contains? instr :args)
    (let [args (:args instr)]
      (check (or (vector? args) (map? args)) "IR :args must be a vector or map" {:instr instr})
      (doseq [v (if (map? args) (vals args) args)]
        (check (register? v) "IR :args entry is not a register reference" {:instr instr :value v}))))
  instr)

(defn validate-block! [block]
  (check (nat-int? (:id block)) "IR block missing integer :id" {:block block})
  (check (vector? (:instrs block)) "IR block :instrs must be a vector" {:block block})
  (check (seq (:instrs block)) "IR block has no instructions" {:block block})
  (run! validate-instr! (:instrs block))
  (let [terminator (:op (last (:instrs block)))]
    (check (contains? terminator-ops terminator)
           "IR block does not end in a terminator (:branch/:jump/:finish)"
           {:block-id (:id block) :last-op terminator}))
  (doseq [instr (butlast (:instrs block))]
    (check (not (contains? terminator-ops (:op instr)))
           "IR terminator instruction is not the last instruction in its block"
           {:block-id (:id block) :instr instr}))
  block)

(defn validate!
  "Throws ex-info on the first structural violation found; returns `ir`
   unchanged otherwise. Structural only -- does not re-run type checking
   (that is cn.li.node.compile's job at construction time); this exists so
   any IR handed to the emitter or the pretty-printer, including one
   produced by a future graph editor, can be defensively checked before use."
  [ir]
  (check (= 1 (:ir/version ir)) "unsupported IR version" {:ir ir})
  (check (keyword? (:id ir)) "IR missing :id" {:ir ir})
  (check (map? (:entries ir)) "IR missing :entries" {:ir ir})
  (check (map? (:constants ir)) "IR missing :constants" {:ir ir})
  (let [blocks (:blocks ir)]
    (check (vector? blocks) "IR :blocks must be a vector" {:ir ir})
    (check (seq blocks) "IR :blocks is empty" {:ir ir})
    (run! validate-block! blocks)
    (doseq [[entry-key block-id] (:entries ir)]
      (check (nat-int? block-id) "IR entry value must be a block index" {:entry entry-key})
      (check (< block-id (count blocks)) "IR entry points at a nonexistent block" {:entry entry-key :block-id block-id}))
    (doseq [block blocks instr (:instrs block)]
      (doseq [target-key [:then :else :target]]
        (when (contains? instr target-key)
          (check (nat-int? (get instr target-key)) "IR jump/branch target must be an integer" {:instr instr})
          (check (< (get instr target-key) (count blocks))
                 "IR branch/jump target out of range" {:instr instr})))))
  ir)
