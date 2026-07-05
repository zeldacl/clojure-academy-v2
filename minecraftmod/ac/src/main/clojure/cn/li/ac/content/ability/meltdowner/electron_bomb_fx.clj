(ns cn.li.ac.content.ability.meltdowner.electron-bomb-fx
  (:require [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]))

(def ^:private spec
  (arc-beam/build-spec
    {:effect-id :electron-bomb
     :initial-state (fn [] {:effect-state {} :beams {}})
     :channels {:spawn {:topic :electron-bomb/fx-spawn :mode :spawn
											 :level-payload (fn [_ _ p]
																				{:x (:x p) :y (:y p) :z (:z p)
																				 :dx (:dx p) :dy (:dy p) :dz (:dz p)})}
								:beam {:topic :electron-bomb/fx-beam :mode :beam
											 :level-payload (fn [_ _ p]
																				{:start (:start p) :end (:end p)})}
								:end {:topic :electron-bomb/fx-end :mode :end}}}))

(defn init! [] (fx-spec/register! spec) nil)

(defn electron-bomb-fx-snapshot [] (arc-beam/snapshot :electron-bomb))

(defn reset-electron-bomb-fx-for-test! [] (arc-beam/reset-for-test! :electron-bomb) nil)

(defn clear-electron-bomb-owner! [owner-key] (arc-beam/clear-owner! :electron-bomb owner-key) nil)