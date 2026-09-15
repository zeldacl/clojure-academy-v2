(ns cn.li.ac.ability.service.nil-field-read-sites-test
  "Every field a skill reads must be a key its producer actually emits.

   Two violations were found by the gap-C measurements, and both were live
   defects rather than style problems:

     (:position x) on an :entity-snapshot -- 7 sites. entity-snapshot! had
       no :position while project-entity, behind the neighbouring
       :target/entities, did. Fixed host-side.
     (:invulnerable-time x) on a :target/entities element -- 1 site.
       Every mc-* world-effects-core supplies it and main's light_shield.clj
       gates on it, but project-entity dropped it. The read reached
       :math/lte, whose :double parameter makes effect-emit throw on nil, so
       light-shield's touch path raised whenever an entity entered the cone.
       Fixed host-side.

   Both were invisible until the vocabulary's :returns were typed: while
   every query returned :any, a field read could not be attributed to a
   record at all. So this generalises the check rather than listing the two:
   for each source type whose producer emits a KNOWN, CLOSED key set,
   assert content never reads outside it.

   A type is listed below only when its producer builds the whole map in
   one place. One is deliberately absent and cannot be added:

     :hit-result   raycast! assoc's its normalised keys ONTO the raw hit the
                   loader bridge returned, and that map's shape is
                   loader-specific by design -- mcbase's raycast-normalize
                   exists precisely because bridges disagree (:hit-x vs :x,
                   string vs keyword :face). There is no single key set to
                   close it against, so a read here cannot be judged from
                   the neutral side. Absence means UNCHECKED, not
                   checked-and-clean."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.skills-catalog-v4 :as skills-catalog]
            [cn.li.node.api :as node-api]
            [cn.li.node.types :as types]))

(def ^:private compile-ns (do (require 'cn.li.node.compile) 'cn.li.node.compile))
(def ^:private compile-field-access-var (ns-resolve compile-ns 'compile-field-access))

(def ^:private ^:dynamic *skill* nil)

(def ^:private emitted-keys
  "source type -> exactly the keys its producer puts on the map.
   Read off the producing function, never inferred from a field name."
  {;; cn.li.combat.platform/entity-snapshot!
   :entity-snapshot #{:id :type :entity-type :x :y :z :position
                      :eye-height :eye-position :alive?}
   ;; platform/item-held!
   :item-snapshot #{:present? :placeable? :item-id :block-id :count :source}
   ;; platform/owner-snapshot!
   :owner-snapshot #{:position :eye-position :look :velocity :on-ground? :can-fly?}
   ;; platform/break!
   :break-result #{:status :block-id :position}
   ;; platform/beam-trace!
   :beam-result #{:start :end :visual-end :entities :blocks :reflection-policy}
   ;; A role tag with two producers (see entity-ref-origin-test): the union
   ;; is what a read may legitimately name, since the compiler cannot tell
   ;; which one a given value came from.
   ;;   platform/project-entity  +  platform/spawn-entity!
   :entity-ref #{:id :type :position :width :height :eye-height :age-ms
                 :motion-progress :owner-id :velocity :difficulty
                 :explosion-power :item? :projectile? :arrow? :behavior-hit?
                 :living? :mob? :multipart? :tags :invulnerable-time
                 :status :entity-id}
   ;; Also two producers: targeting/directional-destination and platform's
   ;; resolve-destination. Union, same reasoning.
   :destination #{:position :from :distance :hit? :valid? :direction
                  :world-id :hit-position :drop-position :line-position
                  :place-position :hit-block-position :face :target-hit?
                  :can-place? :minimum-distance}
   ;; platform/resolve-destination under its :block-placement query kind.
   :block-placement #{:world-id :position :hit-position :drop-position
                      :line-position :place-position :hit-block-position
                      :face :target-hit? :distance :hit? :can-place? :valid?
                      :minimum-distance}
   ;; cn.li.ac.ability.service.combat-runtime/energy-target-result, an
   ;; AC-registered host query rather than a combat-core one.
   :energy-target #{:chargeable? :block-pos :block-bounds}
   ;; combat-runtime/activation-context, the ?context/resources value.
   ;; :max-cp is deliberately NOT here: a different host path (a state
   ;; projection at combat-runtime:242) emits a three-key pool, but the
   ;; activation context emits two, and this is the one content reads.
   ;; Adding :max-cp to make both producers fit would turn a read that is
   ;; nil today into one this check calls fine.
   :resource-pool #{:cp :overload}
   ;; platform/terrain-propagate!: the seed state map plus the
   ;; :mastery-breaks it assoc's on at the end.
   :terrain-plan #{:affected-blocks :transforms :broken-blocks :entities
                   :mastery-breaks}})

(def ^:private known-unrepaired
  "Found by this check, not yet fixed, each needing a decision this test
   cannot make. Entries are [skill source field].

   EMPTY. The one entry it held -- current-charging reading (:supported? x)
   off an :item-snapshot that item-held! never puts it on -- is fixed: AC
   now registers an :energy/held-item-supported? query, so the skill asks
   the module that owns the fact instead of a record that never carried it."
  #{})

(deftest every-field-read-names-a-key-its-producer-emits
  (let [sites (atom [])
        orig @compile-field-access-var
        compile-doc-orig node-api/compile-v4-skill-document!]
    (with-redefs-fn
      {#'node-api/compile-v4-skill-document!
       (fn [value opts mode]
         (binding [*skill* (:id value)]
           (compile-doc-orig value opts mode)))

       compile-field-access-var
       (fn [env locals block-id depth form k sub-form]
         (let [result (orig env locals block-id depth form k sub-form)
               instrs (some-> (get @(:block-registry env) (:block-id result)) deref)
               get-instr (last (filter #(= :get (:op %)) instrs))
               src-type (types/canonical-type (get @(:reg-types env) (:src get-instr)))
               known (get emitted-keys src-type)]
           (when (and get-instr known
                      (not (contains? known k))
                      (not (known-unrepaired [*skill* src-type k])))
             (swap! sites conj {:skill *skill* :nid (:nid get-instr)
                                :source src-type :field k}))
           result))}
      #(skills-catalog/assemble {:mode :collect}))

    (when (seq @sites)
      (println)
      (println "=== field reads naming a key the producer does not emit ===")
      (doseq [[[source field] group] (sort-by key (group-by (juxt :source :field) @sites))]
        (println (format "(%s %s)  -- %d site(s)" (pr-str field) (pr-str source) (count group)))
        (doseq [{:keys [skill nid]} (sort-by (juxt :skill :nid) group)]
          (println (format "    %-24s %s" (pr-str skill) (pr-str nid)))))
      (println))

    (is (= [] (vec @sites))
        (str "a skill reads a field its producer never emits, so the read is nil"
             " -- and nil reaching a numeric parameter throws rather than"
             " defaulting: " (pr-str @sites)))))
