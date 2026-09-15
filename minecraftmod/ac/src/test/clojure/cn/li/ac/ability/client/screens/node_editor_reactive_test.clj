(ns cn.li.ac.ability.client.screens.node-editor-reactive-test
  "Pure controller coverage for the V4 free-graph editor.

   Presentation smoke tests cover compiled view routing; this namespace
   covers the controller's V4 geometry and graph edits without a game window."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [cn.li.ability.editor.label :as label]
            [cn.li.ac.ability.skills-catalog-v4 :as skills-catalog-v4]
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

(deftest focus-diagnostic-round-trips-a-real-namespaced-nid-test
  ;; The test above round-trips "n7" -- a nid with no namespace, which no
  ;; real graph produces. V4 :nodes is a #:n{...} map, so every key and
  ;; every node's :nid is :n/<id>. diagnostic-item used `name`, dropping
  ;; the namespace, so focus-diagnostic rebuilt :n004 and selected a key
  ;; the graph does not contain: "jump to the failing node" selected
  ;; nothing, and no test noticed.
  ;;
  ;; So assert the WHOLE path a click takes -- diagnostic -> item ->
  ;; focus-diagnostic -> :selected-nid -- lands on a nid that is actually
  ;; present in the graph, rather than testing either half in isolation.
  (let [graph {:nodes {:n/n004 {:nid :n/n004 :stmt :call}} :links []}
        item (#'editor/diagnostic-item {:code :type-mismatch :nid :n/n004 :message "m"})
        state (#'editor/focus-diagnostic {:graph graph} {:item item})]
    (is (= :n/n004 (:selected-nid state)))
    (is (contains? (:nodes graph) (:selected-nid state))
        "the jumped-to nid must exist in the graph, or nothing highlights")))

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

(deftest editor-effect-inputs-come-from-the-catalogs-builder
  ;; The editor used to build its own :effect-inputs map. It omitted
  ;; :auto-provided? on the universal capabilities, which is what
  ;; cn.li.node.types checks before skipping :missing-vfx-input and
  ;; :nil-vfx-input -- so the diagnostics panel reported missing-required
  ;; errors for :age/:progress/:seed/:source-player-id that the real build
  ;; never produces. It also never derived :required? at all.
  ;;
  ;; Asserted on the OUTPUT rather than by reading the call site, so
  ;; reintroducing a copy that happens to look right still fails.
  ;; A surface document: a context read is the symbol ?radius, where the
  ;; graph form spelled it {:type :context-ref :key :radius}.
  (let [by-id {:demo {:document {:parameters {:radius {:type :double}
                                              :tint {:type :any :default nil}}
                                 :phases {:render ['(ring {:radius ?radius})]}}}}
        specs (skills-catalog-v4/effect-input-specs by-id)
        demo (:demo specs)]
    (testing "universal capabilities are present and flagged auto-provided"
      (is (true? (:auto-provided? (:seed demo))))
      (is (true? (:auto-provided? (:age demo)))))
    (testing ":required? is derived from real use, and a :default opts out"
      (is (true? (:required? (:radius demo))) ":radius is read by the render graph")
      (is (nil? (:required? (:tint demo))) "a declared :default means the payload may omit it"))))

(deftest selected-node-info-annotates-from-compile-diagnostics
  ;; Replaces selected-node-info-reads-the-cached-catalog-not-a-fresh-assemble.
  ;; That test pinned an editor-only rule: selected-node-info called
  ;; check/unknown-vfx-fields against a :vfx-catalog-by-id cached in state,
  ;; a second implementation of what compile.clj already reports as
  ;; :unknown-vfx-field. V4 graphs can be produced without the editor, so an
  ;; editor-only copy protects nothing and can drift; the function and the
  ;; state key are both gone.
  ;;
  ;; The new contract: the inspector line annotates from the SAME
  ;; diagnostics vector the panel renders, so the two can never disagree,
  ;; and it covers every code -- not just field typos.
  (let [lit {:n/lit {:nid :n/lit :expr :literal :value 1.0}}
        vfx-node {:nid :n/fx :stmt :vfx! :effect-id :known-effect
                  :fields {:start :n/lit :bogus-field :n/lit}}
        g {:nodes (assoc lit :n/fx vfx-node) :links []}]
    (testing "a diagnostic attributed to the selected node appears in its text"
      (let [info (#'editor/selected-node-info
                  {:graph g :selected-nid :n/fx
                   :diagnostics [{:code :unknown-vfx-field :nid :n/fx :message "..."}]})]
        (is (some? info))
        (is (re-find #"unknown-vfx-field" (:text info)))))
    (testing "any code is surfaced, not only vfx field typos"
      (let [info (#'editor/selected-node-info
                  {:graph g :selected-nid :n/fx
                   :diagnostics [{:code :type-mismatch :nid :n/fx :message "..."}]})]
        (is (re-find #"type-mismatch" (:text info)))))
    (testing "multiple diagnostics on one node are all listed"
      (let [info (#'editor/selected-node-info
                  {:graph g :selected-nid :n/fx
                   :diagnostics [{:code :type-mismatch :nid :n/fx}
                                 {:code :nil-vfx-input :nid :n/fx}]})]
        (is (re-find #"type-mismatch" (:text info)))
        (is (re-find #"nil-vfx-input" (:text info)))))
    (testing "a diagnostic on a DIFFERENT node does not leak into this one"
      (let [info (#'editor/selected-node-info
                  {:graph g :selected-nid :n/fx
                   :diagnostics [{:code :type-mismatch :nid :n/lit}]})]
        (is (not (re-find #"type-mismatch" (:text info))))))
    (testing "no diagnostics means no annotation at all"
      (let [info (#'editor/selected-node-info {:graph g :selected-nid :n/fx :diagnostics []})]
        (is (some? info))
        (is (not (re-find #"\[" (:text info))))))
    (testing "an unattributed diagnostic (:nid nil) never matches a node"
      ;; check.clj's docstring is explicit that :nid is nil for an unstamped
      ;; form; `(some-> nil name keyword)` must stay nil rather than throw.
      (let [info (#'editor/selected-node-info
                  {:graph g :selected-nid :n/fx
                   :diagnostics [{:code :type-mismatch :nid nil}]})]
        (is (not (re-find #"type-mismatch" (:text info))))))))
