(ns cn.li.ac.ability.server.service.category-runtime
	"Runtime-side category mutations.

	Category switches are wider than an ability-data field update: they must clear
	preset slots and publish category lifecycle events so hooks can abort contexts
	and refresh derived player state."
	(:require [cn.li.ac.ability.model.ability :as adata]
						[cn.li.ac.ability.model.preset :as preset]
						[cn.li.ac.ability.registry.event :as evt]
						[cn.li.ac.ability.service.player-state :as ps]))

(defn change-category!
	"Apply a category change, clear preset slots, and fire the category-change event.

	The optional `ability-update-fn` can extend the mutation with related ability
	changes such as a forced level adjustment."
	([uuid new-category]
	 (change-category! uuid new-category #(adata/set-category % new-category)))
	([uuid new-category ability-update-fn]
	 (let [state (ps/get-or-create-player-state! uuid)
				 old-category (get-in state [:ability-data :category-id])]
		 (when (not= old-category new-category)
			 (let [updated-ability (ability-update-fn (:ability-data state))]
				 (ps/update-player-state!
					 uuid
					 (fn [player-state]
						 (-> player-state
								 (assoc :ability-data updated-ability)
								 (update :preset-data preset/clear-slots))))
				 (evt/fire-ability-event!
					 (evt/make-category-change-event uuid old-category new-category))
				 {:ability-data updated-ability
					:old-category old-category
					:new-category new-category})))))