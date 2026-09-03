(ns cn.li.mcmod.runtime.effect-emit
  "Compiles cn.li.node.compile's flat block IR into a CompiledProgram: one
   clojure.lang.IFn per block, closing over pre-resolved register readers/
   writers instead of re-dispatching on a keyword every time a block runs.
   Executing a compiled program is then just walking blocks and invoking
   them -- no tree recursion, no per-node case dispatch at run time.

   ZERO project dependency, matching mcmod's own build.gradle ('mcmod must
   not depend on Minecraft, Forge, Fabric, NeoForge, or other loader APIs' --
   and, by long-standing convention here, not on node-core or any domain
   module either). The :pure op table (cn.li.node.ops) and the domain host/
   capability dispatch are both supplied by the CALLER as plain values in
   `env`, never required directly here -- combat-core/vfx-core, which
   already depend on both mcmod and node-core, are the only modules that
   wire the two together:

     env = {:invoke-op (fn [op-name arg-map-or-vec] result)   ; cn.li.node.ops/invoke
            :host {:query!   (fn [capability args ^ExecutionFrame frame] result)
                   :command! (fn [capability args ^ExecutionFrame frame] nil)
                   :flush!   (fn [^ExecutionFrame frame] nil)}}   ; :flush! is optional

   :vfx/:state-read/:state-write are implemented here even though
   cn.li.node.compile cannot emit them yet (S1's DSL grammar doesn't cover
   session state) -- this is a general IR->closures compiler for the whole
   op set cn.li.node.ir declares, not only the subset one module's content
   happens to use yet."
  (:import [cn.li.mcmod.runtime.effect CompiledProgram ExecutionFrame]))

(set! *warn-on-reflection* true)

;; --- constant pools ---------------------------------------------------------

(defn- const-arrays [ir]
  {:doubles (double-array (get-in ir [:constants :doubles]))
   :longs (long-array (get-in ir [:constants :longs]))
   :booleans (boolean-array (get-in ir [:constants :booleans]))
   :objects (object-array (get-in ir [:constants :objects]))})

;; --- register/constant reader/writer compilation -----------------------------

(defn- compile-reader [[kind bank slot] consts]
  (let [s (int slot)]
    (if (= kind :const)
      (case bank
        :doubles (let [v (aget ^doubles (:doubles consts) s)] (fn [_] v))
        :longs (let [v (aget ^longs (:longs consts) s)] (fn [_] v))
        :booleans (let [v (aget ^booleans (:booleans consts) s)] (fn [_] v))
        :objects (let [v (aget ^objects (:objects consts) s)] (fn [_] v)))
      (case bank
        :doubles (fn [^ExecutionFrame fr] (aget ^doubles (.-doubles fr) s))
        :longs (fn [^ExecutionFrame fr] (aget ^longs (.-longs fr) s))
        :booleans (fn [^ExecutionFrame fr] (aget ^booleans (.-booleans fr) s))
        :objects (fn [^ExecutionFrame fr] (aget ^objects (.-objects fr) s))))))

(defn- compile-writer [[kind bank slot]]
  (when (= kind :const)
    (throw (ex-info "cannot write to a :const register" {:bank bank :slot slot})))
  (let [s (int slot)]
    (case bank
      :doubles (fn [^ExecutionFrame fr v] (aset ^doubles (.-doubles fr) s (double v)))
      :longs (fn [^ExecutionFrame fr v] (aset ^longs (.-longs fr) s (long v)))
      :booleans (fn [^ExecutionFrame fr v] (aset ^booleans (.-booleans fr) s (boolean v)))
      :objects (fn [^ExecutionFrame fr v] (aset ^objects (.-objects fr) s v)))))

(defn- compile-args
  "instr's :args -> (fn [frame] resolved-map-or-vector), matching whichever
   shape the IR used (a map for :query/:action/:vfx params, a vector for
   :pure's positional args)."
  [args consts]
  (cond
    (map? args)
    (let [readers (mapv (fn [[k r]] [k (compile-reader r consts)]) args)]
      (fn [^ExecutionFrame fr] (into {} (map (fn [[k rd]] [k (rd fr)])) readers)))
    (vector? args)
    (let [readers (mapv #(compile-reader % consts) args)]
      (fn [^ExecutionFrame fr] (mapv #(% fr) readers)))
    :else (fn [_] {})))

;; --- per-instruction compilation ---------------------------------------------
;;
;; Every compiled instruction is (fn [^ExecutionFrame frame] next-block-or--1);
;; -1 means "fall through to the next instruction in this block" (see
;; compile-block). Only :branch/:jump ever return a real block index;
;; :finish returns -1 too -- it stops the PROGRAM not by jumping anywhere,
;; but by leaving no further block to jump to (compile-program's dispatch!
;; checks .-result after the block loop ends).

(defn- compile-instr [instr consts {:keys [invoke-op host]}]
  (case (:op instr)
    :pure
    (let [argf (compile-args (:args instr) consts)
          op (:fn instr)
          wr (compile-writer (:dst instr))]
      (fn [^ExecutionFrame fr] (wr fr (invoke-op op (argf fr))) -1))

    :get
    (let [src (compile-reader (:src instr) consts) k (:key instr) wr (compile-writer (:dst instr))]
      (fn [^ExecutionFrame fr] (wr fr (get (src fr) k)) -1))

    :tun
    (let [k (:key instr) wr (compile-writer (:dst instr))]
      (fn [^ExecutionFrame fr] (wr fr (get-in (.-input fr) [:tunables k])) -1))

    :cap
    (let [k (:key instr) wr (compile-writer (:dst instr))]
      (fn [^ExecutionFrame fr] (wr fr (get-in (.-input fr) [:capabilities k])) -1))

    :state-read
    (let [k (:key instr) wr (compile-writer (:dst instr))]
      (fn [^ExecutionFrame fr] (wr fr (get-in (.-input fr) [:state k])) -1))

    :state-write
    (let [k (:key instr) src (compile-reader (:src instr) consts)]
      (fn [^ExecutionFrame fr] (.add (.-stateWrites fr) {:key k :value (src fr)}) -1))

    :copy
    (let [src (compile-reader (:src instr) consts) wr (compile-writer (:dst instr))]
      (fn [^ExecutionFrame fr] (wr fr (src fr)) -1))

    :convert
    (let [src (compile-reader (:src instr) consts) wr (compile-writer (:dst instr)) to (:to instr)]
      (fn [^ExecutionFrame fr]
        (wr fr (case to
                 :double (double (src fr))
                 :long (long (src fr))
                 :boolean (boolean (src fr))
                 (src fr)))
        -1))

    :query
    (let [argf (compile-args (:args instr) consts)
          cap (:capability instr)
          query! (:query! host)
          flush! (:flush! host)
          barrier? (boolean (:barrier? instr))
          wr (compile-writer (:dst instr))]
      (fn [^ExecutionFrame fr]
        (when (and barrier? flush!) (flush! fr))
        (wr fr (query! cap (argf fr) fr))
        -1))

    :action
    (let [argf (compile-args (:args instr) consts) cap (:capability instr) command! (:command! host)]
      (fn [^ExecutionFrame fr] (command! cap (argf fr) fr) -1))

    :vfx
    (let [argf (compile-args (:args instr) consts) nid (:nid instr)]
      (fn [^ExecutionFrame fr] (.add (.-vfx fr) (assoc (argf fr) :nid nid)) -1))

    :event
    (let [argf (compile-args (:args instr) consts) event-type (:event-type instr) nid (:nid instr)]
      (fn [^ExecutionFrame fr] (.add (.-events fr) (assoc (argf fr) :type event-type :nid nid)) -1))

    :branch
    (let [test (compile-reader (:test instr) consts) th (int (:then instr)) el (int (:else instr))]
      (fn [^ExecutionFrame fr] (if (test fr) th el)))

    :jump
    (let [target (int (:target instr))]
      (fn [_] target))

    :finish
    (let [outcome {:outcome (:outcome instr) :next-phase (:next-phase instr)
                  :end-ability? (boolean (:end-ability? instr))}]
      (fn [^ExecutionFrame fr] (set! (.-result fr) outcome) -1))))

(defn- compile-block [{:keys [instrs]} consts env]
  (let [fs (object-array (mapv #(compile-instr % consts env) instrs))
        n (alength fs)]
    (fn [^ExecutionFrame fr]
      (loop [i (int 0)]
        (if (== i n)
          -1
          (let [j (long ((aget ^objects fs i) fr))]
            (if (neg? j) (recur (unchecked-inc i)) j)))))))

;; --- top level ----------------------------------------------------------------

(defn compile-program
  "ir: cn.li.node.compile output, already ir/validate!-checked by the
   caller (this namespace does not depend on node-core, so it cannot call
   ir/validate! itself). Returns a CompiledProgram."
  ^CompiledProgram [ir env]
  (let [consts (const-arrays ir)
        blocks (object-array (mapv #(compile-block % consts env) (:blocks ir)))
        ^java.util.HashMap entries (java.util.HashMap.)
        registers (:registers ir)]
    (doseq [[k v] (:entries ir)] (.put entries k (Integer/valueOf (int v))))
    (CompiledProgram. blocks
                      (:doubles consts) (:longs consts) (:booleans consts) (:objects consts)
                      entries
                      (int (or (:doubles registers) 0))
                      (int (or (:longs registers) 0))
                      (int (or (:booleans registers) 0))
                      (int (or (:objects registers) 0)))))

(defn new-frame
  "A fresh ExecutionFrame sized for `program`, ready for one dispatch.
   `input` is the read-only per-dispatch map :tun/:cap/:state-read instrs
   consult (see cn.li.mcmod.runtime.effect.ExecutionFrame's docstring)."
  ^ExecutionFrame [^CompiledProgram program input]
  (ExecutionFrame. (double-array (.-doubleRegisterCount program))
                   (long-array (.-longRegisterCount program))
                   (boolean-array (.-booleanRegisterCount program))
                   (object-array (.-objectRegisterCount program))
                   (java.util.ArrayList.) (java.util.ArrayList.) (java.util.ArrayList.) (java.util.ArrayList.)
                   input
                   (int-array 0)))

(defn dispatch!
  "Run `program` from `entry` (a keyword present in its :entries) against
   `frame`, returning `frame` once no block reports a next index. A
   program that never reaches :finish simply runs out of blocks -- .-result
   stays nil; callers must treat that as \"no outcome\", not as a crash."
  ^ExecutionFrame [^CompiledProgram program entry ^ExecutionFrame frame]
  (let [start (.get ^java.util.Map (.-entries program) entry)]
    (when (nil? start)
      (throw (ex-info "no such program entry" {:entry entry :known (keys (.-entries program))})))
    (loop [b (int start)]
      (when-not (neg? b)
        (recur (int ((aget ^objects (.-blocks program) b) frame))))))
  frame)
