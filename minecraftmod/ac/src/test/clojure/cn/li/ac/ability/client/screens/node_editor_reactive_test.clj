(ns cn.li.ac.ability.client.screens.node-editor-reactive-test
  "Pure controller coverage for the V4 free-graph editor.

   Presentation smoke tests cover compiled view routing; this namespace
   covers the controller's V4 geometry and graph edits without a game window."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [cn.li.ability.editor.label :as label]
            [cn.li.ac.ability.client.screens.node-editor-reactive :as editor]))

;; P6: this screen no longer carries its own copy of the truncation
;; algorithm -- ui-label is a thin wrapper over the one shared function
;; spell_composer_reactive.clj and graph.clj also call.
(deftest ui-label-delegates-to-the-shared-label-namespace-test
  (is (= (label/ellipsize "a somewhat long editor label" 40.0)
         (#'editor/ui-label "a somewhat long editor label" 40.0))))

;; C7: the palette row has no room for a description line (:doc is never
;; populated by any source anyway -- see selected-signature's own
;; docstring), so the real, always-populated :params/:returns signature
;; surfaces in the inspector instead, once a component node is selected.
(deftest selected-signature-reports-param-count-and-return-type-test
  (let [g {:nodes {"n1" {:nid "n1" :type :component :component :target/raycast
                         :inputs {:from [0.0 0.0 0.0]}}}
           :links []}
        palette [{:id :target/raycast :params {:from {:type :vec3} :dir {:type :vec3}} :returns :entity}]]
    (is (= "2 params -> entity"
           (#'editor/selected-signature {:graph g :selected-nid "n1" :palette palette})))))

(deftest selected-signature-is-nil-without-a-component-selection-test
  (let [g {:nodes {"n1" {:nid "n1" :type :literal :value 1}} :links []}]
    (is (nil? (#'editor/selected-signature {:graph g :selected-nid "n1" :palette []})))
    (is (nil? (#'editor/selected-signature {:graph g :selected-nid nil :palette []})))))

;; C5: editor_vocab_translations.clj generated ~140 real labels that the
;; palette never displayed -- :label used the raw :id instead, even
;; though the translated text was already in the search index
;; (palette-search-text). This confirms the DISPLAYED label actually
;; changes with the entry's :i18n resolution, not just that some string
;; is present.
(deftest palette-item-label-comes-from-i18n-not-the-raw-id-test
  (let [item (#'editor/palette-item {:id :target/raycast :i18n "editor.node.combat.target.raycast"
                                     :cost 1 :source :node :category :targeting})]
    (is (not= "target/raycast" (:label item)))
    (is (not (str/includes? (:label item) "target/raycast")))))

;; P1/P2: cost used to be concatenated into the label text and truncated
;; together with it (a long name could push cost off the end entirely) --
;; it is now :cost-label, a field of its own.
(deftest palette-item-keeps-cost-as-a-separate-field-from-label-test
  (let [item (#'editor/palette-item {:id :target/raycast :i18n "editor.node.combat.target.raycast"
                                     :cost 7 :source :node :category :targeting})]
    (is (contains? item :cost-label))
    (is (= "7" (:cost-label item)))
    (is (not (str/includes? (:label item) "7")))))

;; C6: check.clj's diagnostics docstring says :nid is meant to let the
;; editor "jump straight to and highlight the failing node" -- this
;; confirms the dispatch actually does that, not just that the data
;; carries a :nid the UI happens to bind.
(deftest focus-diagnostic-jumps-to-the-attributed-node-test
  (is (= :n7 (:selected-nid (#'editor/focus-diagnostic {} {:item {:nid "n7"}})))))

(deftest focus-diagnostic-is-a-safe-noop-without-an-attributed-nid-test
  (let [result (#'editor/focus-diagnostic {:selected-nid :untouched} {:item {:nid nil}})]
    (is (= :untouched (:selected-nid result)))
    (is (= "This diagnostic is not attributed to a single node." (:status result)))))

;; Regression: both editor entry points (the G keybind and editor_dev_tool)
;; refused to open with "not on-disk (packaged jar?)" even though the file
;; was on disk. io/resource returns only the FIRST classpath match, and in
;; a dev run this resource exists three times over -- ac's jar plus two
;; resources/main outputs -- so the jar could win and the file: copies were
;; never looked at.
(deftest default-sample-skill-resource-path-finds-the-real-shipped-file-test
  (let [path (editor/default-sample-skill-resource-path "ac/skills-v4/thunder-bolt.edn")]
    (is (some? path) "the shipped sample must resolve to an on-disk path")
    (is (.isFile (clojure.java.io/file path)))
    (is (str/ends-with? (str/replace path "\\" "/") "ac/skills-v4/thunder-bolt.edn"))))

(deftest default-sample-skill-resource-path-is-nil-for-a-missing-resource-test
  (is (nil? (editor/default-sample-skill-resource-path "ac/skills-v4/no-such-file.edn"))))

;; :editor/export overwrites the real source file, so handing it a
;; build-output copy would let an export silently land somewhere the next
;; build wipes. The mapping is derived then CHECKED -- an unrelated path,
;; or one whose source-tree counterpart does not exist, maps to nil.
(deftest build-output-paths-map-back-to-the-source-tree-test
  (let [src (editor/default-sample-skill-resource-path "ac/skills-v4/thunder-bolt.edn")
        normalized (str/replace src "\\" "/")]
    (is (str/includes? normalized "/src/main/resources/")
        (str "must prefer the source tree over a build output, got " src))
    (is (not (str/includes? normalized "/build/")))))

(deftest source-tree-original-maps-both-dev-run-output-layouts-test
  ;; Uses the real repo so the "does this file exist" half is genuinely
  ;; exercised; project-root is derived from the path, not hardcoded.
  (let [res "ac/skills-v4/thunder-bolt.edn"
        real (str/replace (editor/default-sample-skill-resource-path res) "\\" "/")
        root (subs real 0 (- (count real) (count (str "ac/src/main/resources/" res))))]
    (doseq [layout ["build/neutral/ac/resources/main/"
                    "build/targets/forge-1.20.1/platform/resources/main/"]]
      (is (= real (str/replace (#'editor/source-tree-original (str root layout res) res) "\\" "/"))
          (str "must map " layout " back to the source tree")))))

(deftest source-tree-original-rejects-unmappable-paths-test
  (let [res "ac/skills-v4/thunder-bolt.edn"]
    ;; not under any build output -> nothing to map back from
    (is (nil? (#'editor/source-tree-original (str "/somewhere/else/" res) res)))
    ;; mapped path derived fine but the source-tree file does not exist
    (is (nil? (#'editor/source-tree-original
               "/root/build/neutral/ac/resources/main/ac/skills-v4/no-such.edn"
               "ac/skills-v4/no-such.edn")))))

(defn- graph [& nodes]
  {:nodes (into {} (map (fn [[nid type & kvs]]
                          [nid (into {:nid nid :type type} (apply hash-map kvs))]) nodes))
   :links []})

;; Single-canvas-rectangle contract (the :canvas-viewport modal was removed
;; in favor of the shared editor shell -- cn.li.ability.editor.chrome/
;; panel-geometry now supplies stage origin/size from :palette-open?/
;; :inspector-open?/:diagnostics, not a two-branch compact/viewport mode).
;; See node_editor_reactive.clj's own shell-geometry/design-width/
;; palette-open-w/inspector-open-w for the numbers this derives from:
;; design 560x380, header-h 48, footer-h 32, palette-open-w 130,
;; inspector-open-w 180.

(deftest screen-canvas-point-follows-stage-origin-test
  (testing "default origin: both panels open, stage starts at (130, 48)"
    (is (= {:x 20.0 :y 20.0}
           (#'editor/screen->canvas-point
            {:zoom 1.0 :viewport {:x 0.0 :y 0.0}}
            150.0 68.0))))
  (testing "collapsed palette shifts the origin to x=0; zoom and viewport still invert"
    (is (= {:x 24.0 :y 15.0}
           (#'editor/screen->canvas-point
            {:palette-open? false :zoom 2.0 :viewport {:x 10.0 :y -8.0}}
            58.0 70.0)))))

(deftest canvas-pointer-bounds-follow-panel-state-test
  (testing "both panels open (default): stage is [130,48]..[380,348]"
    (is (true? (#'editor/canvas-pointer? {} 130.0 48.0)))
    (is (true? (#'editor/canvas-pointer? {} 380.0 348.0)))
    (is (false? (#'editor/canvas-pointer? {} 129.9 48.0)))
    (is (false? (#'editor/canvas-pointer? {} 380.1 348.0))))
  (testing "collapsed palette grows the stage leftward to x=0"
    (is (true? (#'editor/canvas-pointer? {:palette-open? false} 0.0 48.0)))
    (is (true? (#'editor/canvas-pointer? {:palette-open? false} 380.0 348.0))))
  (testing "both panels collapsed: stage spans the full design width"
    (let [state {:palette-open? false :inspector-open? false}]
      (is (true? (#'editor/canvas-pointer? state 0.0 48.0)))
      (is (true? (#'editor/canvas-pointer? state 560.0 348.0)))))
  (testing "a non-empty diagnostics list shrinks the stage height"
    (let [state {:diagnostics [{:code :x :message "m"}]}]
      (is (true? (#'editor/canvas-pointer? state 130.0 320.0)))
      (is (false? (#'editor/canvas-pointer? state 130.0 320.1))))))

(deftest screen-delta-is-scaled-only-for-node-movement
  (is (= {:dx 3.0 :dy -2.0}
         (#'editor/screen->canvas-delta {:zoom 2.0} 6.0 -4.0)))
  (is (= {:dx 6.0 :dy -4.0}
         (#'editor/screen->canvas-delta {:zoom 1.0} 6.0 -4.0))))

(deftest fixed-palette-insertion-creates-editable-v4-node
  (let [{:keys [graph nid]} (#'editor/insert-v4-palette-node
                             (graph [:n/start :start])
                             {:id :node/repeat :fixed-type :repeat :params {}}
                             "palette-test")]
    (is (= :repeat (get-in graph [:nodes nid :type])))
    (is (= 1 (get-in graph [:nodes nid :count])))
    (is (= nid (get-in graph [:nodes nid :nid])))))

(deftest component-palette-insertion-preserves-default-input-slots
  (let [{:keys [graph nid]} (#'editor/insert-v4-palette-node
                             (graph [:n/start :start])
                             {:id :math/add
                              :params {:arg0 {:type :double :default 1.5}
                                       :arg1 {:type :long :default 2}}}
                             "palette-test")]
    (is (= :component (get-in graph [:nodes nid :type])))
    (is (= :math/add (get-in graph [:nodes nid :component])))
    (is (= {:arg0 1.5 :arg1 2} (get-in graph [:nodes nid :inputs])))))

(deftest v4-wire-kind-and-target-port-are-derived-from-pins
  (let [g (assoc (graph [:n/lit :literal :value 1]
                        [:n/action :component :component :math/add]
                        [:n/end :end])
                 :links [{:id :e/exec :kind :exec
                          :from [:n/action :out] :to [:n/end :in]}])
        wired (#'editor/connect-v4-wire
               g {:from-nid :n/lit :from-pin :out :from-key :value
                  :to-nid :n/action :to-pin :in :to-key :arg0})
        link (last (:links wired))]
    (is (= :data (:kind link)))
    (is (= [[:n/lit :value] [:n/action :arg0]]
           [(:from link) (:to link)]))))

(deftest remove-v4-node-removes-incident-links-but-protects-sentinels
  (let [g (assoc (graph [:n/start :start]
                        [:n/action :component :component :math/add]
                        [:n/end :end])
                 :links [{:id :e/a :kind :exec :from [:n/start :out] :to [:n/action :in]}
                         {:id :e/b :kind :exec :from [:n/action :out] :to [:n/end :in]}])
        removed (#'editor/remove-v4-node g :n/action)]
    (is (nil? (get-in removed [:nodes :n/action])))
    (is (empty? (:links removed)))
    (is (thrown? clojure.lang.ExceptionInfo (#'editor/remove-v4-node g :n/start)))
    (is (thrown? clojure.lang.ExceptionInfo (#'editor/remove-v4-node g :n/end)))))

(deftest selected-node-info-reads-the-cached-catalog-not-a-fresh-assemble
  ;; A3: open-document used to build state without a VFX catalog at all --
  ;; selected-node-info called fx-catalog/assemble itself on EVERY :vfx!
  ;; node selection, re-parsing and re-validating all 36 ac/vfx-v4/*.edn
  ;; documents per click. open-document now assembles once and stores the
  ;; result under :vfx-catalog-by-id; selected-node-info must read that
  ;; field instead of calling assemble again -- these tests construct
  ;; state by hand (no file I/O, no real catalog) precisely to prove
  ;; selected-node-info's own correctness is independent of fx-catalog.
  (let [lit {:n/lit {:nid :n/lit :expr :literal :value 1.0}}
        vfx-node {:nid :n/fx :stmt :vfx! :effect-id :known-effect
                  :fields {:start :n/lit :bogus-field :n/lit}}
        g {:nodes (assoc lit :n/fx vfx-node) :links []}
        catalog {:known-effect {:user-types {:start :vec3}}}]
    (testing "an unknown field on a known effect is reported"
      (let [info (#'editor/selected-node-info
                  {:graph g :selected-nid :n/fx :mode :skill :vfx-catalog-by-id catalog})]
        (is (some? info))
        (is (re-find #"unknown fields: bogus-field" (:text info)))))
    (testing "no note when every field is declared"
      (let [clean-g {:nodes (assoc lit :n/fx (assoc vfx-node :fields {:start :n/lit})) :links []}
            info (#'editor/selected-node-info
                  {:graph clean-g :selected-nid :n/fx :mode :skill :vfx-catalog-by-id catalog})]
        (is (not (re-find #"unknown fields" (:text info))))))
    (testing "scene mode never surfaces the vfx note, regardless of the catalog"
      (let [info (#'editor/selected-node-info
                  {:graph g :selected-nid :n/fx :mode :scene :vfx-catalog-by-id catalog})]
        (is (not (re-find #"unknown fields" (:text info))))))
    (testing "an effect-id absent from the catalog produces no note (a
              different, more serious problem check/unknown-vfx-fields
              deliberately reports as nil, not an empty set)"
      (let [unknown-effect-g {:nodes (assoc lit :n/fx (assoc vfx-node :effect-id :does-not-exist)) :links []}
            info (#'editor/selected-node-info
                  {:graph unknown-effect-g :selected-nid :n/fx :mode :skill :vfx-catalog-by-id catalog})]
        (is (not (re-find #"unknown fields" (:text info))))))))
