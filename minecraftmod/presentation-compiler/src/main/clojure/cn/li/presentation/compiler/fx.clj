(ns cn.li.presentation.compiler.fx
  "Strict data compiler for *.fx.edn. Effect execution remains in Clojure
   Runtime; this namespace only validates and freezes the template contract."
  (:require [clojure.edn :as edn])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def hosts #{:hud :world-ui :vfx :first-person :camera :post-process})
(def primitives #{:particle :billboard :ribbon :beam :arc :mesh :camera :post-process})
(def stages #{:world-before-translucent :world-after-translucent :first-person
              :hud-underlay :hud :hud-overlay :screen :post-process})

(defn- fail [path message]
  (throw (ex-info (str path ": " message) {:path path})))

(defn- content-hash [source]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (pr-str source) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(defn- validate-timeline! [timeline]
  (when-not (vector? timeline)
    (fail ".timeline" "must be a vector"))
  (doseq [[property frames] (group-by :property timeline)]
    (when-not (keyword? property)
      (fail ".timeline.property" "must be a keyword"))
    (when-not (every? map? frames)
      (fail ".timeline" "each keyframe must be a map"))
    (let [times (mapv :at frames)]
      (when-not (every? number? times)
        (fail ".timeline.at" "must be numeric"))
      (when-not (apply <= times)
        (fail ".timeline" "keyframes must be ordered by :at"))
      (doseq [frame frames]
        (when-not (contains? frame :value)
          (fail ".timeline.value" "is required"))
        (when-let [easing (:easing frame)]
          (when-not (#{:linear :ease-in :ease-out :ease-in-out} easing)
            (fail ".timeline.easing" (str "unsupported easing " easing))))))))

(defn compile-template [source]
  (let [id (:id source)
        host (:host source)
        primitive (:primitive source)
        stage (:stage source)
        owner (:owner source)
        timeline (vec (or (:timeline source) []))
        resources (vec (or (:resources source) []))]
    (when-not (keyword? id) (fail ".id" "must be a keyword"))
    (when-not (hosts host) (fail ".host" (str "unsupported host " host)))
    (when-not (primitives primitive) (fail ".primitive" (str "unsupported primitive " primitive)))
    (when-not (stages stage) (fail ".stage" (str "unsupported render stage " stage)))
    (when-not (keyword? owner) (fail ".owner" "must be a stable owner key"))
    (validate-timeline! timeline)
    (when-not (every? string? resources)
      (fail ".resources" "must contain strings"))
    (when (contains? source :duration-ms)
      (when-not (and (number? (:duration-ms source)) (pos? (:duration-ms source)))
        (fail ".duration-ms" "must be a positive number")))
    {:id id
     :host host
     :primitive primitive
     :stage stage
     :owner owner
     :duration-ms (long (or (:duration-ms source)
                            (if (seq timeline) 1000 1000)))
     :timeline timeline
     :resources resources
     :dependencies resources
     :content-hash (content-hash source)}))

(defn compile-edn [text]
  (compile-template (edn/read-string text)))
