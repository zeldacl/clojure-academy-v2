(ns cn.li.presentation.core.semantics
  "Clojure-owned semantics/narration tree, independent from render nodes.")

(def roles #{:generic :progress :button :textbox :dialog :list :list-item :image
             :slot :heading})

(defn create []
  {:nodes (atom {})
   :root (atom nil)})

(defn upsert! [runtime node-id parent role label value state]
  (when-not (contains? roles role)
    (throw (ex-info "unknown semantics role" {:role role})))
  (when-not (string? label)
    (throw (ex-info "semantics label must be a string" {:label label})))
  (when (and parent (not (contains? @(:nodes runtime) parent)))
    (throw (ex-info "semantics parent does not exist" {:parent parent})))
  (when (nil? parent) (reset! (:root runtime) node-id))
  (swap! (:nodes runtime) assoc node-id
         {:id node-id :parent parent :role role :label label :value value
          :state (or state {})})
  node-id)

(defn remove! [runtime node-id]
  (swap! (:nodes runtime)
         (fn [nodes]
           (let [children (set (for [[id node] nodes :when (= node-id (:parent node))] id))]
             (apply dissoc nodes node-id children))))
  (when (= node-id @(:root runtime)) (reset! (:root runtime) nil))
  nil)

(defn- children [nodes parent]
  (->> nodes vals (filter #(= parent (:parent %))) (sort-by :id)))

(defn tree [runtime]
  (let [nodes @(:nodes runtime)
        visit (fn visit [id]
               (when-let [node (get nodes id)]
                 (assoc node :children (mapv visit (children nodes id)))))]
    (when-let [root @(:root runtime)] (visit root))))

(defn narration [runtime]
  (let [nodes @(:nodes runtime)]
    (letfn [(walk [node]
              (when node
                (into [(str (:label node)
                            (when (some? (:value node)) (str ": " (:value node))))]
                      (mapcat walk (children nodes (:id node))))))]
      (vec (walk (get nodes @(:root runtime)))))))
