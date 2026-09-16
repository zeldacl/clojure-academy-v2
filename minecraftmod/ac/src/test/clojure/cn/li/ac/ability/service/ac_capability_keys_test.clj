(ns cn.li.ac.ability.service.ac-capability-keys-test
  "The AC half of combat-core's handler-request-keys check.

   That check covers the capabilities combat-core registers from its own
   query-handlers/action-handlers maps. It cannot see AC's, for two
   reasons: combat-core must not depend on ac, and AC registers its
   handlers as inline (fn [{:keys [...]}] ...) forms inside
   install-ac-host-capabilities! rather than as named vars, so there is no
   var to resolve.

   Same two invariants, then, against the same vocabulary:

     a handler must not read a request key no node declares -- the value is
     always nil and whatever depends on it is dead;

     a param a node declares must be mentioned by its handler -- otherwise
     content sets it, the editor offers it, and the host drops it.

   The declared side comes from combat-core's vocabulary directly rather
   than being re-parsed. An earlier throwaway version of this analysis did
   re-parse it and mis-attributed a capability by matching across a node
   boundary, which is exactly the kind of error a test must not be able to
   make."
  (:require [clojure.repl :as repl]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cn.li.combat.dsl-vocabulary :as vocab]
            [cn.li.combat.host-parity :as parity]
            [cn.li.ac.ability.service.combat-runtime]))

(def ^:private engine-injected
  "engine-v2's :query!/:command! assoc these onto every request, so no node
   declares them and every handler may read them."
  #{"owner" "world-id" "ability-id"})

(def ^:private params-by-capability
  (reduce (fn [acc [node-id spec]]
            (update acc (or (:capability spec) node-id)
                    (fnil into #{}) (map name (keys (:params spec)))))
          {}
          vocab/nodes))

(def ^:private install-source
  (repl/source-fn
   'cn.li.ac.ability.service.combat-runtime/install-ac-host-capabilities!))

(def ^:private registrations
  "[[capability #{destructured-key ...} tail-source] ...] read off the one
   function that installs them all."
  (when install-source
    (for [m (re-seq #"register-(?:query|action)!\s*\n?\s*(:[\w/.!?*+<>=-]+)\s*\n?\s*\(fn \[\{:keys \[([^\]]*)\]"
                    install-source)]
      (let [[whole capability keys-str] m
            at (str/index-of install-source whole)]
        [(keyword (subs capability 1))
         (set (str/split (str/trim keys-str) #"\s+"))
         (subs install-source (+ at (count whole)))]))))

(deftest every-ac-capability-was-found-test
  (is (some? install-source)
      "install-ac-host-capabilities! source unavailable -- every check below
       would pass vacuously")
  ;; Pinned so a capability moving out of this function, or a registration
  ;; spelled some new way the parser cannot see, fails instead of silently
  ;; shrinking the checked set.
  (is (= (set/union parity/ac-query-capabilities
                    (disj parity/ac-action-capabilities
                          ;; combat-core's install! registers this one, with
                          ;; its instance-local scheduler injected -- it is
                          ;; listed in ac-action-capabilities only so the
                          ;; parity gate treats it as host-provided.
                          :projectile/schedule-beam))
         (set (map first registrations)))
      "the set of AC-registered capabilities changed"))

(deftest ac-handlers-only-read-keys-some-node-declares-test
  (let [offenders
        (for [[capability read _] registrations
              :let [declared (get params-by-capability capability #{})
                    undeclared (remove #(or (engine-injected %) (declared %)) read)]
              :when (seq undeclared)]
          {:capability capability :undeclared (vec (sort undeclared))})]
    (is (= [] (vec offenders))
        (str "these AC handlers destructure request keys no node declares,"
             " so the value is always nil: " (pr-str (vec offenders))))))

(deftest every-ac-declared-param-is-mentioned-by-its-handler-test
  ;; Deliberately weak, like its combat-core counterpart: it asks whether
  ;; the param NAME appears in the handler body, not whether it is
  ;; destructured, because a handler may read via (:key request). Scans a
  ;; bounded window after the destructuring rather than the whole file, so
  ;; a name used by an unrelated later handler cannot vouch for this one.
  (let [offenders
        (for [[capability _ tail] registrations
              :let [body (subs tail 0 (min 2500 (count tail)))
                    declared (get params-by-capability capability #{})
                    unread (remove #(str/includes? body %) declared)]
              :when (seq unread)]
          {:capability capability :never-mentioned (vec (sort unread))})]
    (is (= [] (vec offenders))
        (str "these params are declared on a node but their AC handler never"
             " names them, so content sets them and the host drops them: "
             (pr-str (vec offenders))))))
