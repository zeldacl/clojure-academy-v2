(ns cn.li.ac.ability.client.screens.skill-tree-reactive
  "Reactive Skill Tree — native UiRt nodes via skill-tree-view."
  (:require [cn.li.ac.ability.client.screens.skill-tree :as logic]
            [cn.li.ac.ability.client.screens.skill-tree-view :as view]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mcmod.ui.node INode]))

(def ^:private panel-w 420.0)
(def ^:private panel-h 260.0)
(def ^:private default-mx 210.0)
(def ^:private default-my 130.0)

(def ^:private active-by-uuid (atom {}))

(defn- track-active! [owner ^UiRt r]
  (when-let [uuid (:player-uuid owner)]
    (swap! active-by-uuid assoc uuid {:rt r :owner owner})))

(defn- untrack-active! [owner]
  (when-let [uuid (:player-uuid owner)]
    (swap! active-by-uuid dissoc uuid)))

(defn- refresh! [^UiRt r owner]
  (let [mx-sig (rt/user-signal r :mouse-x)
        my-sig (rt/user-signal r :mouse-y)
        mx (if mx-sig (sig/sget-d mx-sig) default-mx)
        my (if my-sig (sig/sget-d my-sig) default-my)]
    (view/refresh-screen! r owner mx my)))

(defn refresh-active-screen! [player-uuid]
  (when-let [{:keys [rt owner]} (get @active-by-uuid player-uuid)]
    (refresh! rt owner)))

(defn- set-mouse! [^UiRt r mx my owner]
  (let [mx-sig (rt/user-signal r :mouse-x)
        my-sig (rt/user-signal r :mouse-y)]
    (when mx-sig (sig/sset-d! mx-sig (double mx)))
    (when my-sig (sig/sset-d! my-sig (double my)))
    (logic/on-mouse-move owner (int mx) (int my))
    (refresh! r owner)))

(defn- wire-input! [^UiRt r owner]
  (let [handle-click!
        (fn [mx my]
          (set-mouse! r mx my owner)
          (logic/handle-screen-click! owner (int mx) (int my))
          (refresh! r owner))
        handle-move!
        (fn [mx my]
          (set-mouse! r mx my owner))]
    (rt/put-user-signal! r :on-pointer-move handle-move!)
    (when-let [^INode layer (ui/node r :input-layer)]
      (let [idx (.getIdx layer)]
        (rt/register-event! r idx :left-click
          (fn [_ _ evt] (handle-click! (:x evt) (:y evt))))
        (rt/register-event! r idx :drag
          (fn [_ _ evt] (handle-move! (:x evt) (:y evt))))))))

(defn create-runtime
  [owner & {:keys [learn-context]}]
  (let [r (rt/create-runtime)]
    (logic/open-screen! owner learn-context)
    (view/ensure-shell! r (int panel-w) (int panel-h))
    (rt/put-user-signal! r :mouse-x (sig/signal-d default-mx))
    (rt/put-user-signal! r :mouse-y (sig/signal-d default-my))
    (track-active! owner r)
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

(defn on-close! [owner]
  (untrack-active! owner)
  (logic/close-screen! owner))

(defn create-detail-overlay-runtime [node]
  (let [r (rt/create-runtime)]
    (view/refresh-detail-overlay! r node)
    r))

(defn create-levelup-overlay-runtime [target-level dev-state]
  (let [r (rt/create-runtime)]
    (view/refresh-levelup-overlay! r target-level dev-state)
    r))
