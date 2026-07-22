(ns cn.li.mcmod.platform.item
  "Item operations via Framework function map — pure relay layer, no MC dependencies."
  (:refer-clojure :exclude [empty?])
  (:require [cn.li.mcmod.framework :as fw]))

(def item-ops-keys
  #{:item-is-empty? :item-get-count :item-get-max-stack-size :item-is-equal?
    :item-save-to-nbt :item-get-or-create-tag :item-get-max-damage
    :item-set-damage! :item-get-damage :item-get-item :item-get-tag-compound :item-split
    :item-get-description-id :item-get-registry-name
    :create-item-from-nbt :create-item-stack-by-id :item-stack-empty?})

(defn install-item-ops!
  [ops-map _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :item-ops] ops-map)) nil)

(defn available? [] (boolean (get-in @(fw/fw-atom) [:platform :item-ops])))
(defn current-ops         [] (get-in @(fw/fw-atom) [:platform :item-ops]))

(defn- call [k & args] (when-let [f (get (current-ops) k)] (apply f args)))

;; ItemStack access API
(defn empty?            [stack]        (call :item-is-empty? stack))
(defn stack-count       [stack]        (call :item-get-count stack))
(defn max-stack-size    [stack]        (call :item-get-max-stack-size stack))
(defn same?             [stack other]  (call :item-is-equal? stack other))
(defn save-to-nbt       [stack nbt]    (call :item-save-to-nbt stack nbt))
(defn get-or-create-tag [stack]        (call :item-get-or-create-tag stack))
(defn max-damage        [stack]        (call :item-get-max-damage stack))
(defn set-damage!       [stack dmg]    (call :item-set-damage! stack dmg))
(defn damage            [stack]        (call :item-get-damage stack))
(defn object            [stack]        (call :item-get-item stack))
(defn tag-compound      [stack]        (call :item-get-tag-compound stack))
(defn split             [stack amount] (call :item-split stack amount))

;; Item access API
(defn description-id [item] (call :item-get-description-id item))
(defn registry-name  [item] (call :item-get-registry-name item))

;; Factory access API
(defn from-nbt [nbt-tag]
  (if-let [f (get (current-ops) :create-item-from-nbt)]
    (f nbt-tag)
    (throw (ex-info "Item ops not installed" {:key :create-item-from-nbt}))))
(defn stack-by-id [item-id count]
  (if-let [f (get (current-ops) :create-item-stack-by-id)]
    (f item-id count)
    (throw (ex-info "Item ops not installed" {:key :create-item-stack-by-id}))))
;; Tag helpers
(defn in-tag? [stack tag-str]
  (if-let [f (get (current-ops) :item-tag-checker)]
    (f stack tag-str)
    false))
(defn stack-from-tag [tag-str count]
  (if-let [f (get (current-ops) :tag-item-resolver)]
    (f tag-str count)
    nil))

(defn stack-empty? [stack]
  (if-let [f (get (current-ops) :item-stack-empty?)]
    (f stack)
    (throw (ex-info "Item ops not installed" {:key :item-stack-empty?}))))
