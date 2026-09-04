(ns cn.li.node.cost
  "Static analysis over compiled IR (cn.li.node.compile output): total
   complexity, host command count, effect set, and a best-effort iteration
   bound. Used for: (1) a build-time budget gate; (2) deriving a
   developer ability's :costs from its actual program instead of a
   hand-tuned number that drifts from the content (Psi's static complexity
   model); (3) player-submitted spell admission (cn.li.combat.player);
   (4) an editor's live cost/complexity readout (cn.li.node.schema-export
   supplies the per-node :cost this walks).

   Pure ops (:pure/:get/:tun/:cap/:copy/:convert) cost 0 -- only :query/
   :action instructions cost anything, taken from the vocab entry named by
   the instruction's :node."
  (:require [cn.li.node.ir :as ir]))

(defn- instr-cost [vocab instr]
  (if (contains? #{:query :action} (:op instr))
    (long (or (:cost (get vocab (:node instr))) 0))
    0))

(defn- instr-effects [vocab instr]
  (if (contains? #{:query :action} (:op instr))
    (or (:effects (get vocab (:node instr))) #{})
    #{}))

(defn- all-instrs [ir]
  (mapcat :instrs (:blocks ir)))

(defn- loop-headers
  "Every :branch instruction carrying compile.clj's :loop-hint breadcrumb
   (see cn.li.node.compile/compile-each) -- the only source of a static
   iteration bound this analysis has."
  [ir]
  (filter :loop-hint (all-instrs ir)))

(defn- static-limit
  "Best-effort: only known when the each's collection-producing call site
   had a literal (non-expression) :limit argument. Returns nil (meaning
   \"unbounded/unknown to static analysis\") otherwise -- callers (e.g.
   player admission) must treat nil as a rejection, not as zero."
  [ir loop-header]
  (let [coll-reg (get-in loop-header [:loop-hint :collection])
        producer (first (filter #(= coll-reg (:dst %)) (all-instrs ir)))]
    (when (and producer (contains? #{:query :action} (:op producer)))
      (let [limit-param (:limit (:args producer))]
        (when (and limit-param (= :const (first limit-param)))
          (let [bank (second limit-param) slot (nth limit-param 2)]
            (get-in ir [:constants bank slot])))))))

(defn analyze
  "vocab: {node-id {:cost n :effects #{...}}} -- the same table
   cn.li.node.compile was given. Returns:
     :complexity     sum of every :query/:action instruction's :cost
     :host-commands  count of :action instructions (commands actually
                     queued against the host, as opposed to :query reads)
     :effects        union of every :query/:action instruction's :effects
     :max-iterations sum of each each-loop's static-limit (nil in the sum
                     poisons the whole result to nil -- \"at least one loop
                     bound is unknown\" must not silently read as small)"
  [ir vocab]
  (let [instrs (all-instrs ir)
        limits (map #(static-limit ir %) (loop-headers ir))]
    {:complexity (reduce + 0 (map #(instr-cost vocab %) instrs))
     :host-commands (count (filter #(= :action (:op %)) instrs))
     :effects (reduce into #{} (map #(instr-effects vocab %) instrs))
     :max-iterations (when (every? some? limits) (reduce + 0 limits))}))
