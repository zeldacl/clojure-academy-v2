(ns cn.li.presentation.core.composition
  "Pure runtime composition editing for the PUI4 Presentation core.

   A BaseView is immutable normalized data.  A Composition is an ephemeral
   session projection over that BaseView; no edit mutates the source view or
   survives unmount/reset.  Nodes are already primitive-safe at this layer,
   while blueprint descriptors only provide catalog metadata and templates."
  (:require [clojure.string :as string]))

(def default-history-limit 100)

(defn- value-id [value]
  (cond
    (keyword? value) (if-let [ns (namespace value)]
                       (str ns "/" (name value))
                       (name value))
    :else (str value)))

(defn- key-id [value]
  (let [value (value-id value)]
    (when (string/blank? value)
      (throw (ex-info "UI key is blank" {:value value})))
    value))

(defn- slot-id [value]
  (let [value (value-id value)]
    (if (string/blank? value) "" value)))

(defn- normalize-node [node]
  (let [children (mapv normalize-node (or (:children node) []))
        slots (into {}
                    (map (fn [[slot entries]]
                           [(slot-id slot) (mapv normalize-node (or entries []))]))
                    (or (:slots node) {}))]
    (-> node
        (assoc :key (key-id (:key node))
               :children children
               :slots slots)
        (update :blueprint #(when % (value-id %))))))

(defn- child-entries [node]
  (concat
   (map-indexed (fn [index child] ["" index child]) (:children node))
   (mapcat (fn [[slot entries]]
            (map-indexed (fn [index child] [slot index child]) entries))
           (:slots node))))

(defn- index-node [acc node parent-key parent-slot parent-index]
  (let [key (:key node)]
    (when (contains? (:nodes acc) key)
      (throw (ex-info "duplicate UI key" {:key key})))
    (let [acc (-> acc
                  (assoc-in [:nodes key] node)
                  (cond-> parent-key
                    (assoc-in [:parents key]
                              {:parent-key parent-key
                               :slot parent-slot
                               :index parent-index})))]
      (reduce (fn [result [slot index child]]
                (index-node result child key slot index))
              acc
              (child-entries node)))))

(defn- indexes [root]
  (index-node {:nodes {} :parents {}} root nil nil nil))

(defn- normalize-blueprints [blueprints]
  (into {}
        (map (fn [[id descriptor]]
               (let [id (value-id id)]
                 [id (-> descriptor
                          (assoc :id id)
                          (update :template #(when % (normalize-node %)))
                          (update :props-schema #(or % {}))
                          (update :slot-schema #(or % {})))])))
        (or blueprints {})))

(defn base-composition
  "Create an ephemeral composition session from immutable normalized BaseView data.
   The returned value is safe to retain as the only source for a mount." 
  ([base-view] (base-composition base-view {}))
  ([base-view {:keys [history-limit] :or {history-limit default-history-limit}}]
   (let [root (normalize-node (:root base-view))
         blueprints (normalize-blueprints (:blueprints base-view))
         boundaries (into {}
                          (map (fn [[key descriptor]]
                                 [(key-id key) descriptor]))
                          (or (:boundaries base-view) {}))]
     (indexes root)
     {:base-view (assoc base-view
                        :root root
                        :blueprints blueprints
                        :boundaries boundaries)
      :root root
      :blueprints blueprints
      :boundaries boundaries
      :history []
      :redo []
      :history-limit (max 0 (int history-limit))
      :composition-revision 0})))

(defn composition-view [composition]
  {:root (:root composition)
   :blueprints (:blueprints composition)
   :boundaries (:boundaries composition)
   :composition-revision (:composition-revision composition)})

(defn- node-index [composition]
  (indexes (:root composition)))

(defn- descriptor [composition key node]
  (or (get (:boundaries composition) key)
      (get (:boundaries composition) (:blueprint node))
      (get (:blueprints composition) (:blueprint node))
      {}))

(defn- policy-allows? [policy operation slot]
  (case policy
    :sealed false
    :children (contains? #{:insert :remove :move} operation)
    :slot (and (contains? #{:insert :remove :move} operation)
               (not (string/blank? slot)))
    :replace-only (= operation :replace)
    ;; Unspecified boundaries are intentionally replace-only: a component
    ;; cannot gain arbitrary children without declaring that edit surface.
    (= operation :replace)))

(defn- boundary-allows? [composition key operation slot]
  (let [node (get (:nodes (node-index composition)) key)
        descriptor (descriptor composition key node)
        policy (or (:edit-policy descriptor) (:edit-policy node) :sealed)
        allowed-slots (:allowed-slots descriptor)]
    (and node
         (policy-allows? policy operation slot)
         (or (nil? allowed-slots)
             (contains? (set (map slot-id allowed-slots)) (slot-id slot))))))

(defn- container-children [node slot]
  (if (= "" (slot-id slot)) (:children node) (get-in node [:slots (slot-id slot)] [])))

(defn- with-container-children [node slot children]
  (if (= "" (slot-id slot))
    (assoc node :children (vec children))
    (assoc-in node [:slots (slot-id slot)] (vec children))))

(defn- update-node [root target-key f]
  (if (= (:key root) target-key)
    (f root)
    (let [children (:children root)
          updated-children (mapv #(update-node % target-key f) children)
          slots (:slots root)
          updated-slots (into {}
                             (map (fn [[slot entries]]
                                    [slot (mapv #(update-node % target-key f) entries)]))
                             slots)]
      (assoc root :children updated-children :slots updated-slots))))

(defn- find-node [composition key]
  (get (:nodes (node-index composition)) (key-id key)))

(defn- find-parent [composition key]
  (get (:parents (node-index composition)) (key-id key)))

(defn- put-child [root parent-key slot index child]
  (update-node root parent-key
               (fn [parent]
                 (let [current (vec (container-children parent slot))
                       index (if (= -1 index) (count current) index)]
                   (when (or (neg? index) (> index (count current)))
                     (throw (ex-info "child index out of range" {:index index :size (count current)})))
                   (with-container-children parent slot
                     (into (conj (subvec current 0 index) child)
                           (subvec current index)))))))

(defn- remove-child [root parent-key slot index]
  (let [removed (atom nil)
        root (update-node root parent-key
                          (fn [parent]
                            (let [current (vec (container-children parent slot))]
                              (when (or (neg? index) (>= index (count current)))
                                (throw (ex-info "child index out of range" {:index index :size (count current)})))
                              (reset! removed (nth current index))
                              (with-container-children parent slot
                                (into (subvec current 0 index)
                                      (subvec current (inc index)))))))]
    [root @removed]))

(defn- instantiate [composition blueprint key props slots]
  (let [descriptor (get (:blueprints composition) blueprint)]
    (when-not descriptor
      (throw (ex-info "unknown UI blueprint" {:blueprint blueprint})))
    (let [template (or (:template descriptor) {})]
      (-> template
          (assoc :key key :blueprint blueprint)
          (update :props #(merge (or % {}) (or props {})))
          (update :slots #(merge (or % {}) (or slots {})))
          normalize-node))))

(defn- compatible-map [schema old supplied]
  (let [allowed (set (keys (or schema {})))]
    (merge (select-keys (or old {}) allowed)
           (select-keys (or supplied {}) allowed))))

(defn- compatible-slots [schema old supplied]
  (let [allowed (set (keys (or schema {})))
        old (if (seq allowed) (select-keys (or old {}) allowed) {})
        supplied (if (seq allowed) (select-keys (or supplied {}) allowed) {})]
    (merge old supplied)))

(defn- replace-node [composition target-key blueprint props slots]
  (let [old (find-node composition target-key)
        descriptor (get (:blueprints composition) blueprint)
        node (instantiate composition blueprint target-key
                          (compatible-map (:props-schema descriptor) (:props old) props)
                          (compatible-slots (:slot-schema descriptor) (:slots old) slots))]
    [(assoc composition :root (update-node (:root composition) target-key (constantly node))) old node]))

(defn- descendant? [composition ancestor-key possible-child-key]
  (loop [key (key-id possible-child-key)]
    (if (= key (key-id ancestor-key))
      true
      (when-let [parent (find-parent composition key)]
        (recur (:parent-key parent))))))

(defn- command-op [command]
  (keyword (or (:op command) (:operation command))))

(defn- apply-internal [composition command]
  (let [operation (command-op command)]
    (case operation
      :insert
      (let [parent-key (key-id (:target-key command))
            slot (slot-id (:slot command))
            key (key-id (:key command))
            blueprint (value-id (:blueprint command))
            index (int (or (:index command) -1))]
        (when (find-node composition key)
          (throw (ex-info "UI key already exists" {:key key})))
        (when-not (find-node composition parent-key)
          (throw (ex-info "insert parent not found" {:key parent-key})))
        (when-not (boundary-allows? composition parent-key :insert slot)
          (throw (ex-info "insert rejected by component boundary" {:key parent-key :slot slot})))
        (let [node (instantiate composition blueprint key (:props command) (:slots command))
              root (put-child (:root composition) parent-key slot index node)
              actual-index (if (= -1 index)
                             (count (container-children (find-node composition parent-key) slot))
                             index)]
          {:composition (assoc composition :root root)
           :inverse {:op :remove :target-key key}
           :affected #{parent-key key}
           :details {:op :insert :key key :parent-key parent-key :index actual-index}}))

      :remove
      (let [target-key (key-id (:target-key command))
            target (find-node composition target-key)
            parent (find-parent composition target-key)]
        (when-not target (throw (ex-info "remove target not found" {:key target-key})))
        (when-not parent (throw (ex-info "root cannot be removed" {:key target-key})))
        (when-not (boundary-allows? composition (:parent-key parent) :remove (:slot parent))
          (throw (ex-info "remove rejected by component boundary" {:key target-key})))
        (let [[root removed] (remove-child (:root composition) (:parent-key parent)
                                            (:slot parent) (:index parent))]
          {:composition (assoc composition :root root)
           :inverse {:op :restore :parent-key (:parent-key parent)
                     :slot (:slot parent) :index (:index parent) :node removed}
           :affected #{target-key (:parent-key parent)}
           :details {:op :remove :key target-key}}))

      :replace
      (let [target-key (key-id (:target-key command))
            target (find-node composition target-key)
            blueprint (value-id (:blueprint command))]
        (when-not target (throw (ex-info "replace target not found" {:key target-key})))
        (when-not (boundary-allows? composition target-key :replace "")
          (throw (ex-info "replace rejected by component boundary" {:key target-key})))
        (when-not (get (:blueprints composition) blueprint)
          (throw (ex-info "unknown UI blueprint" {:blueprint blueprint})))
        (let [[next _old _new] (replace-node composition target-key blueprint
                                               (:props command) (:slots command))]
          {:composition next
           :inverse {:op :restore-node :target-key target-key :node target}
           :affected #{target-key}
           :details {:op :replace :key target-key :blueprint blueprint}}))

      :move
      (let [target-key (key-id (:target-key command))
            parent-key (key-id (:parent-key command))
            target (find-node composition target-key)
            old-parent (find-parent composition target-key)
            slot (slot-id (:slot command))
            index (int (or (:index command) -1))]
        (when-not target (throw (ex-info "move target not found" {:key target-key})))
        (when-not old-parent (throw (ex-info "root cannot be moved" {:key target-key})))
        (when (= target-key parent-key)
          (throw (ex-info "node cannot move into itself" {:key target-key})))
        (when (descendant? composition target-key parent-key)
          (throw (ex-info "node cannot move into its descendant" {:key target-key :parent-key parent-key})))
        (when-not (find-node composition parent-key)
          (throw (ex-info "move parent not found" {:key parent-key})))
        (when-not (boundary-allows? composition (:parent-key old-parent) :move (:slot old-parent))
          (throw (ex-info "move source rejected by component boundary" {:key target-key})))
        (when-not (boundary-allows? composition parent-key :move slot)
          (throw (ex-info "move destination rejected by component boundary" {:key parent-key})))
        (let [[without _] (remove-child (:root composition) (:parent-key old-parent)
                                         (:slot old-parent) (:index old-parent))
              adjusted-index (if (and (= (:parent-key old-parent) parent-key)
                                      (= (:slot old-parent) slot)
                                      (>= index (:index old-parent)))
                               (dec index) index)
              root (put-child without parent-key slot adjusted-index target)]
          {:composition (assoc composition :root root)
           :inverse {:op :move :target-key target-key
                     :parent-key (:parent-key old-parent)
                     :slot (:slot old-parent) :index (:index old-parent)}
           :affected #{target-key parent-key (:parent-key old-parent)}
           :details {:op :move :key target-key :parent-key parent-key}}))

      :restore
      (let [parent-key (key-id (:parent-key command))
            node (normalize-node (:node command))
            root (put-child (:root composition) parent-key (:slot command)
                            (int (:index command)) node)]
        {:composition (assoc composition :root root)
         :inverse {:op :remove :target-key (:key node)}
         :affected #{parent-key (:key node)}})

      :restore-node
      (let [target-key (key-id (:target-key command))
            old (find-node composition target-key)]
        (when-not old (throw (ex-info "restore target not found" {:key target-key})))
        {:composition (assoc composition :root
                              (update-node (:root composition) target-key
                                           (constantly (normalize-node (:node command)))))
         :inverse {:op :restore-node :target-key target-key :node old}
         :affected #{target-key}})

      (throw (ex-info "unknown composition edit" {:operation operation})))))

(defn- record-edit [composition command result]
  (let [revision (inc (:composition-revision composition))
        next (assoc (:composition result)
                    :composition-revision revision
                    :redo [])
        entry {:command command :inverse (:inverse result)}
        history (conj (:history next) entry)
        limit (:history-limit next)]
    (assoc next :history (if (pos? limit)
                           (vec (take-last limit history))
                           []))))

(defn apply-edit!
  "Apply one declarative edit atomically. Returns {:status :applied|:rejected|:noop
   :composition ... :revision ...}. Rejected commands do not alter state." 
  [composition command]
  (try
    (let [result (apply-internal composition command)
          next (record-edit composition command result)]
      {:status :applied
       :composition next
       :revision (:composition-revision next)
       :affected (:affected result)
       :details (:details result)})
    (catch clojure.lang.ExceptionInfo error
      {:status :rejected
       :composition composition
       :revision (:composition-revision composition)
       :message (.getMessage error)
       :details (ex-data error)})))

(defn undo!
  "Undo the newest session edit without changing BaseView." 
  [composition]
  (if-let [entry (peek (:history composition))]
    (try
      (let [result (apply-internal composition (:inverse entry))
            history (pop (:history composition))
            next (assoc (:composition result)
                        :history history
                        :redo (conj (:redo composition) entry)
                        :composition-revision (inc (:composition-revision composition)))]
        {:status :applied :composition next :revision (:composition-revision next)})
      (catch clojure.lang.ExceptionInfo error
        {:status :rejected :composition composition
         :revision (:composition-revision composition)
         :message (.getMessage error) :details (ex-data error)}))
    {:status :noop :composition composition :revision (:composition-revision composition)}))

(defn redo!
  "Redo the newest undone session edit without mutating BaseView." 
  [composition]
  (if-let [entry (peek (:redo composition))]
    (let [result (apply-edit! (assoc composition :history [] :redo []) (:command entry))]
      (if (= :applied (:status result))
        (let [next (:composition result)]
          {:status :applied
           :composition (assoc next :history (conj (:history composition) entry)
                               :redo (pop (:redo composition)))
           :revision (:revision result)})
        result))
    {:status :noop :composition composition :revision (:composition-revision composition)}))

(defn reset-composition!
  "Discard all session edits and recreate the active projection from BaseView." 
  [composition]
  (base-composition (:base-view composition)
                    {:history-limit (:history-limit composition)}))
