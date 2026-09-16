(ns cn.li.combat.handler-request-keys-test
  "A host handler must not read a request key that no node can supply.

   This is the general form of the bug that had five raycast nodes sharing
   one handler: platform/raycast! `case`d on a :query-kind that nothing
   ever put on the request, so four of its five branches were dead. The
   same sweep also found discard-entity! scanning a 128-block radius behind
   an :entity-type the node never declared, and :world/sound's handler
   reading :volume/:pitch/:source that content had no way to set -- so
   every server-side sound played at the handler's fallbacks.

   None of those are visible to host-parity's checks, which ask whether a
   capability is REGISTERED. A registered handler reading a phantom key is
   well-formed, compiles, runs, and quietly takes the wrong branch.

   Reads the handler's own source rather than its arglist, because what a
   handler destructures IS the contract it expects the node to declare, and
   nothing else records that."
  (:require [clojure.repl :as repl]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cn.li.combat.dsl-vocabulary :as vocab]
            [cn.li.combat.platform :as platform]))

(def ^:private engine-injected
  "cn.li.ability.engine-v2's :query!/:command! assoc these onto every
   request, so no node declares them and every handler may read them."
  #{"owner" "world-id" "ability-id"})

(def ^:private params-by-capability
  (reduce (fn [acc [node-id spec]]
            (update acc (or (:capability spec) node-id)
                    (fnil into #{}) (map name (keys (:params spec)))))
          {}
          vocab/nodes))

(def ^:private capabilities-by-handler-var
  "handler var -> the capabilities it is registered under. A handler may
   legitimately serve several (:target/block-placement shares
   resolve-destination!), so it is judged against their union."
  (let [var-of (into {} (map (fn [v] [@v v])) (vals (ns-publics 'cn.li.combat.platform)))]
    (reduce (fn [acc [capability handler]]
              (if-let [v (var-of handler)]
                (update acc v (fnil conj #{}) capability)
                acc))
            {}
            (merge (platform/query-handlers) (platform/action-handlers)))))

(defn- destructured-keys
  "The {:keys [...]} of the handler's FIRST parameter -- nil when it takes
   the request without destructuring, which reads nothing to judge."
  [handler-var]
  (let [{:keys [ns name]} (meta handler-var)]
    (when-let [src (repl/source-fn (symbol (str (ns-name ns)) (str name)))]
      (when-let [[_ ks] (re-find #"\[\{:keys \[([^\]]*)\]" src)]
        (set (str/split (str/trim ks) #"\s+"))))))

(deftest handlers-only-read-keys-some-node-declares-test
  (is (seq capabilities-by-handler-var)
      "no handler vars resolved -- the check would pass vacuously")
  (let [offenders
        (for [[handler-var capabilities] capabilities-by-handler-var
              :let [read (destructured-keys handler-var)
                    declared (apply set/union
                                    (map #(get params-by-capability % #{}) capabilities))
                    undeclared (when read
                                 (remove #(or (engine-injected %) (declared %)) read))]
              :when (seq undeclared)]
          {:handler (symbol (str (:name (meta handler-var))))
           :capabilities (vec (sort capabilities))
           :undeclared (vec (sort undeclared))})]
    (is (= [] (vec offenders))
        (str "these handlers destructure request keys no node declares, so"
             " the value is always nil and whatever depends on it is dead: "
             (pr-str (vec offenders))))))
