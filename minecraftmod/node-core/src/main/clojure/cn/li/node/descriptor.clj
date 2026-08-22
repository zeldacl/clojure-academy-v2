(ns cn.li.node.descriptor
  "The node registry: primitive and composite/source descriptors share one
   lookup table so a caller never needs to know which kind resolved a
   component id. This is where the three-layer rule (NODE_LANGUAGE.md
   section 1) is mechanically enforced at registration time -- a :mid
   descriptor carrying :impl, or a :primitive descriptor missing :impl, is
   rejected before it can ever be stored.")

(defonce ^:private registry* (atom {:frozen? false :components {}}))

(def valid-layers #{:primitive :mid :source})

(defn- valid-field-spec? [spec]
  (and (map? spec) (contains? spec :type)))

(defn- valid-fields? [fields]
  (or (nil? fields) (and (map? fields) (every? valid-field-spec? (vals fields)))))

(defn- valid-children? [children]
  (or (nil? children)
      (and (map? children)
           (every? (fn [spec] (and (map? spec) (contains? #{:single :seq :case-map} (:kind spec))))
                   (vals children)))))

(defn freeze!
  "Close the registry to further registration. Called once at real startup,
   after every combat-core/vfx-core vocabulary namespace has loaded."
  []
  (swap! registry* assoc :frozen? true))

(defn reset-for-test!
  "Test-only: clears the registry so isolated tests don't see each other's
   registrations or trip on duplicate-id errors. Never called from a
   production init path."
  []
  (reset! registry* {:frozen? false :components {}}))

(defn descriptor [id]
  (get (:components @registry*) id))

(defn all-descriptors []
  (vals (:components @registry*)))

(defn frozen? []
  (:frozen? @registry*))

(defn- register!
  [{:keys [id revision layer inputs outputs children effects impl] :as spec}]
  (when (:frozen? @registry*)
    (throw (ex-info "node registry is frozen; register before freeze!" {:id id})))
  (when-not (and (keyword? id) (integer? revision) (contains? valid-layers layer))
    (throw (ex-info "invalid node descriptor: :id/:revision/:layer" {:id id :spec (dissoc spec :impl)})))
  (when-not (and (valid-fields? inputs) (valid-fields? outputs) (valid-children? children))
    (throw (ex-info "invalid node descriptor: :inputs/:outputs must be {key {:type t ...}}, :children must be {key {:kind :single|:seq|:case-map ...}}"
                     {:id id})))
  (when (contains? (:components @registry*) id)
    (throw (ex-info "duplicate node component" {:id id})))
  ;; The mechanical half of "mid layer must be pure EDN": the registry
  ;; itself refuses to store an :impl alongside :layer :mid, and refuses a
  ;; :primitive with no :impl. A hand-written call straight into register!
  ;; cannot bypass this the way a looser convention could.
  (when (and (= layer :mid) impl)
    (throw (ex-info "mid-layer component must not have :impl -- mid nodes are pure EDN composites, see NODE_LANGUAGE.md section 1"
                     {:id id})))
  (when (and (= layer :primitive) (nil? impl))
    (throw (ex-info "primitive component must have :impl" {:id id})))
  (when (and (= layer :source) impl)
    (throw (ex-info "source component must not have :impl -- see NODE_LANGUAGE.md section 5" {:id id})))
  (swap! registry* update :components assoc id
         (-> spec
             (update :inputs #(or % {}))
             (update :outputs #(or % {}))
             (update :children #(or % {}))
             (update :effects #(or % #{}))
             (update :reads-environment #(or % #{})))))

(defn register-primitive!
  "Register a :layer :primitive component. Only primitives may carry :impl
   (fn [filtered-inputs ctx] -> outputs-map); only primitives may reach
   outside the node language (e.g. call into mcmod). See
   cn.li.node.runtime/invoke-primitive! for how :impl is actually called --
   it never receives raw unfiltered node data, only the fields this
   descriptor's own :inputs declares, so implementation and descriptor
   cannot drift apart."
  [spec]
  (register! (assoc spec :layer :primitive)))

(defn register-composite!
  "Store a compiled :layer :mid or :layer :source descriptor. Composite
   *documents* are compiled by cn.li.node.composite before reaching here;
   this fn only stores the resulting descriptor and never accepts :impl."
  [spec]
  (register! spec))

(defn primitive-count
  "Count of currently-registered :layer :primitive descriptors. Vocabulary
   modules pin this in a test (see NODE_LANGUAGE.md section 1's mechanical
   pin requirement) so a new primitive can never be added silently."
  []
  (count (filter #(= :primitive (:layer %)) (all-descriptors))))
