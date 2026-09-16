(ns cn.li.vfx.scene-op-fields-test
  "The vfx half of combat-core's handler-request-keys check.

   The shape differs because vfx has no per-node handler: scene's :command!
   is (assoc args :kind cap), passing the whole arg map through untouched.
   The place a declared param can actually be dropped is one step later, in
   frame/legacy-op, whose `case` on :kind names the fields it forwards to
   the render plan -- anything it does not name never reaches a renderer.

   So the two invariants become:

     every param a node declares is named by that kind's arm (or a helper
     the arm calls), otherwise content sets it, the editor offers it, and
     it is silently dropped;

     every field the arm reads off the op is one some node declares,
     otherwise it is always nil.

   Both were violated when this was written. :ray-beam declared and dropped
   the :grow-ticks its own {:kind :beam} geometry consumes, so two shipped
   effects set a growth that never animated. :audio-loop declared an
   :instance-key that ->java-frame overwrites with the sample's own, and a
   :stop-on-destroy? that appeared nowhere in the repo. :camera-fov declared
   a :duration-ticks that VfxOutput has no slot for."
  (:require [clojure.repl :as repl]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cn.li.vfx.dsl-vocabulary :as vocab]
            [cn.li.vfx.frame]))

(def ^:private frame-fn-source
  (into {}
        (map (fn [[sym v]]
               [(str sym) (or (repl/source-fn
                               (symbol "cn.li.vfx.frame" (str (:name (meta v))))) "")]))
        (ns-interns 'cn.li.vfx.frame)))

(def ^:private legacy-op-source (get frame-fn-source "legacy-op"))

(def ^:private arm-by-kind
  "kind -> the text of its case arm in legacy-op, plus the source of every
   frame fn that arm calls with the op. Several arms delegate their whole
   material or geometry to a helper (beam-material op), so the fields they
   serve are named one level down."
  (when legacy-op-source
    (into {}
          (keep (fn [chunk]
                  (when-let [[_ kind] (re-find #"^:([\w?*<>=-]+)[ \n]" chunk)]
                    (let [helpers (->> (re-seq #"\(([\w?*<>=-]+) op\)" chunk)
                                       (map second)
                                       (keep frame-fn-source))]
                      [(keyword kind) (str/join "\n" (cons chunk helpers))]))))
          (rest (str/split legacy-op-source #"\n    (?=:[\w?*<>=-]+[ \n])")))))

(defn- names? [text param]
  (boolean (re-find (re-pattern (str "[:\\[ ]" (java.util.regex.Pattern/quote (name param))
                                     "[ \\]\\)\\n]"))
                    text)))

(deftest every-node-kind-has-an-arm-test
  (is (some? legacy-op-source)
      "frame/legacy-op source unavailable -- every check below would pass vacuously")
  ;; A node with no arm emits an op the frame assembler drops entirely, so
  ;; the whole node is inert rather than one of its params.
  (is (= [] (vec (sort (remove arm-by-kind (keys vocab/nodes)))))
      "these scene nodes have no arm in legacy-op, so nothing they emit renders"))

(deftest every-declared-param-is-forwarded-by-its-arm-test
  (let [offenders
        (for [[kind spec] vocab/nodes
              :let [arm (get arm-by-kind kind)]
              :when arm
              :let [dropped (remove #(names? arm %) (keys (:params spec)))]
              :when (seq dropped)]
          {:kind kind :dropped (vec (sort dropped))})]
    (is (= [] (vec offenders))
        (str "these scene params are declared -- so content sets them and the"
             " editor offers them -- but legacy-op never names them, so they"
             " reach no renderer: " (pr-str (vec offenders))))))

(deftest every-field-an-arm-reads-is-declared-test
  (let [offenders
        (for [[kind spec] vocab/nodes
              :let [arm (get arm-by-kind kind)]
              :when arm
              :let [declared (set (map name (keys (:params spec))))
                    ;; (:foo op) and (select-keys op [...]) are the two
                    ;; spellings the arms use.
                    read (set/union
                          (set (map second (re-seq #"\(:([\w?*<>=-]+) op\)" arm)))
                          (set (mapcat #(map (fn [s] (subs s 1))
                                             (re-seq #":[\w?*<>=-]+" (second %)))
                                       (re-seq #"select-keys op \[([^\]]*)\]" arm))))
                    undeclared (remove declared read)]
              :when (seq undeclared)]
          {:kind kind :undeclared (vec (sort undeclared))})]
    (is (= [] (vec offenders))
        (str "these arms read op fields no node declares, so the value is"
             " always nil: " (pr-str (vec offenders))))))
