(ns cn.li.mcmod.platform.item
  "Item operations via Framework function map — pure relay layer, no MC dependencies."
  (:require [cn.li.mcmod.framework :as fw]))

(def item-ops-keys
  #{:item-is-empty? :item-get-count :item-get-max-stack-size :item-is-equal?
    :item-save-to-nbt :item-get-or-create-tag :item-get-max-damage
    :item-set-damage! :item-get-damage :item-get-item :item-get-tag-compound :item-split
    :item-get-description-id :item-get-registry-name
    :create-item-from-nbt :create-item-stack-by-id :item-stack-empty? :item-registry-name})

(defn install-item-ops!
  [ops-map _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :item-ops] ops-map)) nil)

(defn item-ops-available? [] (boolean (get-in @(fw/fw-atom) [:platform :item-ops])))
(defn factory-initialized? [] (item-ops-available?))  ;; backward-compatible alias
(defn current-ops         [] (get-in @(fw/fw-atom) [:platform :item-ops]))

(defn- call [k & args] (when-let [f (get (current-ops) k)] (apply f args)))

;; ItemStack wrappers
(defn item-is-empty?          [stack]        (call :item-is-empty? stack))
(defn item-get-count          [stack]        (call :item-get-count stack))
(defn item-get-max-stack-size [stack]        (call :item-get-max-stack-size stack))
(defn item-is-equal?          [stack other]  (call :item-is-equal? stack other))
(defn item-save-to-nbt        [stack nbt]    (call :item-save-to-nbt stack nbt))
(defn item-get-or-create-tag  [stack]        (call :item-get-or-create-tag stack))
(defn item-get-max-damage     [stack]        (call :item-get-max-damage stack))
(defn item-set-damage!        [stack dmg]    (call :item-set-damage! stack dmg))
(defn item-get-damage         [stack]        (call :item-get-damage stack))
(defn item-get-item           [stack]        (call :item-get-item stack))
(defn item-get-tag-compound   [stack]        (call :item-get-tag-compound stack))
(defn item-split              [stack amount] (call :item-split stack amount))

;; Item wrappers
(defn item-get-description-id [item] (call :item-get-description-id item))
(defn item-get-registry-name  [item] (call :item-get-registry-name item))

;; Factory wrappers
(defn create-item-from-nbt [nbt-tag]
  (if-let [f (get (current-ops) :create-item-from-nbt)]
    (f nbt-tag)
    (throw (ex-info "Item ops not installed" {:key :create-item-from-nbt}))))
(defn create-item-stack-by-id [item-id count]
  (if-let [f (get (current-ops) :create-item-stack-by-id)]
    (f item-id count)
    (throw (ex-info "Item ops not installed" {:key :create-item-stack-by-id}))))
;; Backward-compatible aliases
(defn item-is-in-tag? [stack tag-str]
  (if-let [f (get (current-ops) :item-tag-checker)]
    (f stack tag-str)
    false))
(defn create-item-stack-from-tag [tag-str count]
  (if-let [f (get (current-ops) :tag-item-resolver)]
    (f tag-str count)
    nil))

(defn item-stack-empty? [stack]
  (if-let [f (get (current-ops) :item-stack-empty?)]
    (f stack)
    (throw (ex-info "Item ops not installed" {:key :item-stack-empty?}))))
