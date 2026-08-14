(ns cn.li.presentation.compiler.reload
  "Hot reload state. A failed compile never replaces the last valid template."
  (:require [cn.li.presentation.compiler.core :as ui]
            [cn.li.presentation.compiler.fx :as fx]))

(defn create []
  {:templates (atom {})
   :errors (atom [])
   :generation (atom 0)})

(defn reload! [runtime template-id kind text symbols]
  (try
    (let [template (if (= kind :ui)
                     (ui/compile-edn template-id text symbols)
                     (if (= kind :fx) (fx/compile-edn text)
                         (throw (ex-info "unknown template kind" {:kind kind}))))
          generation (swap! (:generation runtime) inc)]
      (swap! (:templates runtime) assoc template-id
             {:generation generation :template template})
      {:ok? true :generation generation :template template})
    (catch Exception exception
      (let [error {:template-id template-id
                   :message (.getMessage exception)
                   :cause exception}]
        (swap! (:errors runtime) conj error)
        {:ok? false
         :template (get-in @(:templates runtime) [template-id :template])
         :error error}))))

(defn current [runtime template-id]
  (get-in @(:templates runtime) [template-id :template]))

(defn errors [runtime]
  @(:errors runtime))
