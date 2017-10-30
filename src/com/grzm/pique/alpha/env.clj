(ns com.grzm.pique.alpha.env
  (:require
   [com.grzm.pique.alpha.env.environment :as environment]
   [com.grzm.pique.alpha.env.system-environment :as sys-env]))

(def sys-env (sys-env/system-environment))

(def spec-cache (atom nil))

(defn jdbc-spec
  ([]
   (if-let [cached @spec-cache]
     cached
     (jdbc-spec true)))
  ([force-read?]
   (if force-read?
     (reset! spec-cache (environment/jdbc-spec sys-env))
     @spec-cache)))


(def params-cache (atom nil))

(defn params
  ([]
   (if-let [cached @params-cache]
     cached
     (params true)))
  ([force-read?]
   (if force-read?
     (reset! params-cache (environment/params sys-env))
     @params-cache)))
