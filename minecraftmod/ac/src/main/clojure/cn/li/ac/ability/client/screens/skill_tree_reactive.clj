(ns cn.li.ac.ability.client.screens.skill-tree-reactive
  "Reactive Skill Tree — native UiRt host with draw-ops canvas for full visual parity.
   State, layout, and draw-op builders remain in skill-tree."
  (:require [cn.li.ac.ability.client.screens.skill-tree :as logic]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.node :as node]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mcmod.ui.node INode]))

(def ^:private panel-w 420.0)
(def ^:private panel-h 260.0)
(def ^:private default-mx 210.0)
(def ^:private default-my 130.0)

(def ^:private ui-spec
  {:kind :group :id :root
   :props {:w panel-w :h panel-h :align-w :center :align-h :center}
   :children
   [{:kind :draw-ops :id :canvas
     :props {:x 0.0 :y 0.0 :w panel-w :h panel-h}}
    {:kind :box :id :input-layer
     :props {:x 0.0 :y 0.0 :w panel-w :h panel-h :fill 0x00000000}}]})

(def ^:private active-by-uuid (atom {}))

(defn- track-active! [owner ^UiRt r]
  (when-let [uuid (:player-uuid owner)]
    (swap! active-by-uuid assoc uuid {:rt r :owner owner})))

(defn- untrack-active! [owner]
  (when-let [uuid (:player-uuid owner)]
    (swap! active-by-uuid dissoc uuid)))

(defn- mark-canvas-dirty! [^UiRt r]
  (when-let [^INode canvas (ui/node r :canvas)]
    (.setFlag canvas node/FLAG-RENDER-DIRTY)))

(defn- set-mouse! [^UiRt r mx my owner]
  (let [mx-sig (rt/user-signal r :mouse-x)
        my-sig (rt/user-signal r :mouse-y)]
    (when mx-sig (sig/sset-d! mx-sig (double mx)))
    (when my-sig (sig/sset-d! my-sig (double my)))
    (logic/on-mouse-move owner (int mx) (int my))
    (mark-canvas-dirty! r)))

(defn- bump-revision! [^UiRt r]
  (when-let [rev (rt/user-signal r :revision)]
    (sig/sset-l! rev (inc (sig/sget-l rev))))
  (mark-canvas-dirty! r))

(defn refresh-active-screen!
  "Refresh open skill tree after server sync."
  [player-uuid]
  (when-let [{:keys [rt]} (get @active-by-uuid player-uuid)]
    (bump-revision! rt)))

(defn- wire-input! [^UiRt r owner]
  (let [handle-click!
        (fn [mx my]
          (set-mouse! r mx my owner)
          (logic/handle-screen-click! owner (int mx) (int my))
          (bump-revision! r))
        handle-move!
        (fn [mx my]
          (set-mouse! r mx my owner))]
    (rt/put-user-signal! r :on-pointer-move handle-move!)
    (when-let [^INode layer (ui/node r :input-layer)]
      (let [idx (.getIdx layer)]
        (rt/register-event! r idx :left-click
          (fn [_ _ evt]
            (handle-click! (:x evt) (:y evt))))
        (rt/register-event! r idx :drag
          (fn [_ _ evt]
            (handle-move! (:x evt) (:y evt))))))))

(defn- install-ops-fn! [^UiRt r owner]
  (when-let [^INode canvas (ui/node r :canvas)]
    (let [ops-fn (fn []
                   (let [mx-sig (rt/user-signal r :mouse-x)
                         my-sig (rt/user-signal r :mouse-y)
                         _rev (when-let [s (rt/user-signal r :revision)] (sig/sget-l s))
                         mx (if mx-sig (sig/sget-d mx-sig) default-mx)
                         my (if my-sig (sig/sget-d my-sig) default-my)]
                     (logic/build-draw-ops owner (int mx) (int my)
                       (int panel-w) (int panel-h))))]
      (.setOSlot canvas 0 ops-fn)
      (.setFlag canvas node/FLAG-RENDER-DIRTY))))

(defn create-runtime
  "Build reactive skill tree. payload may include :learn-context."
  [owner & {:keys [learn-context]}]
  (let [r (rt/create-runtime)]
    (logic/open-screen! owner learn-context)
    (rt/build! r ui-spec)
    (rt/put-user-signal! r :mouse-x (sig/signal-d default-mx))
    (rt/put-user-signal! r :mouse-y (sig/signal-d default-my))
    (rt/put-user-signal! r :revision (sig/signal-l 0))
    (track-active! owner r)
    (install-ops-fn! r owner)
    (wire-input! r owner)
    (set-mouse! r default-mx default-my owner)
    r))

(defn open-screen!
  ([owner] (open-screen! owner nil))
  ([owner learn-context]
   (let [^UiRt r (create-runtime owner :learn-context learn-context)]
     (bridge/open-reactive-screen! r "Node Tree"
       {:on-close #(do (untrack-active! owner)
                       (logic/close-screen! owner))}))))

(defn on-close!
  [owner]
  (untrack-active! owner)
  (logic/close-screen! owner))
