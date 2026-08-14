(ns cn.li.presentation.core.input
  "Runtime-owned input state: capture/target/bubble dispatch, pointer capture,
   focus navigation and IME preedit. Game controllers only receive actions."
  (:import [cn.li.presentation.core EventResult]))

(defn create []
  {:nodes (atom {})
   :focus (atom nil)
   :pointer-capture (atom nil)
   :ime (atom {:text "" :preedit "" :selection [0 0]})})

(defn register-node! [runtime node-id parent-id handler]
  (swap! (:nodes runtime) assoc node-id {:id node-id :parent parent-id :handler handler
                                         :focusable? false :tab-index 0})
  node-id)

(defn set-focusable! [runtime node-id focusable? tab-index]
  (swap! (:nodes runtime) update node-id merge
         {:focusable? (boolean focusable?) :tab-index (int tab-index)})
  nil)

(defn- ancestry [nodes target]
  (loop [current target result []]
    (if (nil? current)
      result
      (recur (get-in nodes [current :parent]) (conj result current)))))

(defn- invoke-handler [runtime node-id phase event]
  (when-let [handler (get-in @(:nodes runtime) [node-id :handler])]
    (let [result (handler phase event)]
      (cond
        (= result EventResult/CAPTURE_POINTER)
        (do (reset! (:pointer-capture runtime) node-id) :capture-pointer)
        (= result EventResult/CONSUME) :consume
        :else :pass))))

(defn dispatch! [runtime target event]
  (let [nodes @(:nodes runtime)
        target (or @(:pointer-capture runtime) target)
        path (ancestry nodes target)
        capture-path (reverse (rest path))]
    (or (some #(when (not= :pass (invoke-handler runtime % :capture event)) true) capture-path)
        (when (not= :pass (invoke-handler runtime target :target event)) true)
        (some #(when (not= :pass (invoke-handler runtime % :bubble event)) true) (rest path))
        false)))

(defn release-pointer! [runtime]
  (reset! (:pointer-capture runtime) nil)
  nil)

(defn focus! [runtime node-id]
  (when-not (get-in @(:nodes runtime) [node-id :focusable?])
    (throw (ex-info "node is not focusable" {:node-id node-id})))
  (reset! (:focus runtime) node-id)
  node-id)

(defn focus-next! [runtime direction]
  (let [focusable (->> @(:nodes runtime)
                       vals
                       (filter :focusable?)
                       (sort-by (juxt :tab-index :id))
                       vec)
        ids (mapv :id focusable)
        current @(:focus runtime)
        index (.indexOf ^java.util.List ids current)
        next-index (if (seq ids)
                     (mod (+ (if (= direction :backward) -1 1)
                             (if (neg? index) 0 index)) (count ids))
                     -1)]
    (when (>= next-index 0) (reset! (:focus runtime) (nth ids next-index)))))

(defn update-ime! [runtime text preedit selection]
  (reset! (:ime runtime) {:text (str text) :preedit (str preedit)
                          :selection (vec selection)})
  nil)

(defn snapshot [runtime]
  {:focus @(:focus runtime)
   :pointer-capture @(:pointer-capture runtime)
   :ime @(:ime runtime)})
