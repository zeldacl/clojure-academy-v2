(ns cn.li.combat.ir
  "Private fixed-width IR encoder for the Clojure combat VM."
  (:import [cn.li.mcmod.runtime.effect CompiledProgram]))

(set! *warn-on-reflection* true)

(def operand-stride 4)
(def opcode-by-ir
  {:const-double 1 :const-long 2 :const-boolean 3 :const-object 4
   :load-param 5 :load-context 6 :move 7 :expr 8 :query 9
   :jump 10 :jump-if-false 11 :iter-open 12 :iter-next 13
   :latch-claim 14 :session-patch 15 :owner-patch 16 :txn-begin 17
   :txn-guard 18 :txn-reserve 19 :txn-commit 20 :emit-action 21
   :emit-vfx 22 :emit-event 23 :finish 24 :reject 25 :component 26})

(defn- fail [message data]
  (throw (ex-info message data)))

(defn- append-constant [state kind value]
  (update state (keyword (str (name kind) "-constants")) conj value))

(defn- operands-for [node]
  (case (:ir/op node)
    :const-double [(:dst node) (:const-index node) 0 0]
    :const-long [(:dst node) (:const-index node) 0 0]
    :const-boolean [(:dst node) (if (:value node) 1 0) 0 0]
    :const-object [(:dst node) (:const-index node) 0 0]
    :load-param [(:dst node) (:param-index node) 0 0]
    :load-context [(:dst node) (:context-index node) 0 0]
    :move [(:dst node) (:src node) 0 0]
    :expr [(:dst node) (:opcode-index node 0) (first (:args node)) (second (:args node))]
    :query [(:capability-id node) (:request-index node) (:result-slot node) 0]
    :jump [(:target node) 0 0 0]
    :jump-if-false [(:condition-slot node) (:target node) 0 0]
    :iter-open [(:batch-slot node) (:iterator-slot node) (:limit node 0) 0]
    :iter-next [(:iterator-slot node) (:item-slot node) (:end-target node) 0]
    :latch-claim [(:key-slot node) (:storage-index node) (:result-slot node) 0]
    :session-patch [(:patch-index node) 0 0 0]
    :owner-patch [(:patch-index node) 0 0 0]
    :txn-begin [(:transaction-index node) 0 0 0]
    :txn-guard [(:guard-index node) (:fail-target node) 0 0]
    :txn-reserve [(:reservation-index node) 0 0 0]
    :txn-commit [(:result-slot node) (:fail-target node) 0 0]
    :emit-action [(:action-index node) 0 0 0]
    :emit-vfx [(:signal-index node) 0 0 0]
    :emit-event [(:event-index node) 0 0 0]
    :finish [(:outcome-index node) (if (:finish-session? node) 1 0) 0 0]
    :reject [(:reason-index node) 0 0 0]
    :component [(:const-index node) 0 0 0]
    (fail "unknown IR op" {:node node})))

(defn- index-node [state node]
  (let [kind (:ir/op node)]
    (case kind
      :const-double (let [index (count (:double-constants state))]
                     [(assoc node :const-index index)
                      (append-constant state :double (:value node))])
      :const-long (let [index (count (:long-constants state))]
                    [(assoc node :const-index index)
                     (append-constant state :long (:value node))])
      :const-object (let [index (count (:object-constants state))]
                      [(assoc node :const-index index)
                       (append-constant state :object (:value node))])
      :component (let [index (count (:object-constants state))]
                   [(assoc node :const-index index)
                    (append-constant state :object (:data node))])
      [node state])))

(defn encode
  [ir {:keys [double long boolean object]
       :or {double 0 long 0 boolean 0 object 0}}]
  (let [initial {:double-constants [] :long-constants [] :object-constants []
                 :nodes []}
        indexed-state (reduce
                        (fn [state node]
                          (let [[indexed state] (index-node state node)]
                            (update state :nodes conj indexed)))
                        initial ir)
        indexed-ir (:nodes indexed-state)
        state (dissoc indexed-state :nodes)
        opcodes (int-array
                  (map #(int (get opcode-by-ir (:ir/op %) 0)) indexed-ir))
        operands (int-array
                   (mapcat #(map (fn [value] (int (or value 0)))
                                  (operands-for %)) indexed-ir))]
    (when (some zero? opcodes)
      (fail "IR contains unknown opcode" {:ir ir}))
    (CompiledProgram.
      opcodes operands
      (double-array (map double (:double-constants state)))
      (long-array (map long (:long-constants state)))
      (object-array (:object-constants state))
      (int-array [0])
      (int double) (int long) (int boolean)
      (int (+ object (count (:object-constants state)))))))
