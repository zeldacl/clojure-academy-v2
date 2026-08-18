(ns cn.li.combat.components
  "Data-only component registry and startup compiler hooks.

   Component implementations are ordinary Clojure functions.  Content never
   receives the functions; it only references the keyword component ids." )

(defonce ^:private registry*
  (atom {:frozen? false :components {}}))

(defn register!
  [{:keys [id revision schema lower] :as descriptor}]
  (when (:frozen? @registry*)
    (throw (ex-info "combat component registry is frozen" {:id id})))
  (when-not (and (keyword? id)
                 (integer? revision)
                 (map? schema)
                 (ifn? lower))
    (throw (ex-info "invalid combat component descriptor"
                    {:descriptor descriptor})))
  (when (contains? (:components @registry*) id)
    (throw (ex-info "duplicate combat component" {:id id})))
  (swap! registry* update :components assoc id descriptor)
  id)

(defn freeze! []
  (swap! registry* assoc :frozen? true)
  (:components @registry*))

(defn reset-for-test! []
  (reset! registry* {:frozen? false :components {}})
  nil)

(defn descriptor [id]
  (get-in @registry* [:components id]))

(defn descriptors []
  (:components @registry*))

(defn- require-field! [node field]
  (when-not (contains? node field)
    (throw (ex-info "component field is missing"
                    {:component (:component node) :field field})))
  node)

(defn- identity-lower [compiler node]
  (update compiler :ir into [{:ir/op :component
                              :component (:component node)
                              :component-index 0
                              :data node}]))

(defn register-builtins!
  "Register the first generic component surface.  Lowering intentionally
   remains small and explicit; future components must add schema + tests." 
  []
  (doseq [[id required]
          [[:flow/phases #{:start :pulse :release :abort}]
           [:flow/sequence #{:steps}]
           [:flow/branch #{:when :then}]
           [:flow/foreach #{:items :as :body :limit}]
           [:flow/window #{:value :on-pass :on-fail}]
           [:flow/once #{:scope :storage-path :key}]
           [:flow/finish #{:outcome}]
           [:data/bind #{:to :value}]
           [:session/patch #{:entries}]
           [:owner/patch #{:entries}]
           [:txn/atomic #{:guards :reservations :body}]
           [:guard/resource #{:cost}]
           [:guard/held-item #{:source :item-ids}]
           [:target/raycast #{:origin :direction :distance}]
           [:target/entities #{:shape :projection :limit :result}]
           [:target/blocks #{:shape :projection :limit :result}]
           [:combat/beam #{:origin :direction :length :radius :result}]
           [:combat/damage #{:target :amount}]
           [:combat/damage-impact #{:target :amount}]
           [:combat/impulse #{:target :vector}]
           [:combat/status #{:target :status-id :duration-ticks}]
           [:interaction/dispatch #{:kind :target :result}]
           [:inventory/consume #{:source :count}]
           [:entity/spawn #{:entity-type :owner :position}]
           [:entity/discard #{:entity}]
           [:block/break-budget #{:blocks :energy :limit}]
           [:world/sound #{:sound-id :position}]
           [:world/lightning #{:position}]
           [:effect/vfx #{:effect-id :operation :payload}]
           [:domain/event #{:event-type :payload}]]]
    (when-not (descriptor id)
      (register! {:id id
                  :revision 1
                  :schema {:required required}
                  :lower identity-lower})))
  nil)
