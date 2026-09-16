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
            [cn.li.combat.beam-settlement :as beam]
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
    (-> (reduce (fn [acc [capability handler]]
                  (if-let [v (var-of handler)]
                    (update acc v (fnil conj #{}) capability)
                    acc))
                {}
                (merge (platform/query-handlers) (platform/action-handlers)))
        ;; install! registers this one outside both maps, wrapping the
        ;; function below in a closure over an injected scheduler, so there
        ;; is no registered value to resolve back to a var. Named here so
        ;; the checks cover it rather than skipping it silently -- the
        ;; capability is real and content calls it.
        (assoc #'beam/schedule-action! #{:projectile/schedule-beam}))))

(defn- destructured-keys
  "The first {:keys [...]} in the handler's arglist -- nil when it takes the
   request without destructuring, which reads nothing to judge.

   Not anchored to the FIRST parameter: schedule-action! takes its injected
   scheduler first and the request second, and anchoring skipped it
   entirely, which is the same silent pass this check exists to prevent."
  [handler-var]
  (let [{:keys [ns name]} (meta handler-var)]
    (when-let [src (repl/source-fn (symbol (str (ns-name ns)) (str name)))]
      (when-let [[_ ks] (re-find #"\{:keys \[([^\]]*)\]" src)]
        (set (str/split (str/trim ks) #"\s+"))))))

(def ^:private platform-fn-names
  (into #{} (map str) (keys (ns-interns 'cn.li.combat.platform))))

(def ^:private forwards-request-onward
  "Handlers that hand the WHOLE request to an injected callback, so the
   params they serve are consumed after a round trip no static scope can
   follow. schedule-action! passes its payload to an instance-local
   scheduler which later calls back into this same module to settle --
   combat-core owns settlement by design, so the namespace is the honest
   bound. Naming them costs the per-handler precision that caught
   :projectile/redirect's :difficulty, which is why this is a list and not
   the default."
  #{"schedule-action!"})

(defn- effective-source
  "A handler's source plus that of any platform fn it calls.

   Several handlers are thin arity/guard wrappers that forward the whole
   request to an inner fn -- raycast! -> basic-raycast is the shape -- so
   the params they serve are named one level down. One level is enough for
   every handler here and keeps the check from chasing the whole module."
  [handler-var]
  (let [{:keys [ns name]} (meta handler-var)
        own (repl/source-fn (symbol (str (ns-name ns)) (str name)))]
    (when own
      (->> (if (forwards-request-onward (str name))
             (keys (ns-interns ns))
             (re-seq #"[a-z][\w!?*<>=-]*" own))
           (map str)
           (filter (if (forwards-request-onward (str name))
                     (constantly true)
                     platform-fn-names))
           (remove #{(str name)})
           (keep (fn [n] (repl/source-fn (symbol (str (ns-name ns)) n))))
           (cons own)
           (str/join "\n")))))

(deftest every-declared-param-is-mentioned-by-its-handler-test
  ;; The mirror of the check below, and the half that catches the opposite
  ;; failure: content sets a param the host ignores. That is how
  ;; :target/directional-destination-query's :policy was found -- declared,
  ;; passed the whole way down, and never destructured by the callee.
  ;;
  ;; Deliberately weak: it asks whether the param NAME appears anywhere in
  ;; the handler's source, not whether it is destructured, because handlers
  ;; legitimately read via (:key request) or forward the whole request on.
  ;; A name that appears nowhere cannot be being read by any spelling.
  (let [offenders
        (for [[handler-var capabilities] capabilities-by-handler-var
              :let [src (effective-source handler-var)]
              :when src
              :let [declared (apply set/union
                                    (map #(get params-by-capability % #{}) capabilities))
                    unread (remove #(str/includes? src %) declared)]
              :when (seq unread)]
          {:handler (symbol (str (:name (meta handler-var))))
           :capabilities (vec (sort capabilities))
           :never-mentioned (vec (sort unread))})]
    (is (= [] (vec offenders))
        (str "these params are declared on a node -- so content can set them"
             " and the editor offers them -- but their handler never names"
             " them, so the value is silently dropped: "
             (pr-str (vec offenders))))))

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
