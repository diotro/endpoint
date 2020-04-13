(ns sweet-tooth.endpoint.validate-config
  (:require [duct.core :as duct]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(s/def ::raw-config any?)
(s/def ::actual-config any?)
(s/def ::level #{:warn :error})
(s/def ::msg string?)

(s/def ::key-validation (s/keys :req-un [::raw-config
                                         ::actual-config
                                         ::level
                                         ::errors]
                                :opt-un [::msg]))

(s/def ::key-validations (s/nilable (s/coll-of ::key-valdation)))

(defn read-config
  "Reads the config leaving duct/env values intact in order to report
  the original config"
  ([source]
   (read-config source {}))
  ([source readers]
   (duct/read-config source (merge {'duct/env #(into ['env-var] %)}
                                   readers))))

(defmulti validate-config-key
  ""
  (fn [k env _config] [k env]))

(defmethod validate-config-key :default [_ _ _] nil)

(defn validation-errors
  [{:keys [duct.core/environment] :as config} raw-config]
  (reduce-kv (fn [errors component-key component-config]
               (or (some->> (validate-config-key component-key
                                                 environment
                                                 component-config)
                            (merge {:key           component-key
                                    :raw-config    (component-key raw-config)
                                    :actual-config component-config})
                            (conj errors))
                   errors))
             []
             config))

;;------
;; env vars
;;------



;;------
;; reporting validation
;;------

(defmulti msg (fn [error] (:component-key error)))

(defn default-msg
  [{:keys [msg error raw-config actual-config component-key]}]
  (or msg
      (format (str "Configuration error for %s:\n"
                   "- configured with: %s\n"
                   "- received config: %s\n"
                   "- error: %s\n")
              component-key
              raw-config
              actual-config
              error)))

(defmethod msg :default
  [error]
  (default-msg error))

(defn report
  "A string describing the errors"
  [errors]
  (let [sorted-errors (sort-by :component-key errors)]
    (format (str "Misconfigured components:\n"
                 "%s\n\n"
                 "Component configuration errors:\n"
                 "%s\n")
            (->> sorted-errors
                 (map #(str "- " (:component-key %)))
                 (str/join "\n"))
            (->> sorted-errors
                 (map msg)
                 (str/join "\n")))))
