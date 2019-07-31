(ns sweet-tooth.endpoint.liberator.reitit-routes
  "Module for initializing routes created by
  sweet-tooth.endpoint.routes.reitit and associng in the reitit router
  as the app's :duct/router

  This ns contains DEEP FORBIDDEN MAGIC and is maybe a TERRIBLE IDEA

  - Module adds a ::router config key, taking route data as an arg
  - route data is sprinkled with helpful sweet-tooth goodies
  - route data includes refs to handler keys
  - handler keys are added to the config
  - handler keys are derived from ::unary-handler and ::coll-handler to 
    provide MAGICAL defaults"
  (:require [duct.core :as duct]
            [integrant.core :as ig]
            [reitit.ring :as rr]
            [com.flyingmachine.liberator-unbound :as lu]
            
            [sweet-tooth.endpoint.liberator :as el]
            [sweet-tooth.endpoint.middleware :as em]
            [sweet-tooth.endpoint.routes.reitit :as err]
            [clojure.string :as str]))

(defn ns-route?
  "differentiate 'normal' routes from those generated by
  ns-pair->ns-route"
  [route]
  (get-in route [1 ::err/ns]))

;;-----------
;; duct
;;-----------
(defn liberator-resources
  "Return both entry and collection request handlers"
  [endpoint-opts]
  (let [endpoint-ns (::err/ns endpoint-opts)
        decisions   (try (-> (ns-resolve (symbol endpoint-ns) 'decisions)
                             deref
                             (el/initialize-decisions (assoc (:ctx endpoint-opts)
                                                             :sweet-tooth.endpoint/namspace endpoint-ns)))
                         (catch Throwable t (throw (ex-info "could not find 'decisions in namespace" {:ns (::err/ns endpoint-opts)}))))]
    (->> decisions
         (lu/merge-decisions el/decision-defaults)
         (lu/resources lu/resource-groups))))

(defmethod ig/init-key ::unary-handler [_ endpoint-opts]
  (:entry (liberator-resources endpoint-opts)))
(defmethod ig/init-key ::coll-handler [_ endpoint-opts]
  (:collection (liberator-resources endpoint-opts)))

(defn endpoint-handler-key
  [endpoint-opts]
  (let [ns   (::err/ns endpoint-opts)
        type (::err/type endpoint-opts)]
    (keyword (name ns) (case type
                         ::err/unary "unary-handler"
                         ::err/coll  "coll-handler"))))

(defn add-handler-ref
  "Adds an integrant ref to an ns-route for `:handler` so that the
  handler can be initialized by the backend"
  [ns-route]
  (let [{:keys [sweet-tooth.endpoint.routes.reitit/ns handler]} (get ns-route 1)]
    (cond-> ns-route
      (and (not handler) ns) (assoc-in [1 :handler] (-> ns-route
                                                        (get 1)
                                                        endpoint-handler-key
                                                        ig/ref)))))

(defn add-ent-type
  [[_ endpoint-opts :as route]]
  (if (ns-route? route)
    (update-in route [1 :ent-type] #(or % (-> endpoint-opts
                                              ::err/ns
                                              name
                                              (str/replace #".*\.(?=[^.]+$)" "")
                                              keyword)))
    route))

(defn add-id-keys
  [ns-route]
  (if (ns-route? ns-route)
    (let [[_ {:keys [id-key auth-id-key]
              :or   {id-key      :id
                     auth-id-key :id}
              :as   route-data}] ns-route
          id-keys                {:id-key      id-key
                                  :auth-id-key auth-id-key}]
      (assoc ns-route 1 (-> route-data
                            (merge id-keys)
                            (update :ctx (fn [ctx] (merge id-keys ctx))))))
    ns-route))

(defn format-middleware-fn
  [[_ endpoint-opts]]
  (fn format-middleware [f]
    (fn [req]
      (assoc (f req) :sweet-tooth.endpoint/format (select-keys endpoint-opts [:id-key :auth-id-key :ent-type])))))

(defn add-middleware
  "Middleware is added to reitit in order to work on the request map
  that reitit produces before that request map is passed to the
  handler"
  [ns-route]
  (update ns-route 1 assoc :middleware [(format-middleware-fn ns-route) em/wrap-merge-params]))

(defn magic-the-f-out-of-this-route-data
  "I'M SORRY

  TODO come up with a better name"
  [route-data]
  (-> route-data
      add-id-keys
      add-ent-type
      add-handler-ref
      add-middleware))

(defn magic-the-handler
  [route-data]
  (-> route-data
      add-id-keys
      add-ent-type))

(defn add-ns-route-config
  [ns-route-config [_ ns-route-opts]]
  (cond-> ns-route-config
    (::err/ns ns-route-opts) (assoc (endpoint-handler-key ns-route-opts) ns-route-opts)))

(defmethod ig/init-key ::ns-routes [_ {:keys [ns-routes]}]
  (let [ns-routes (cond (vector? ns-routes) ns-routes

                        (or (keyword? ns-routes)
                            (symbol? ns-routes))
                        (do (require (symbol (namespace ns-routes)))
                            @(ns-resolve (symbol (namespace ns-routes))
                                         (symbol (name ns-routes)))))]
    
    (fn [config]
      ;; Have each endpoint handler's integrant key drive from a
      ;; default key
      (doseq [endpoint-opts (->> ns-routes
                                 (filter ns-route?)
                                 (map #(get % 1)))]
        (derive (endpoint-handler-key endpoint-opts)
                (case (::err/type endpoint-opts)
                  ::err/unary ::unary-handler
                  ::err/coll  ::coll-handler)))

      (-> config
          (duct/merge-configs
            {::router (mapv magic-the-f-out-of-this-route-data ns-routes)}
            (->> ns-routes
                 (mapv magic-the-handler)
                 (filter ns-route?)
                 (reduce add-ns-route-config {})))
          (dissoc :duct.router/cascading)))))

(defmethod ig/init-key ::router [_ routes]
  (rr/ring-handler (rr/router routes)))
