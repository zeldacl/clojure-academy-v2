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
   this branch or on main.

   The parameters that remain unread are classified, not pending: see
   incomplete-conversions below."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private runtime-read
  "Read by cn.li.vfx.runtime rather than by the scene program, so a program
   that never names them is correct. Exactly the four keys
   transient-duration and the fade path take off an instance's :user map --
   read off those call sites, not inferred from the name appearing
   somewhere in the file. An earlier version of this set listed :owner,
   :seed and :style on exactly that mistake; none of the three is read
   there, and listing them turned real findings into silence."
  #{:duration-ticks :life-ticks :ttl-ticks :fade-ticks})

(def ^:private side-channel-read
  "effect-id -> the params client-vfx-v2/update-presentation-sidechannels!
   reads for it at signal-dispatch time. :local-walk-speed is read off ANY
   signal, so it is allowed everywhere."
  {:screen-flash-session #{:alpha :duration-ticks}
   :camera-fov-session #{:offset}})

(def ^:private universal-side-channel #{:local-walk-speed})

(def ^:private incomplete-conversions
  "Parameters declared for a visual the v4 effect body does not implement.

   NOT unknowns, and not exemptions. Each was traced: the parameter names
   a feature of that skill's pre-V4 fx implementation, and the v4 :render
   phase emits a fraction of it -- typically one generic emitter or ring
   with its rates hardcoded. Spot-checked against the originals rather
   than assumed: mine-detect really does carry a rescan interval and a
   five-tier colour table, and blood-retrograde really does run a
   splash/spray system with per-splash TTLs.

   Three are already gone. :particle-burst-trail-transient was authored for
   the :particle-trail node -- its twelve parameters are that node's
   parameters, one for one -- and had been wired to :emitter, which drops
   :end and everything about the trail; it now calls the node it was
   written for. :target-mark-session needed nothing: :ttl-ticks is read by
   the runtime's transient-duration, which an earlier version of the
   exemption list simply failed to name.

   :directed-blastwave-charge is the third, and is what finishing one by
   hand looks like: its pre-V4 charge-ops gives the
   exact formula (progress = ticks / max, radius = 0.1 + 0.16 * progress,
   a 0.22-rate sine pulse, alpha 220 or 170 by :punched?), so all four of
   its parameters now drive the ring. It also shipped passing :punched? --
   a BOOLEAN -- as the ring's :color, which is why :color is now :rgba.

   Finishing the rest is content work against those references, and some
   may need scene vocabulary that does not exist yet (the emitter has no
   frame animation or duplicate control for blood-retrograde's sprays).
   That is a known cost, not an open question."
  '{:arc-ring-fade-audio #{:end :start}
    :block-scan-transient #{:advanced? :filter :max-range :max-results
                            :range :rescan-interval :tier-colors}
    :blood-retrograde-impact #{:look-dir :splash-count :splash-frame-count
                               :splash-frame-duration-ms :splash-life-ticks
                               :splash-texture-pattern :spray-duplicates
                               :surface-hits :target-height :target-width}
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
                                       (get incomplete-conversions effect-id #{}))
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

(deftest incomplete-conversion-list-does-not-name-a-parameter-that-is-gone-test
  ;; The list above is only honest while every entry still exists. A
  ;; parameter deleted from an effect -- or an effect finished, as
  ;; :directed-blastwave-charge was -- must leave it, or the list quietly
  ;; becomes an exemption for a name nothing declares.
  (let [declared (into {} (map (fn [[doc _]] [(:id doc) (set (keys (:parameters doc)))]))
                       (vfx-docs))
        stale (for [[effect-id params] incomplete-conversions
                    :let [gone (remove (get declared effect-id #{}) params)]
                    :when (seq gone)]
                {:effect effect-id :no-longer-declared (vec (sort gone))})]
    (is (= [] (vec stale))
        (str "the incomplete-conversion list names parameters the effect no longer"
             " declares: " (pr-str (vec stale))))))
