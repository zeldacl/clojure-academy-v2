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
   one place. Three are deliberately absent:

     :hit-result   raycast! assoc's onto the raw hit from the platform
                   bridge, so the key set is open and a read outside the
                   assoc'd part may still be legitimate.
     :terrain-plan / :energy-target   producers not audited yet; absence
                   here means unchecked, not checked-and-clean."
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
                      :minimum-distance}})

(def ^:private known-unrepaired
  "Found by this check, not yet fixed, each needing a decision this test
   cannot make. Entries are [skill source field].

   [:current-charging :item-snapshot :supported?]
     item-held! emits no :supported?, so the read is nil. Upstream's
     equivalent is real: main's current_charging.clj computes
     `(energy/is-energy-item-supported? stack)` and uses it to decide
     whether to charge at all, which EXP tier to award, and the :good?
     state. In the V4 graph the value reaches a branch that controls
     :audio-loop-session's :destroy, so the charging audio's lifetime is
     currently decided by a constant nil.

     Not repaired here because the capability lives on the wrong side of a
     module boundary: is-energy-item-supported? is AC's
     (cn.li.ac.energy.operations), while item-held! is combat-core, which
     must not depend on AC. Exposing it needs either a neutral inventory
     relay field or an AC-provided host query alongside :cost/spend and
     :energy/target -- a design choice, not a typing one."
  #{[:current-charging :item-snapshot :supported?]})

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
