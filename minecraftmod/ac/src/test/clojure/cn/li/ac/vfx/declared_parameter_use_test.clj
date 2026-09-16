(ns cn.li.ac.vfx.declared-parameter-use-test
  "A VFX effect's declared :parameters must reach a consumer.

   The last direction of the handler-key sweep, turned inward: the previous
   checks compared a node's declaration against its host handler, and this
   one compares an EFFECT's declaration against everything that could read
   it. A parameter declared here is one skills must supply and the editor
   offers, so one that nothing reads is a knob wired to nothing.

   Three consumers exist, and mistaking any of them for absence is the
   trap this check had to be built around -- a naive 'does the program
   read ?name' version produced 55 findings and every one I checked by
   hand was a false positive of one of these kinds:

     the scene PROGRAM, via ?name -- the common case;

     the vfx RUNTIME, for lifecycle and identity (:life-ticks, :owner,
     :seed, ...), which the program never names;

     a client SIDE CHANNEL, for effects whose :render graph is
     legitimately empty -- see empty-render-graph-audit-test, which
     classifies those, and client-vfx-v2/update-presentation-sidechannels!,
     which reads them at signal-dispatch time.

   So the runtime and side-channel readers are listed by name below rather
   than inferred, each one verified against its reader. That makes every
   exemption a written claim, and leaves anything unlisted genuinely
   unread -- which is how :bounds-radius surfaced: declared by four
   effects, passed at thirteen skill sites, and named in no source file on
   this branch or on main."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private runtime-read
  "Read by cn.li.vfx.runtime (lifecycle, identity, culling) rather than by
   the scene program, so a program that never names them is correct."
  #{:life-ticks :duration-ticks :owner :seed :style})

(def ^:private side-channel-read
  "effect-id -> the params client-vfx-v2/update-presentation-sidechannels!
   reads for it at signal-dispatch time. :local-walk-speed is read off ANY
   signal, so it is allowed everywhere."
  {:screen-flash-session #{:alpha :duration-ticks}
   :camera-fov-session #{:offset}})

(def ^:private universal-side-channel #{:local-walk-speed})

(def ^:private unverified
  "Declared parameters this check cannot yet place. NOT an exemption list
   -- each is a candidate the sweep found and nobody has traced to a
   reader or proven dead. Shrinking this is real work; growing it silently
   is what the assertion below prevents.

   Kept as data rather than deleted so the count cannot drift: a NEW
   unread parameter fails the test even while these remain."
  '{:arc-channel-session #{:charge-ticks :visual-max-ticks}
    :arc-ring-fade-audio #{:arc-count-limit :arc-life-ticks :arc-radius
                           :arc-spacing :end :start}
    :arc-ring-session #{:arc-count-limit :arc-life-ticks :arc-radius
                        :arc-spacing :sound-id :sound-pitch :sound-position
                        :sound-volume}
    :beam-arc-fade #{:arc-count-limit :arc-radius :arc-spacing}
    :block-progress-session #{:pulse-period}
    :block-scan-transient #{:advanced? :filter :max-range :max-results
                            :range :rescan-interval :tier-colors}
    :blood-retrograde-impact #{:look-dir :splash-count :splash-frame-count
                               :splash-frame-duration-ms :splash-life-ticks
                               :splash-texture-pattern :spray-duplicates
                               :surface-hits :target-height :target-width}
    :directed-blastwave-charge #{:max-charge-ticks}
    :particle-burst-trail-transient #{:end :fade-in :fade-out :radius
                                      :spacing :velocity}
    :target-mark-session #{:ttl-ticks}
    :terrain-shockwave-transient #{:direction :surface-hits}})

(defn- vfx-docs []
  (let [root (io/file (io/resource "ac/vfx-v4"))]
    (when-not root (throw (ex-info "ac/vfx-v4 not on the classpath" {})))
    (for [^java.io.File f (.listFiles root)
          :when (.endsWith (.getName f) ".edn")
          :let [text (slurp f)]]
      [(edn/read-string text) text])))

(deftest every-declared-parameter-has-a-reader-test
  (let [offenders
        (for [[doc text] (vfx-docs)
              :let [effect-id (:id doc)
                    body (subs text (or (str/index-of text ":phases") 0))
                    allowed (set/union runtime-read
                                       universal-side-channel
                                       (get side-channel-read effect-id #{})
                                       (get unverified effect-id #{}))
                    unread (remove (fn [param]
                                     (or (allowed param)
                                         (str/includes? body (str "?" (name param)))))
                                   (keys (:parameters doc)))]
              :when (seq unread)]
          {:effect effect-id :unread (vec (sort unread))})]
    (is (= [] (vec offenders))
        (str "these effect parameters are declared -- so skills must supply"
             " them and the editor offers them -- but no program, runtime or"
             " side-channel reader names them: " (pr-str (vec offenders))))))

(deftest unverified-list-does-not-name-a-parameter-that-is-gone-test
  ;; The list above is only honest while every entry still exists. A
  ;; parameter deleted from an effect must leave it, or the list quietly
  ;; becomes an exemption for a name nothing declares.
  (let [declared (into {} (map (fn [[doc _]] [(:id doc) (set (keys (:parameters doc)))]))
                       (vfx-docs))
        stale (for [[effect-id params] unverified
                    :let [gone (remove (get declared effect-id #{}) params)]
                    :when (seq gone)]
                {:effect effect-id :no-longer-declared (vec (sort gone))})]
    (is (= [] (vec stale))
        (str "the unverified list names parameters the effect no longer"
             " declares: " (pr-str (vec stale))))))
