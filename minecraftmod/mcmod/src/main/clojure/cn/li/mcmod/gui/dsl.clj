(ns cn.li.mcmod.gui.dsl
  "GUI DSL - Declarative GUI definition using Clojure macros
  
  Supports both:
  - legacy \"generic\" GUI specs (string :id, slots/buttons/labels, XML layout)
  - wireless GUI metadata + runtime hooks (int :gui-id, registry/screen/container/sync metadata)"
  (:require [clojure.string :as str]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.platform.item :as item]))

;; GUI Registry - stores all defined GUIs
(defonce ^{:doc "Registry for GUI specs.

Structure:
- :by-id       {string-id -> GuiSpec}
- :by-gui-id   {int-gui-id -> GuiSpec} (wireless/platform-visible GUIs only)"}
  gui-registry
  (atom {:by-id {} :by-gui-id {}}))

;; Component specifications
(defrecord SlotSpec [index x y filter on-change])
(defrecord ButtonSpec [id x y width height text on-click])
(defrecord LabelSpec [x y text color])

;; ============================================================================
;; Nested Record Structures for GuiSpec
;; ============================================================================

(defrecord RegistrationConfig
  [display-name gui-type registry-name screen-factory-fn-kw slot-layout]
  ;; GUI registration metadata for platform adapters.
  ;;
  ;; Fields:
  ;; - display-name: Human-readable display name for the GUI
  ;; - gui-type: GUI type keyword (e.g., :node, :matrix, :solar-gen)
  ;; - registry-name: Registry name for platform registration (e.g., "wireless_node")
  ;; - screen-factory-fn-kw: Keyword for screen factory function (e.g., :create-node-screen)
  ;; - slot-layout: Slot layout configuration map with ranges
  )

(defrecord LifecycleHandlers
  [container-fn container-predicate screen-fn tick-fn]
  ;; GUI lifecycle hook functions.
  ;;
  ;; Fields:
  ;; - container-fn: Function to create container/menu (fn [gui-id inventory player-entity pos] ...)
  ;; - container-predicate: Predicate to test if a container matches this GUI (fn [container] boolean)
  ;; - screen-fn: Function to create screen (fn [container inventory component] ...)
  ;; - tick-fn: Function called every tick (fn [container] ...)
  )

(defrecord SyncConfig
  [sync-get sync-apply payload-sync-apply-fn]
  ;; Client-server synchronization configuration.
  ;;
  ;; Fields:
  ;; - sync-get: Function to get sync data from server (fn [container] data-map)
  ;; - sync-apply: Function to apply sync data on client (fn [container data-map] ...)
  ;; - payload-sync-apply-fn: Function to apply payload sync data (fn [container payload] ...)
  )

(defrecord OperationHandlers
  [validate-fn close-fn button-click-fn text-input-fn]
  ;; GUI operation handler functions.
  ;;
  ;; Fields:
  ;; - validate-fn: Function to validate if GUI can stay open (fn [container] boolean)
  ;; - close-fn: Function called when GUI closes (fn [container] ...)
  ;; - button-click-fn: Function to handle button clicks (fn [container button-id] ...)
  ;; - text-input-fn: Function to handle text input (fn [container text] ...)
  )

(defrecord SlotOperations
  [slot-count-fn slot-get-fn slot-set-fn slot-can-place-fn slot-changed-fn]
  ;; Slot operation handler functions.
  ;;
  ;; Fields:
  ;; - slot-count-fn: Function to get slot count (fn [container] int)
  ;; - slot-get-fn: Function to get item from slot (fn [container slot-index] ItemStack)
  ;; - slot-set-fn: Function to set item in slot (fn [container slot-index item-stack] ...)
  ;; - slot-can-place-fn: Function to check if item can be placed (fn [container slot-index item-stack] boolean)
  ;; - slot-changed-fn: Function called when slot changes (fn [container slot-index] ...)
  )

(defrecord LegacyLayout
  [title width height slots buttons labels background]
  ;; Legacy/generic GUI layout fields for XML-based GUIs.
  ;;
  ;; Fields:
  ;; - title: GUI title text
  ;; - width: GUI width in pixels
  ;; - height: GUI height in pixels
  ;; - slots: Vector of SlotSpec records
  ;; - buttons: Vector of ButtonSpec records
  ;; - labels: Vector of LabelSpec records
  ;; - background: Background texture identifier
  )

(defrecord GuiSpec
  [id gui-id
   registration lifecycle sync operations slots legacy-layout]
  ;; Complete GUI specification with nested configuration groups.
  ;;
  ;; Core identity fields:
  ;; - id: Legacy/generic ID used by XML/gui.container system (string)
  ;; - gui-id: Wireless/platform-visible GUI ID (int). When present, platform layers
  ;;          can register MenuType/ScreenHandlerType and open GUIs using this id.
  ;;
  ;; Nested configuration groups:
  ;; - registration: RegistrationConfig record
  ;; - lifecycle: LifecycleHandlers record
  ;; - sync: SyncConfig record
  ;; - operations: OperationHandlers record
  ;; - slots: SlotOperations record
  ;; - legacy-layout: LegacyLayout record
  )

;; Default values
(def default-gui-width 176)
(def default-gui-height 166)
(def default-button-width 60)
(def default-button-height 20)

;; Slot filter predicates
(defn any-item-filter [_item] true)

(defn item-type-filter [item-type]
  (fn [item-stack]
    (= (item/item-get-item item-stack) item-type)))

;; Parse slot specification
(defn parse-slot [slot-map]
  (map->SlotSpec
    {:index (:index slot-map)
     :x (:x slot-map 0)
     :y (:y slot-map 0)
     :filter (or (:filter slot-map) any-item-filter)
     :on-change (or (:on-change slot-map) (fn [_ _] nil))}))

;; Parse button specification
(defn parse-button [button-map]
  (map->ButtonSpec
    {:id (:id button-map)
     :x (:x button-map 0)
     :y (:y button-map 0)
     :width (or (:width button-map) default-button-width)
     :height (or (:height button-map) default-button-height)
     :text (:text button-map "Button")
     :on-click (or (:on-click button-map) (fn [] (log/info "Button clicked")))}))

;; Parse label specification
(defn parse-label [label-map]
  (map->LabelSpec
    {:x (:x label-map 0)
     :y (:y label-map 0)
     :text (:text label-map "")
     :color (or (:color label-map) 0x404040)}))

(defn- cfg-value
  "Read config from nested group first, then legacy top-level key."
  [spec nested-path legacy-key]
  (or (get-in spec nested-path)
      (get spec legacy-key)))

;; Validate GUI specification
(defn validate-gui-spec [gui-spec]
  (when-not (and (:id gui-spec) (string? (:id gui-spec)) (not (str/blank? (:id gui-spec))))
    (throw (ex-info "GUI must have a non-empty string :id" {:id (:id gui-spec) :spec gui-spec})))

  ;; If this GUI participates in platform registration, require wireless metadata.
  (when (some? (:gui-id gui-spec))
    (let [registration (:registration gui-spec)]
      (when-not (integer? (:gui-id gui-spec))
        (throw (ex-info "GUI :gui-id must be an integer when provided" {:gui-id (:gui-id gui-spec) :id (:id gui-spec)})))
      (when-not (and (string? (:registry-name registration)) (not (str/blank? (:registry-name registration))))
        (throw (ex-info "GUI :registry-name must be a non-empty string when :gui-id is present"
                        {:id (:id gui-spec) :gui-id (:gui-id gui-spec) :registry-name (:registry-name registration)})))
      (when-not (keyword? (:gui-type registration))
        (throw (ex-info "GUI :gui-type must be a keyword when :gui-id is present"
                        {:id (:id gui-spec) :gui-id (:gui-id gui-spec) :gui-type (:gui-type registration)})))
      (when-not (keyword? (:screen-factory-fn-kw registration))
        (throw (ex-info "GUI :screen-factory-fn-kw must be a keyword when :gui-id is present"
                        {:id (:id gui-spec) :gui-id (:gui-id gui-spec) :screen-factory-fn-kw (:screen-factory-fn-kw registration)})))))

  (let [legacy-layout (:legacy-layout gui-spec)]
    (doseq [slot (:slots legacy-layout)]
      (when-not (number? (:index slot))
        (throw (ex-info "Slot must have an :index number" {:slot slot}))))
    (doseq [button (:buttons legacy-layout)]
      (when-not (number? (:id button))
        (throw (ex-info "Button must have an :id number" {:button button})))))
  true)

;; Create GUI specification from options
(defn create-gui-spec [gui-id options]
  (let [;; Extract nested groups (new syntax)
        registration-opts (:registration options)
        lifecycle-opts (:lifecycle options)
        sync-opts (:sync options)
        operations-opts (:operations options)
        slots-opts (:slots options)
        legacy-layout-opts (:legacy-layout options)

        ;; Parse legacy layout components
        slots-vec (mapv parse-slot (or (:slots legacy-layout-opts) (:slots options) []))
        buttons-vec (mapv parse-button (or (:buttons legacy-layout-opts) (:buttons options) []))
        labels-vec (mapv parse-label (or (:labels legacy-layout-opts) (:labels options) []))

        ;; Build nested records
        registration (map->RegistrationConfig
                       {:display-name (or (:display-name registration-opts) (:display-name options))
                        :gui-type (or (:gui-type registration-opts) (:gui-type options))
                        :registry-name (or (:registry-name registration-opts) (:registry-name options))
                        :screen-factory-fn-kw (or (:screen-factory-fn-kw registration-opts) (:screen-factory-fn-kw options))
                        :slot-layout (or (:slot-layout registration-opts) (:slot-layout options))})

        lifecycle (map->LifecycleHandlers
                    {:container-fn (or (:container-fn lifecycle-opts) (:container-fn options))
                     :container-predicate (or (:container-predicate lifecycle-opts) (:container-predicate options))
                     :screen-fn (or (:screen-fn lifecycle-opts) (:screen-fn options))
                     :tick-fn (or (:tick-fn lifecycle-opts) (:tick-fn options))})

        sync (map->SyncConfig
               {:sync-get (or (:sync-get sync-opts) (:sync-get options))
                :sync-apply (or (:sync-apply sync-opts) (:sync-apply options))
                :payload-sync-apply-fn (or (:payload-sync-apply-fn sync-opts) (:payload-sync-apply-fn options))})

        operations (map->OperationHandlers
                     {:validate-fn (or (:validate-fn operations-opts) (:validate-fn options))
                      :close-fn (or (:close-fn operations-opts) (:close-fn options))
                      :button-click-fn (or (:button-click-fn operations-opts) (:button-click-fn options))
                      :text-input-fn (or (:text-input-fn operations-opts) (:text-input-fn options))})

        slot-operations (map->SlotOperations
                          {:slot-count-fn (or (:slot-count-fn slots-opts) (:slot-count-fn options))
                           :slot-get-fn (or (:slot-get-fn slots-opts) (:slot-get-fn options))
                           :slot-set-fn (or (:slot-set-fn slots-opts) (:slot-set-fn options))
                           :slot-can-place-fn (or (:slot-can-place-fn slots-opts) (:slot-can-place-fn options))
                           :slot-changed-fn (or (:slot-changed-fn slots-opts) (:slot-changed-fn options))})

        legacy-layout (map->LegacyLayout
                        {:title (or (:title legacy-layout-opts) (:title options) "GUI")
                         :width (or (:width legacy-layout-opts) (:width options) default-gui-width)
                         :height (or (:height legacy-layout-opts) (:height options) default-gui-height)
                         :slots slots-vec
                         :buttons buttons-vec
                         :labels labels-vec
                         :background (or (:background legacy-layout-opts) (:background options) :default)})

        spec (map->GuiSpec
               {:id gui-id
                :gui-id (:gui-id options)
                :registration registration
                :lifecycle lifecycle
                :sync sync
                :operations operations
                :slots slot-operations
          :legacy-layout legacy-layout
          ;; Flattened aliases for gradual call-site migration.
          :display-name (:display-name registration)
          :gui-type (:gui-type registration)
          :registry-name (:registry-name registration)
          :screen-factory-fn-kw (:screen-factory-fn-kw registration)
          :slot-layout (:slot-layout registration)
          :container-fn (:container-fn lifecycle)
          :container-predicate (:container-predicate lifecycle)
          :screen-fn (:screen-fn lifecycle)
          :tick-fn (:tick-fn lifecycle)
          :sync-get (:sync-get sync)
          :sync-apply (:sync-apply sync)
          :payload-sync-apply-fn (:payload-sync-apply-fn sync)
          :validate-fn (:validate-fn operations)
          :close-fn (:close-fn operations)
          :button-click-fn (:button-click-fn operations)
          :text-input-fn (:text-input-fn operations)
          :slot-count-fn (:slot-count-fn slot-operations)
          :slot-get-fn (:slot-get-fn slot-operations)
          :slot-set-fn (:slot-set-fn slot-operations)
          :slot-can-place-fn (:slot-can-place-fn slot-operations)
          :slot-changed-fn (:slot-changed-fn slot-operations)})]
    (validate-gui-spec spec)
    spec))

;; Register GUI in registry
(defn register-gui! [gui-spec]
  (log/info "Registering GUI:" (:id gui-spec))
  (swap! gui-registry
         (fn [reg]
           (let [id (:id gui-spec)
                 gui-id (:gui-id gui-spec)]
             (when (and (some? gui-id) (contains? (:by-gui-id reg) gui-id))
               ;; AOT/checkClojure 会重复加载同一份 GUI DSL，
               ;; gui-spec 中的函数对象无法保证“相等”，因此这里将重复 :gui-id 视为幂等。
               nil)
             (cond-> reg
               true (assoc-in [:by-id id] gui-spec)
               (some? gui-id) (assoc-in [:by-gui-id gui-id] gui-spec)))))
  gui-spec)

;; Get GUI from registry
(defn get-gui [gui-id]
  (get-in @gui-registry [:by-id gui-id]))

;; List all registered GUIs
(defn list-guis []
  (keys (:by-id @gui-registry)))

;; ============================================================================
;; Wireless/platform-visible GUI query API (int gui-id)
;; ============================================================================

(defn get-gui-by-gui-id
  "Get a GUI spec by wireless/platform GUI id (int)."
  [gui-id]
  (get-in @gui-registry [:by-gui-id gui-id]))

(defn list-gui-ids
  "List all registered wireless/platform GUI ids (ints)."
  []
  (keys (:by-gui-id @gui-registry)))

(defn get-all-gui-ids
  "Alias for platform adapters."
  []
  (seq (list-gui-ids)))

(defn has-gui-id?
  "Return true when a wireless/platform GUI id is registered."
  [gui-id]
  (contains? (:by-gui-id @gui-registry) gui-id))

(defn get-registry-name
  "Get registry name for a wireless GUI by gui-id."
  [gui-id]
  (some-> (get-gui-by-gui-id gui-id)
          (cfg-value [:registration :registry-name] :registry-name)))

(defn get-screen-factory-fn-kw
  "Get screen factory keyword for a wireless GUI by gui-id."
  [gui-id]
  (some-> (get-gui-by-gui-id gui-id)
          (cfg-value [:registration :screen-factory-fn-kw] :screen-factory-fn-kw)))

(defn get-gui-type
  "Get container/gui type keyword for a wireless GUI by gui-id."
  [gui-id]
  (some-> (get-gui-by-gui-id gui-id)
          (cfg-value [:registration :gui-type] :gui-type)))

(defn get-slot-layout
  "Get slot layout for a wireless GUI by gui-id."
  [gui-id]
  (some-> (get-gui-by-gui-id gui-id)
          (cfg-value [:registration :slot-layout] :slot-layout)))

(defn get-display-name
  "Get display name for a wireless GUI by gui-id."
  [gui-id]
  (some-> (get-gui-by-gui-id gui-id)
          (cfg-value [:registration :display-name] :display-name)))

(defn get-container-fn
  "Get container creation fn for a wireless GUI by gui-id."
  [gui-id]
  (some-> (get-gui-by-gui-id gui-id)
          (cfg-value [:lifecycle :container-fn] :container-fn)))

(defn get-screen-fn
  "Get screen creation fn for a wireless GUI by gui-id."
  [gui-id]
  (some-> (get-gui-by-gui-id gui-id)
          (cfg-value [:lifecycle :screen-fn] :screen-fn)))

(defn get-container-predicate
  "Get container predicate fn for a wireless GUI by gui-id."
  [gui-id]
  (some-> (get-gui-by-gui-id gui-id)
          (cfg-value [:lifecycle :container-predicate] :container-predicate)))

(defn get-payload-sync-apply-fn
  "Get payload sync apply fn for a wireless GUI by gui-id."
  [gui-id]
  (some-> (get-gui-by-gui-id gui-id)
          (cfg-value [:sync :payload-sync-apply-fn] :payload-sync-apply-fn)))

(defn get-slot-range
  "Get slot index range for a wireless GUI section.

  Returns [start end] inclusive, or [0 0] when not found."
  [gui-id section]
  (if-let [layout (get-slot-layout gui-id)]
    (get-in layout [:ranges section] [0 0])
    [0 0]))

(defn get-gui-by-type
  "Get a registered wireless GUI spec by its :gui-type keyword."
  [gui-type]
  (some (fn [[_gui-id spec]]
          (when (= (cfg-value spec [:registration :gui-type] :gui-type) gui-type)
            spec))
        (:by-gui-id @gui-registry)))

(defn get-gui-id-for-type
  "Get GUI id (int) for a :gui-type keyword, or nil."
  [gui-type]
  (some (fn [[gui-id spec]]
          (when (= (cfg-value spec [:registration :gui-type] :gui-type) gui-type)
            gui-id))
        (:by-gui-id @gui-registry)))

(defn get-config-by-container
  "Get GUI config by testing container against all registered predicates."
  [container]
  (some (fn [[_gui-id spec]]
          (when-let [pred (cfg-value spec [:lifecycle :container-predicate] :container-predicate)]
            (when (pred container)
              spec)))
        (:by-gui-id @gui-registry)))

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
  (defgui-with-lazy-fns wireless-node
    :gui-id 0
    :namespace 'cn.li.ac.block.wireless-node.gui
    :display-name \"Wireless Node\"
    :registry-name \"wireless_node\"
    :gui-type :node
    :screen-factory-fn-kw :create-node-screen
    :slot-layout {...}
    :payload-sync-fn 'apply-node-sync-payload!)

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
        ;; Generate lazy wrapper for each function
        wrappers (into {}
                   (for [[fn-name opt-kw] fn-mappings]
                     [opt-kw
                      `(fn [& args#]
                         (when-let [f# (requiring-resolve '~(symbol (str ns-sym) (str fn-name)))]
                           (apply f# args#)))]))
        ;; Add payload sync function if specified
        wrappers (if payload-sync-fn
                   (assoc wrappers :payload-sync-apply-fn
                     `(fn [& args#]
                        (when-let [f# (requiring-resolve '~(symbol (str ns-sym) (str payload-sync-sym)))]
                          (apply f# args#))))
                   wrappers)]
    `(defgui ~gui-name
       ~@(apply concat (merge wrappers (dissoc opts :namespace :payload-sync-fn))))))

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
        legacy-layout (:legacy-layout gui-spec)
        buttons-with-state (mapv
                             (fn [btn]
                               (assoc btn :enabled (atom true)))
                             (:buttons legacy-layout))]
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
  (let [legacy-layout (get-in gui-instance [:spec :legacy-layout])
        button-spec (nth (:buttons legacy-layout) button-id nil)]
    (when (and button-spec (button-enabled? gui-instance button-id))
      (log/info "Executing button" button-id ":" (:text button-spec))
      ((:on-click button-spec)))))

;; Execute slot change
(defn handle-slot-change [gui-instance slot-index old-stack new-stack]
  (let [legacy-layout (get-in gui-instance [:spec :legacy-layout])
        slot-spec (nth (:slots legacy-layout) slot-index nil)]
    (when slot-spec
      (if ((:filter slot-spec) new-stack)
        (do
          (set-slot-state! gui-instance slot-index new-stack)
          ((:on-change slot-spec) old-stack new-stack)
          true)
        (do
          (log/info "Item rejected by slot filter")
          false)))))
