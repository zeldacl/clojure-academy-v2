(ns cn.li.ac.testing.fixtures
  "Test fixtures for AC module testing.
  
  Provides reusable setup/teardown and mock object construction
  for integration and unit tests."
  (:require [cn.li.ac.foundation.vblock :as vb]
            [cn.li.ac.wireless.gui.state.sync :as sync]
            [cn.li.ac.wireless.gui.events.bus :as bus]))

;; ============================================================================
;; Mock Fixtures
;; ============================================================================

(defn with-mock-world
  "Test fixture: Create temporary world data structure.
  
  Returns:
    Mock world data atom"
  []
  (atom {:networks []
         :net-lookup {}
         :spatial-index {}
         :connections []}))

(defn with-mock-player
  "Test fixture: Create mock player object.
  
  Args:
    name: Player name (optional, default TestPlayer)
    
  Returns:
    Mock player with UUID, name, capabilities"
  [& [name]]
  {:uuid (java.util.UUID/randomUUID)
   :name (or name "TestPlayer")
   :abilities {}})

(defn with-mock-tile-entity
  "Test fixture: Create mock tile entity.
  
  Args:
    x, y, z: Coordinates (optional, default 0, 64, 0)
    
  Returns:
    Mock tile entity"
  [& [{:keys [x y z] :or {x 0 y 64 z 0}}]]
  {:x x :y y :z z
   :capabilities {}
   :nbt-data {}})

;; ============================================================================
;; VBlock Fixtures
;; ============================================================================

(defn mock-vblock
  "Create mock VBlock for testing.
  
  Args:
    type: :matrix | :node | :generator | :receiver (default :node)
    x, y, z: Coordinates (default 0, 64, 0)
    
  Returns:
    VBlock record"
  [& [{:keys [type x y z] :or {type :node x 0 y 64 z 0}}]]
  (case type
    :matrix (vb/vmatrix x y z)
    :node (vb/vnode x y z)
    :generator (vb/vgenerator x y z)
    :receiver (vb/vreceiver x y z)
    (vb/vblock x y z type false)))

(defn mock-network
  "Create mock network structure for testing.
  
  Args:
    ssid: Network SSID (default \"TestNetwork\")
    matrix-vblock: Matrix VBlock (optional, uses default)
    nodes: Vector of node VBlocks (optional, empty)
    
  Returns:
    Network map structure"
  [& [{:keys [ssid matrix-vblock nodes]
       :or {ssid "TestNetwork"
            matrix-vblock (mock-vblock {:type :matrix})
            nodes []}}]]
  {:ssid (atom ssid)
   :password (atom "")
   :matrix matrix-vblock
   :nodes (atom nodes)
   :energy (atom 0)
   :max-energy (atom 10000)
   :disposed (atom false)})

(defn mock-node-connection
  "Create mock node connection structure for testing.
  
  Args:
    node-vblock: Node VBlock (optional, uses default)
    generators: Vector of generator VBlocks (optional, empty)
    receivers: Vector of receiver VBlocks (optional, empty)
    
  Returns:
    Node connection map"
  [& [{:keys [node-vblock generators receivers]
       :or {node-vblock (mock-vblock {:type :node})
            generators []
            receivers []}}]]
  {:node node-vblock
   :generators (atom generators)
   :receivers (atom receivers)
   :disposed (atom false)})

;; ============================================================================
;; GUI State Fixtures
;; ============================================================================

(defn with-wireless-state-container
  "Test fixture: Create wireless GUI state container.
  
  Returns:
    IStateContainer with wireless state"
  []
  (sync/create-state-container
    {:connected-network nil
     :is-connected? false
     :available-networks []
     :last-refresh nil
     :loading false
     :last-error nil
     :error-time nil}))

(defn with-event-bus
  "Test fixture: Create event bus.
  
  Returns:
    IEventBus"
  []
  (bus/create-event-bus))

;; ============================================================================
;; Spy/Mock Helpers
;; ============================================================================

(defn spy-atom
  "Create spy atom that records all changes.
  
  Args:
    initial-value: Initial value
    
  Returns:
    {:atom atom :changes atom}"
  [initial-value]
  (let [atom-obj (atom initial-value)
        changes (atom [])]
    (add-watch atom-obj :spy
      (fn [key ref old-value new-value]
        (swap! changes conj {:old old-value :new new-value})))
    {:atom atom-obj
     :changes changes}))

(defn get-changes
  "Get and clear recorded changes from spy atom.
  
  Args:
    spy-result: From spy-atom result
    
  Returns:
    Vector of changes"
  [{:keys [changes]}]
  (let [recorded @changes]
    (reset! changes [])
    recorded))

;; ============================================================================
;; Database/Storage Fixtures
;; ============================================================================

(defn with-mock-nbt-data
  "Test fixture: Create mock NBT data structure.
  
  Returns:
    Mock NBT tag compound"
  []
  {:type :compound
   :tags {}})

(defn mock-wireless-nbt
  "Create mock NBT data for wireless network persistence.
  
  Args:
    ssid: Network SSID
    x, y, z: Matrix position
    version: NBT version (default 1)
    
  Returns:
    NBT structure"
  [& [{:keys [ssid x y z version]
       :or {ssid "Test" x 0 y 64 z 0 version 1}}]]
  {:NBTVersion version
   :SSID ssid
   :MatrixX x
   :MatrixY y
   :MatrixZ z
   :Nodes []
   :Energy 0})

;; ============================================================================
;; Cleanup Helpers
;; ============================================================================

(defn cleanup-mock
  "Cleanup mock object resources.
  
  Args:
    mock: Mock object (world, network, etc.)
    
  Returns:
    nil"
  [mock]
  (when (and (map? mock) (:disposed mock))
    (reset! (:disposed mock) true)))

(defn cleanup-all-mocks
  "Cleanup all mock objects in collection.
  
  Args:
    mocks: Vector of mock objects
    
  Returns:
    nil"
  [mocks]
  (doseq [mock mocks]
    (cleanup-mock mock)))

;; ============================================================================
;; Context Managers
;; ============================================================================

(defmacro with-mock-world-context
  "Execute code with mock world context.
  
  Usage:
    (with-mock-world-context [world-data]
      (is (= :test)))
  
  Automatically cleans up after test."
  [[binding] & body]
  `(let [~binding (with-mock-world)]
     (try
       ~@body
       (finally
         (cleanup-mock ~binding)))))

(defmacro with-wireless-gui-context
  "Execute code with wireless GUI state/event setup.
  
  Usage:
    (with-wireless-gui-context [state-container event-bus]
      (is (= :test)))
  
  Creates both state and event bus, cleans up after."
  [[state-binding bus-binding] & body]
  `(let [~state-binding (with-wireless-state-container)
         ~bus-binding (with-event-bus)]
     (try
       ~@body
       (finally
         ;; Cleanup if needed
         ))))

;; ============================================================================
;; Assertion Helpers
;; ============================================================================

(defn assert-vblock-valid
  "Assert that VBlock is structurally valid.
  
  Args:
    vblock: VBlock to check
    
  Throws:
    AssertionError if invalid"
  [vblock]
  (assert (vb/vblock? vblock))
  (assert (number? (:x vblock)))
  (assert (number? (:y vblock)))
  (assert (number? (:z vblock)))
  (assert (keyword? (:block-type vblock)))
  (assert (boolean? (:ignore-chunk vblock))))

(defn assert-network-valid
  "Assert that network structure is valid.
  
  Args:
    network: Network to check
    
  Throws:
    AssertionError if invalid"
  [network]
  (assert (instance? clojure.lang.IAtom (:ssid network)))
  (assert (instance? clojure.lang.IAtom (:nodes network)))
  (assert (instance? clojure.lang.IAtom (:energy network)))
  (assert (instance? clojure.lang.IAtom (:disposed network))))
