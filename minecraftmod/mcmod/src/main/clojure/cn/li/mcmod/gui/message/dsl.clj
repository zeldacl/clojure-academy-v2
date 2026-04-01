(ns cn.li.mcmod.gui.message.dsl
  "Message DSL helpers shared by AC wireless GUI."
  (:require [clojure.string :as str]))

(defn- action->token
  [action]
  (-> (name action)
      (str/replace "-" "_")))

(defn message-id
  "Build message id with a stable prefix.

  Current format: wireless_<domain>_<action>."
  [domain action]
  (str "wireless_" (name domain) "_" (action->token action)))

(defn build-domain-spec
  "Build message spec for a domain and validate duplicate actions early."
  [domain actions]
  (let [dupes (->> actions frequencies (keep (fn [[k n]] (when (> n 1) k))))]
    (when (seq dupes)
      (throw (ex-info "Duplicate actions in domain"
                      {:domain domain :duplicate-actions (vec dupes)})))
    {:domain domain
     :messages (into {}
                     (map (fn [action]
                            [action (message-id domain action)]))
                     actions)
     :specs (mapv (fn [action]
                    {:domain domain
                     :action action
                     :msg-id (message-id domain action)})
                  actions)}))

(defn build-catalog
  "Merge domain specs and validate global message-id uniqueness and format."
  [domain-specs]
  (let [all-specs (vec (mapcat :specs domain-specs))
        by-id (group-by :msg-id all-specs)
        dup-ids (->> by-id
                     (keep (fn [[msg-id entries]]
                             (when (> (count entries) 1)
                               msg-id))))
        invalid-ids (->> all-specs
                         (remove (fn [{:keys [msg-id]}]
                                   (boolean (re-matches #"wireless_[a-z0-9_]+_[a-z0-9_]+" msg-id))))
                         (mapv :msg-id))]
    (when (seq dup-ids)
      (throw (ex-info "Duplicate wireless message ids"
                      {:duplicate-msg-ids (vec dup-ids)})))
    (when (seq invalid-ids)
      (throw (ex-info "Invalid wireless message ids"
                      {:invalid-msg-ids invalid-ids})))
    {:domains (into {}
                    (map (fn [domain-spec]
                           [(:domain domain-spec) (:messages domain-spec)]))
                    domain-specs)
     :specs all-specs
     :by-msg-id (into {}
                      (map (fn [spec]
                             [(:msg-id spec) (select-keys spec [:domain :action])]))
                      all-specs)}))

(defn msg-id
  [catalog domain action]
  (or (get-in catalog [:domains domain action])
      (throw (ex-info "Unknown wireless message"
                      {:domain domain :action action}))))

(defn find-by-msg-id
  [catalog msg-id]
  (get-in catalog [:by-msg-id msg-id]))
