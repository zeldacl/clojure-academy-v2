(ns cn.li.ac.registry.core
  "Core contracts for AC content discovery.")

(defprotocol IContentProvider
  (provider-id [this]
    "Stable unique id for the provider.")
  (priority [this]
    "Sort priority; lower values load first.")
  (content-phases [this]
    "Return a vector of content phase specs contributed by this provider."))

(defrecord ContentProvider [id priority-value phases]
  IContentProvider
  (provider-id [_this] id)
  (priority [_this] priority-value)
  (content-phases [_this] (vec phases)))

(defn content-provider
  "Create a content provider record from plain data."
  [{:keys [id phases priority] :or {priority 100}}]
  {:pre [(some? id) (sequential? phases)]}
  (->ContentProvider id priority phases))

(defn content-provider?
  [value]
  (satisfies? IContentProvider value))

(defn normalize-provider
  [value]
  (cond
    (content-provider? value) value
    (map? value) (content-provider value)
    :else (throw (ex-info "Unsupported content provider" {:value value}))))

(defn provider-id*
  "Safe provider id accessor.

  Prefers protocol dispatch, but falls back to map fields for resilience
  under mixed incremental compilation/classloader states."
  [provider]
  (or (try
        (provider-id provider)
        (catch Throwable _ nil))
      (:id provider)
      (throw (ex-info "Provider has no id" {:provider provider}))))

(defn provider-priority*
  "Safe provider priority accessor with fallback default 100."
  [provider]
  (or (try
        (priority provider)
        (catch Throwable _ nil))
      (:priority-value provider)
      (:priority provider)
      100))

(defn provider-phases*
  "Safe provider phases accessor. Always returns a vector."
  [provider]
  (let [phases (or (try
                     (content-phases provider)
                     (catch Throwable _ nil))
                   (:phases provider)
                   [])]
    (vec phases)))
