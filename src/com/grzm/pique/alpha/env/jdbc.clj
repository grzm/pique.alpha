(ns com.grzm.pique.alpha.env.jdbc
  (:require
   [com.grzm.pique.alpha.env.environment :as environment]
   [com.grzm.pique.alpha.env.system-environment :as sys-env]))

(def sys-env (sys-env/system-environment))

(def spec-cache (atom nil))

(defn spec
  ([]
   (if-let [cached @spec-cache]
     cached
     (spec true)))
  ([force-read?]
   (if force-read?
     (reset! spec-cache (environment/jdbc-spec sys-env))
     @spec-cache)))
