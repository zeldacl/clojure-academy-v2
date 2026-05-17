(ns cn.li.mcmod.gui.dsl
  "GUI DSL - Declarative GUI definition using Clojure macros
  
  Supports generic GUI layout specs plus wireless/platform-visible GUI metadata
  and runtime hooks."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.gui.parser :as gui-parser]
            [cn.li.mcmod.gui.validator :as gui-validator]
            [cn.li.mcmod.gui.registry :as gui-registry-core]))

(def gui-registry gui-registry-core/gui-registry)

(def default-gui-width gui-parser/default-gui-width)
(def default-gui-height gui-parser/default-gui-height)
(def default-button-width gui-parser/default-button-width)
(def default-button-height gui-parser/default-button-height)

(def any-item-filter gui-parser/any-item-filter)
(def parse-slot gui-parser/parse-slot)
(def parse-button gui-parser/parse-button)
(def parse-label gui-parser/parse-label)
(def validate-gui-spec gui-validator/validate-gui-spec)
(def create-gui-spec gui-parser/create-gui-spec)
(def register-gui! gui-registry-core/register-gui!)
(def get-gui gui-registry-core/get-gui)
(def list-guis gui-registry-core/list-guis)
(def get-gui-by-gui-id gui-registry-core/get-gui-by-gui-id)
(def list-gui-ids gui-registry-core/list-gui-ids)
(def get-all-gui-ids gui-registry-core/get-all-gui-ids)
(def has-gui-id? gui-registry-core/has-gui-id?)
(def get-registry-name gui-registry-core/get-registry-name)
(def get-screen-factory-fn-kw gui-registry-core/get-screen-factory-fn-kw)
(def get-gui-type gui-registry-core/get-gui-type)
(def get-slot-layout gui-registry-core/get-slot-layout)
(def get-display-name gui-registry-core/get-display-name)
(def get-container-fn gui-registry-core/get-container-fn)
(def get-screen-fn gui-registry-core/get-screen-fn)
(def get-container-predicate gui-registry-core/get-container-predicate)
(def get-payload-sync-apply-fn gui-registry-core/get-payload-sync-apply-fn)
(def get-slot-range gui-registry-core/get-slot-range)
(def get-gui-by-type gui-registry-core/get-gui-by-type)
(def get-gui-id-for-type gui-registry-core/get-gui-id-for-type)
(def get-config-by-container gui-registry-core/get-config-by-container)

;; Main macro: defgui
(defmacro defgui
  "Define a GUI with declarative syntax

  Example:
  (defgui my-gui
    :title \"My GUI\"
    :width 176
    :height 166
    :slots [{:index 0 :x 80 :y 35}]
    :buttons [{:id 0 :x 120 :y 30 :text \"OK\" :on-click #(println \"Clicked!\")}]
    :labels [{:x 8 :y 6 :text \"Inventory\"}])"
  [gui-name & options]
  (let [gui-id (name gui-name)
        options-map (apply hash-map options)]
    `(def ~gui-name
       (register-gui!
         (create-gui-spec ~gui-id ~options-map)))))

;; Macro: defgui-with-lazy-fns
(defmacro defgui-with-lazy-fns
  "Define a GUI with automatic lazy function resolution.

  Eliminates boilerplate by automatically generating requiring-resolve wrappers
  for all standard GUI functions from a specified namespace.

  Example:
  (defgui-with-lazy-fns machine-panel
    :gui-id 0
    :namespace 'example.content.machine.gui
    :registration {:display-name \"Machine Panel\"
             :registry-name \"machine_panel\"
             :gui-type :machine
             :screen-factory-fn-kw :create-machine-screen
             :slot-layout {...}}
    :payload-sync-fn 'apply-machine-sync-payload!)

  This will automatically create lazy wrappers for:
  - create-container, create-screen, tick!
  - get-sync-data, apply-sync-data!
  - still-valid?, on-close, handle-button-click!
  - get-slot-count, get-slot-item, set-slot-item!
  - can-place-item?, slot-changed!

  Use :payload-sync-fn to specify the payload sync function name (varies per GUI)."
  [gui-name & {:keys [namespace payload-sync-fn] :as opts}]
  (when-not namespace
    (throw (ex-info "defgui-with-lazy-fns requires :namespace parameter" {:gui-name gui-name})))
  (let [ns-sym (if (and (seq? namespace) (= 'quote (first namespace)))
                 (second namespace)
                 namespace)
        payload-sync-sym (when payload-sync-fn
                           (if (and (seq? payload-sync-fn) (= 'quote (first payload-sync-fn)))
                             (second payload-sync-fn)
                             payload-sync-fn))
        ;; Map function names to their corresponding defgui option keywords
        fn-mappings {'create-container :container-fn
                     'create-screen :screen-fn
                     'tick! :tick-fn
                     'get-sync-data :sync-get
                     'apply-sync-data! :sync-apply
                     'still-valid? :validate-fn
                     'on-close :close-fn
                     'handle-button-click! :button-click-fn
                     'get-slot-count :slot-count-fn
                     'get-slot-item :slot-get-fn
                     'set-slot-item! :slot-set-fn
                     'can-place-item? :slot-can-place-fn
                     'slot-changed! :slot-changed-fn}
        wrappers (into {}
                   (for [[fn-name opt-kw] fn-mappings]
                     [opt-kw
                      `(fn [& args#]
                         (when-let [f# (requiring-resolve '~(symbol (str ns-sym) (str fn-name)))]
                           (apply f# args#)))]))
        wrappers (if payload-sync-fn
                   (assoc wrappers :payload-sync-apply-fn
                     `(fn [& args#]
                        (when-let [f# (requiring-resolve '~(symbol (str ns-sym) (str payload-sync-sym)))]
                          (apply f# args#))))
                   wrappers)
        merged-opts (merge opts wrappers)
        lifecycle-keys #{:container-fn :screen-fn :tick-fn}
        sync-keys #{:sync-get :sync-apply :payload-sync-apply-fn}
        operation-keys #{:validate-fn :close-fn :button-click-fn :text-input-fn}
        slot-operation-keys #{:slot-count-fn :slot-get-fn :slot-set-fn :slot-can-place-fn :slot-changed-fn}
        grouped-opts (let [grouped (-> merged-opts
                     (dissoc :namespace :payload-sync-fn)
                     (assoc :lifecycle (merge (select-keys merged-opts lifecycle-keys)
                      (:lifecycle merged-opts))
                      :sync (merge (select-keys merged-opts sync-keys)
                       (:sync merged-opts))
                      :operations (merge (select-keys merged-opts operation-keys)
                       (:operations merged-opts))
                      :slot-operations (merge (select-keys merged-opts slot-operation-keys)
                            (:slot-operations merged-opts))))]
                 (apply dissoc grouped (concat lifecycle-keys sync-keys operation-keys slot-operation-keys)))]
    `(defgui ~gui-name
       ~@(apply concat grouped-opts))))

;; XML-based GUI macro
(defmacro defgui-from-xml
  "Define a GUI from XML layout file
  
  Example:
  (defgui-from-xml node-gui
    :xml-layout \"page_wireless_node\"
    :on-init (fn [gui] ...)
    :on-render (fn [gui dt] ...))"
  [gui-name & options]
  (let [options-map (apply hash-map options)
        xml-layout (:xml-layout options-map)
        gui-id (name gui-name)]
    `(def ~gui-name
       (let [xml-parser# (requiring-resolve 'cn.li.mcmod.gui.xml-parser/load-gui-from-xml)
         base-spec# (xml-parser# ~gui-id ~xml-layout)
         merged-spec# (merge base-spec# ~(dissoc options-map :xml-layout))]
       (register-gui! (create-gui-spec (:id base-spec#) merged-spec#))))))

;; Helper: create slot handler that updates atom
(defn slot-change-handler [slots-atom slot-index]
  (fn [old-stack new-stack]
    (log/info "Slot" slot-index "changed from" old-stack "to" new-stack)
    (swap! slots-atom assoc slot-index new-stack)))

;; Helper: create button handler that clears slot
(defn clear-slot-handler [slots-atom slot-index]
  (fn []
    (log/info "Clearing slot" slot-index)
    (swap! slots-atom dissoc slot-index)))

;; Helper: create button handler that validates and processes
(defn processing-handler [slots-atom input-slots output-slot process-fn]
  (fn []
    (let [inputs (mapv #(get @slots-atom %) input-slots)]
      (when (every? some? inputs)
        (try
          (let [result (apply process-fn inputs)]
            (log/info "Processing successful, result:" result)
            (doseq [slot input-slots]
              (swap! slots-atom dissoc slot))
            (swap! slots-atom assoc output-slot result))
          (catch Exception e
            (log/info "Processing failed:"(ex-message e))))))))

;; GUI instance management
(defrecord GuiInstance [spec player world pos data])

(defn create-gui-instance
  "Create a runtime instance of a GUI for a specific player"
  [gui-spec player world pos]
  (let [slots-atom (atom {})
        layout (:layout gui-spec)
        buttons-with-state (mapv
                             (fn [btn]
                               (assoc btn :enabled (atom true)))
                             (:buttons layout))]
    (map->GuiInstance
      {:spec gui-spec
       :player player
       :world world
       :pos pos
       :data {:slots slots-atom
              :buttons buttons-with-state}})))

;; Get slot from instance
(defn get-slot-state [gui-instance slot-index]
  (get @(get-in gui-instance [:data :slots]) slot-index))

;; Set slot in instance
(defn set-slot-state! [gui-instance slot-index item-stack]
  (swap! (get-in gui-instance [:data :slots]) assoc slot-index item-stack))

;; Clear slot in instance
(defn clear-slot-state! [gui-instance slot-index]
  (swap! (get-in gui-instance [:data :slots]) dissoc slot-index))

;; Get button state
(defn button-enabled? [gui-instance button-id]
  (let [button (nth (get-in gui-instance [:data :buttons]) button-id nil)]
    (when button
      @(:enabled button))))

;; Set button enabled state
(defn set-button-enabled! [gui-instance button-id enabled?]
  (let [button (nth (get-in gui-instance [:data :buttons]) button-id nil)]
    (when button
      (reset! (:enabled button) enabled?))))

;; Execute button click
(defn handle-button-click [gui-instance button-id]
  (let [layout (get-in gui-instance [:spec :layout])
        button-spec (nth (:buttons layout) button-id nil)]
    (when (and button-spec (button-enabled? gui-instance button-id))
      (log/info "Executing button" button-id ":" (:text button-spec))
      ((:on-click button-spec)))))

;; Execute slot change
(defn handle-slot-change [gui-instance slot-index old-stack new-stack]
  (let [layout (get-in gui-instance [:spec :layout])
        slot-spec (nth (:slots layout) slot-index nil)]
    (when slot-spec
      (if ((:filter slot-spec) new-stack)
        (do
          (set-slot-state! gui-instance slot-index new-stack)
          ((:on-change slot-spec) old-stack new-stack)
          true)
        (do
          (log/info "Item rejected by slot filter")
          false)))))

