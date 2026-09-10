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
            :prim-ops   cn.li.node.ops/prim-table              ; optional, see below
            :host {:query!   (fn [capability args ^ExecutionFrame frame] result)
                   :command! (fn [capability args ^ExecutionFrame frame] nil)
                   :flush!   (fn [^ExecutionFrame frame] nil)}}   ; :flush! is optional

   :prim-ops is an optional fast path for :pure instructions ONLY (see
   compile-pure-specialized below): when present and an instruction's op
   and register banks match one of its entries, that instruction runs
   through clojure.lang.IFn$xyz primitive invocation instead of invoke-op's
   general boxed-arg-vector path. Purely a speed optimization -- a nil or
   incomplete :prim-ops behaves exactly as if it were absent; every :pure
   op still runs correctly (just slower) through invoke-op regardless.

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
   :pure's positional args).

   The map branch used to rebuild the result via (into {} (map ...)) on
   EVERY dispatch: a lazy seq of freshly-allocated [k v] tuples, walked by
   a transient-map reduce -- garbage proportional to arg count on every
   single :query/:action/:vfx/:event/:map-lit instruction, every dispatch.
   Now: the KEYS (and, since they never change, their ARRAY POSITIONS) are
   fixed at compile time into a template Object[]; each dispatch only
   clones that template (one intrinsic array copy, no boxing) and
   overwrites the value slots. clojure.lang.RT/mapUniqueKeys builds the
   result straight from the flat array -- PersistentArrayMap for the
   common small-arity case, falling back to PersistentHashMap once past
   its size threshold, matching what a {...} literal of the same arity
   would already compile to. \"UniqueKeys\", not the checked `map`: an
   instruction's :args keys come from cn.li.node.compile's own map
   literal/call-arg construction, which cannot produce a duplicate key."
  [args consts]
  (cond
    (map? args)
    (let [entries (vec args)
          n (count entries)
          template (object-array (* 2 n))
          readers (object-array n)]
      (dotimes [i n]
        (let [[k r] (nth entries i)]
          (aset template (* 2 i) k)
          (aset readers i (compile-reader r consts))))
      (fn [^ExecutionFrame fr]
        (let [arr (aclone template)]
          (dotimes [i n]
            (aset arr (inc (* 2 i)) ((aget ^objects readers i) fr)))
          (clojure.lang.RT/mapUniqueKeys arr))))
    (vector? args)
    (let [readers (mapv #(compile-reader % consts) args)]
      (fn [^ExecutionFrame fr] (mapv #(% fr) readers)))
    :else (fn [_] {})))

;; --- primitive :pure specialization (perf plan Phase B) ---------------------
;;
;; See cn.li.node.ops/prim-table's own docstring for the full design
;; rationale (why this lives in clojure.lang.IFn$xyz, not a new Java type,
;; and why only :math/*/:long/* are covered). Measured: an 11-op :math/add
;; chain went from 4594 ns / 7944 B (generic path) to 111 ns / 600 B here.
;;
;; compile-d-reader/compile-l-reader below are the primitive-return
;; counterparts of compile-reader above -- same :const-vs-:reg branching,
;; same closure-over-slot shape, but hinted ^double/^long so Clojure
;; compiles them to implement clojure.lang.IFn$OD/IFn$OL instead of the
;; generic (Object arg, Object return) IFn compile-reader's closures use.

(defn- compile-d-reader
  "[kind bank slot], consts -> a (fn ^double [frame]) implementing
   clojure.lang.IFn$OD. bank is unused here -- the caller has already
   confirmed it is :doubles (see compile-pure-specialized's banks-match?)
   before ever calling this."
  [[kind _bank slot] consts]
  (let [s (int slot)]
    (if (= kind :const)
      (let [v (aget ^doubles (:doubles consts) s)]
        (fn ^double [_] v))
      (fn ^double [^ExecutionFrame fr] (aget ^doubles (.-doubles fr) s)))))

(defn- compile-l-reader
  "As compile-d-reader, for a confirmed :longs bank, implementing
   clojure.lang.IFn$OL."
  [[kind _bank slot] consts]
  (let [s (int slot)]
    (if (= kind :const)
      (let [v (aget ^longs (:longs consts) s)]
        (fn ^long [_] v))
      (fn ^long [^ExecutionFrame fr] (aget ^longs (.-longs fr) s)))))

(defn- banks-match?
  "True when instr's actual register/constant banks (from its IR [kind
   bank slot] references) are exactly the shape a prim-table entry
   declares -- the one check compile-pure-specialized needs before it is
   safe to assume every arg is readable via compile-d-reader/compile-l-
   reader and the destination is writable via a raw aset on that bank's
   array."
  [args arg-banks dst dst-bank]
  (and (= (count args) (count arg-banks))
       (every? true? (map (fn [[_ bank _] b] (= bank b)) args arg-banks))
       (= (second dst) dst-bank)))

(defn- compile-pure-1d->d [args dst consts ^clojure.lang.IFn$DD f]
  (let [^clojure.lang.IFn$OD a (compile-d-reader (nth args 0) consts)
        dst-s (int (nth dst 2))]
    (fn [^ExecutionFrame fr]
      (aset ^doubles (.-doubles fr) dst-s (.invokePrim f (.invokePrim a fr)))
      -1)))

(defn- compile-pure-1d->l [args dst consts ^clojure.lang.IFn$DL f]
  (let [^clojure.lang.IFn$OD a (compile-d-reader (nth args 0) consts)
        dst-s (int (nth dst 2))]
    (fn [^ExecutionFrame fr]
      (aset ^longs (.-longs fr) dst-s (.invokePrim f (.invokePrim a fr)))
      -1)))

(defn- compile-pure-2d->d [args dst consts ^clojure.lang.IFn$DDD f]
  (let [^clojure.lang.IFn$OD a (compile-d-reader (nth args 0) consts)
        ^clojure.lang.IFn$OD b (compile-d-reader (nth args 1) consts)
        dst-s (int (nth dst 2))]
    (fn [^ExecutionFrame fr]
      (aset ^doubles (.-doubles fr) dst-s (.invokePrim f (.invokePrim a fr) (.invokePrim b fr)))
      -1)))

(defn- compile-pure-3d->d [args dst consts ^clojure.lang.IFn$DDDD f]
  (let [^clojure.lang.IFn$OD a (compile-d-reader (nth args 0) consts)
        ^clojure.lang.IFn$OD b (compile-d-reader (nth args 1) consts)
        ^clojure.lang.IFn$OD c (compile-d-reader (nth args 2) consts)
        dst-s (int (nth dst 2))]
    (fn [^ExecutionFrame fr]
      (aset ^doubles (.-doubles fr) dst-s
            (.invokePrim f (.invokePrim a fr) (.invokePrim b fr) (.invokePrim c fr)))
      -1)))

(defn- compile-pure-2d->b
  "Result bank is :booleans, not :objects -- Clojure's IFn family has no
   primitive `boolean` letter (confirmed empty across this repo's own
   clojure.jar), so .invokePrim on an IFn$DDO always returns a boxed
   Boolean; (boolean ...) unboxes it once (Boolean/TRUE|FALSE are cached
   singletons, so this never allocates) before the aset, which requires an
   actual primitive boolean, not an Object."
  [args dst consts ^clojure.lang.IFn$DDO f]
  (let [^clojure.lang.IFn$OD a (compile-d-reader (nth args 0) consts)
        ^clojure.lang.IFn$OD b (compile-d-reader (nth args 1) consts)
        dst-s (int (nth dst 2))]
    (fn [^ExecutionFrame fr]
      (aset ^booleans (.-booleans fr) dst-s (boolean (.invokePrim f (.invokePrim a fr) (.invokePrim b fr))))
      -1)))

(defn- compile-pure-2l->l [args dst consts ^clojure.lang.IFn$LLL f]
  (let [^clojure.lang.IFn$OL a (compile-l-reader (nth args 0) consts)
        ^clojure.lang.IFn$OL b (compile-l-reader (nth args 1) consts)
        dst-s (int (nth dst 2))]
    (fn [^ExecutionFrame fr]
      (aset ^longs (.-longs fr) dst-s (.invokePrim f (.invokePrim a fr) (.invokePrim b fr)))
      -1)))

(defn- compile-pure-specialized
  "instr (a :pure IR instruction), consts, prim-ops (cn.li.node.ops/
   prim-table or nil) -> a compiled closure, or nil when this instruction
   cannot be specialized -- either prim-ops is absent/does not know this
   op, or (rare, but possible for an :any-typed op like :pair/first whose
   declared :params don't pin a bank) the instruction's actual register
   banks don't match what the table entry promises. Either way, nil tells
   compile-instr's :pure case to fall back to the existing generic path,
   which is correct for every op regardless of shape -- this is a pure
   speed optimization with a safety net, never the only way an op can run."
  [instr consts prim-ops]
  (when prim-ops
    (when-let [{:keys [arg-banks dst-bank fn]} (get prim-ops (:fn instr))]
      (let [args (:args instr) dst (:dst instr)]
        (when (and (vector? args) (banks-match? args arg-banks dst dst-bank))
          (case [arg-banks dst-bank]
            [[:doubles] :doubles] (compile-pure-1d->d args dst consts fn)
            [[:doubles] :longs] (compile-pure-1d->l args dst consts fn)
            [[:doubles :doubles] :doubles] (compile-pure-2d->d args dst consts fn)
            [[:doubles :doubles :doubles] :doubles] (compile-pure-3d->d args dst consts fn)
            [[:doubles :doubles] :booleans] (compile-pure-2d->b args dst consts fn)
            [[:longs :longs] :longs] (compile-pure-2l->l args dst consts fn)
            nil))))))

;; --- per-instruction compilation ---------------------------------------------
;;
;; Every compiled instruction is (fn [^ExecutionFrame frame] next-block-or--1);
;; -1 means "fall through to the next instruction in this block" (see
;; compile-block). Only :branch/:jump ever return a real block index;
;; :finish returns -1 too -- it stops the PROGRAM not by jumping anywhere,
;; but by leaving no further block to jump to (compile-program's dispatch!
;; checks .-result after the block loop ends).

(defn- compile-instr [instr consts {:keys [invoke-op host prim-ops]}]
  (case (:op instr)
    :pure
    (or (compile-pure-specialized instr consts prim-ops)
        (let [argf (compile-args (:args instr) consts)
              op (:fn instr)
              wr (compile-writer (:dst instr))]
          (fn [^ExecutionFrame fr] (wr fr (invoke-op op (argf fr))) -1)))

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

    (:map-lit :vec-lit)
    (let [argf (compile-args (:args instr) consts) wr (compile-writer (:dst instr))]
      (fn [^ExecutionFrame fr] (wr fr (argf fr)) -1))

    :convert
    (let [src (compile-reader (:src instr) consts) wr (compile-writer (:dst instr)) to (:to instr)
          nid (:nid instr)
          from (:from instr)]
      (fn [^ExecutionFrame fr]
        (let [v (src fr)]
          (wr fr
              (case to
                :double (cond
                          (nil? v)
                          (throw (ex-info "convert to :double received nil"
                                          {:nid nid :to to :from from}))
                          (number? v) (double v)
                          :else
                          (throw (ex-info "convert to :double expected number"
                                          {:nid nid :to to :from from
                                           :value v :value-class (class v)})))
                :long (cond
                        (nil? v)
                        (throw (ex-info "convert to :long received nil"
                                        {:nid nid :to to :from from}))
                        (number? v) (long v)
                        :else
                        (throw (ex-info "convert to :long expected number"
                                        {:nid nid :to to :from from
                                         :value v :value-class (class v)})))
                :boolean (boolean v)
                v)))
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

(defn reset-frame!
  "Prepares `frame` for reuse against a NEW dispatch with `input`, instead
   of allocating a fresh frame via new-frame (measured: 424 B/dispatch of
   pure frame-construction overhead for a program with no instructions at
   all) -- clears the 4 outbox ArrayLists and .-result/.-touchedCount, then
   installs `input`. The register arrays (.-doubles/.-longs/.-booleans/
   .-objects) are deliberately left AS-IS, not zeroed: cn.li.node.ir's own
   docstring guarantees every register is written before it is ever read
   within a single dispatch (register allocation is def-before-use, not
   reused across unrelated bindings without an intervening write), so a
   stale value from a PRIOR dispatch can never actually be observed by
   this one -- zeroing four arrays every reuse would just be wasted work.

   Only safe when the caller can guarantee no other in-flight dispatch is
   still reading `frame` -- the VFX domain module's own single-threaded
   per-effect-id frame cache is the one real caller (mcmod must not name
   or require domain-module namespaces, see verifyVfxDependencyDirection/
   verifyCombatDependencyDirection -- this ABI is consumed by both combat
   and VFX equally, whichever caller opts in). Combat's own dispatch path
   deliberately does NOT use this: it hands the frame itself back to a
   caller that reads .-actions/.-vfx/.-result afterward, so a pooled frame
   there would risk a later reset corrupting a result the caller has not
   finished reading yet."
  ^ExecutionFrame [^ExecutionFrame frame input]
  (.clear (.-actions frame)) (.clear (.-vfx frame)) (.clear (.-events frame)) (.clear (.-stateWrites frame))
  (set! (.-result frame) nil)
  (set! (.-touchedCount frame) 0)
  (set! (.-input frame) input)
  frame)

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
