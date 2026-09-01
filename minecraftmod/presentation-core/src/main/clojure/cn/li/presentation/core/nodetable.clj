(ns cn.li.presentation.core.nodetable
  "Runtime NodeTable construction from a compiled .uic.edn artifact.

   One NodeTable is built per view-id and shared by every mount of that
   view — construction happens once, lazily, on first mount, since the
   compiled node tree never changes for the lifetime of the JVM."
  (:import [cn.li.presentation.core.engine NodeTable]
           [cn.li.mcmod.runtime UiResourceRef UiResourceRef$Kind]))

(defn- resource-kind [kind]
  (case (or kind :texture)
    :texture UiResourceRef$Kind/TEXTURE
    :font UiResourceRef$Kind/FONT
    :model UiResourceRef$Kind/MODEL
    UiResourceRef$Kind/TEXTURE))

(defn- resource-ref [{:keys [namespace path kind]}]
  (UiResourceRef. (str namespace) (str path) (resource-kind kind)))

(defn build-node-table
  "Convert a compiled artifact's flat EDN vectors into a Java NodeTable.
   Called once per view-id; see table-for for the memoized entry point."
  ^NodeTable [artifact]
  (let [n (int (:node-count artifact))
        ints (fn ^ints [k] (int-array (get artifact k)))
        floats (fn ^floats [k] (float-array (get artifact k)))
        longs (fn ^longs [k] (long-array (get artifact k)))
        style-table (object-array (or (:style-table artifact) []))
        string-table (into-array String (or (:string-table artifact) []))
        resources (into-array UiResourceRef (mapv resource-ref (or (:resources artifact) [])))
        bind-paths (object-array (mapv :path (or (:bindings artifact) [])))
        action-table (object-array (mapv :name (or (:actions artifact) [])))
        node-keys (object-array (or (:node/key artifact) []))
        focus-order (int-array (or (:focus-order artifact) []))]
    (NodeTable. n
                (ints :node/op) (ints :node/parent) (ints :node/first-child)
                (ints :node/next-sibling) (ints :node/child-count) (ints :node/flags)
                (floats :node/box)
                (ints :node/width-mode) (floats :node/width-value)
                (ints :node/height-mode) (floats :node/height-value)
                (floats :node/gap) (floats :node/aspect)
                (floats :node/declared-x) (floats :node/declared-y)
                (ints :node/direction) (ints :node/justify)
                (ints :node/align-items) (ints :node/align-self)
                (ints :node/style) (ints :node/bind) (ints :node/action)
                (ints :node/res) (ints :node/anim) (ints :node/text-index)
                (floats :node/font-size)
                (ints :node/rgba)
                (longs :node/dep-mask) (int (:mask-words artifact)) (int (:binding-count artifact))
                style-table string-table resources
                bind-paths action-table node-keys focus-order)))

(defonce ^:private tables* (atom {}))

(defn table-for
  "The NodeTable for artifact's view-id, building and caching it on first use."
  ^NodeTable [artifact]
  (let [view-id (:view-id artifact)]
    (or (get @tables* view-id)
        (let [t (build-node-table artifact)]
          (swap! tables* assoc view-id t)
          t))))

(defn clear-tables-for-test!
  "Test-only: drop the cache so a test's synthetic artifact under a reused
   view-id doesn't see a stale NodeTable from a previous test."
  []
  (reset! tables* {}))
