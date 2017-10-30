(ns com.grzm.pique.alpha.env.environment
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [com.grzm.pique.alpha.env.password :as password])
  (:import
   (clojure.lang ExceptionInfo)))

(defprotocol EnvironmentReader
  (-system-env [er])
  ;; password
  (-env-passwords [er])
  (-user-passwords [er])
  ;; connection service
  (-env-service [er])
  (-user-service [er])
  (-env-sysconfdir-service [er])
  (-sysconfdir-service [er]))


(defn keywordize [v] (keyword (-> v (str/replace \_ \-) str/lower-case)))

(defn keywordize-keys [m] (into {} (map (fn [[k v]] [(keywordize k) v]) m)))

(def env-var-strings
  #{"PGHOST"
    "PGPORT"
    "PGDATABASE"
    "PGUSER"
    "PGPASSWORD"
    "PGAPPNAME"
    "PGCONNECT_TIMEOUT"
    ;; security
    "PGSSLMODE"
    "PGREQUIRESSL"
    "PGSSLCERT"
    "PGSSLKEY"
    "PGSSLROOTCERT"
    "PGKRBSRVNAME"
    "PGGSSLIB"
    ;; configuration lookups
    ;; password file
    "PGPASSFILE"
    ;; connection service file
    "PGSERVICE"
    "PGSERVICEFILE"
    ;; system config
    "PGSYSCONFDIR"
    ;; non-JDBC
    "PGHOSTADDR"
    "PGREALM"
    "PGOPTIONS"
    "PGSSLCOMPRESSION"
    "PGSSLCRL"
    "PGREQUIREPEER"
    ;; non-JDBC
    "PGCLIENTENCODING"
    ;; default session behavior
    "PGDATESTYLE"
    "PGTZ"
    "PGGEQO"
    ;; internal libpq behavior
    "PGLOCALEDIR"})

(defn system-env [er] (-system-env er))

(defn env-passwords [er] (-env-passwords er))

(defn user-passwords [er] (-user-passwords er))

(defn env-service [er] (-env-service er))

(defn user-service [er] (-user-service er))

(defn env-sysconfdir-service [er] (-env-sysconfdir-service er))

(defn sysconfdir-service [er] (-sysconfdir-service er))

(def env-var-conn-params
  {:pgappname         :application-name,
   :pgclientencoding  :client-encoding,
   ;; int string
   :pgconnect-timeout :connect-timeout,
   :pgdatabase        :dbname,
   :pggsslib          :gsslib,
   :pghost            :host,
   :pghostaddr        :hostaddr,
   :pgkrbsrvname      :krbsrvname,
   :pgoptions         :options,
   :pgpassword        :password,
   ;; int string
   :pgport            :port,
   :pgrequirepeer     :requirepeer,
   ;; 1/0 boolean
   :pgrequiressl      :requiressl,
   :pgsslcert         :sslcert,
   ;; 1/0 boolean
   :pgsslcompression  :sslcompression,
   :pgsslcrl          :sslcrl,
   :pgsslkey          :sslkey,
   :pgsslmode         :sslmode,
   :pgsslrootcert     :sslrootcert,
   :pguser            :user})

(def conn-params->params
  ;; this needs to be expanded
  {:application-name :application-name
   :client-encoding :client-encoding
   :dbname :database
   :host :host
   :password :password
   :port :port
   :user :user})

(def conn-params->jdbc
  {:host                      :host,
   :hostaddr                  nil,
   :port                      :port,
   :dbname                    :dbname,
   :client-encoding           nil,
   :options                   nil,
   :user                      :user,
   :password                  :password,
   ;; boolean
   :requiressl                :ssl,
   ;; verify-ca, verify-full
   :sslmode                   :sslmode,
   :sslcert                   :sslcert,
   :sslkey                    :sslkey,
   :sslcrl                    nil,
   :sslrootcert               :sslrootcert,
   ;; int
   :connect-timeout           :connectTimeout,
   :krbsrvname                :kerberosServerName,
   :application-name          :ApplicationName,
   :fallback-application-name nil,
   ;; 1/0 => boolean
   :keepalives                :tcpKeepAlive,
   :keepalives-idle           nil,
   :keepalives-interval       nil,
   :keepalives-count          nil,
   :tty                       nil,
   :requirepeer               nil,
   :gsslib                    :gsslib,
   :service                   nil})

(defn remove-nil [m] (into {} (remove (comp nil? second) m)))

(def param-conn-params (remove-nil conn-params->params))

(def jdbc-conn-params (remove-nil conn-params->jdbc))

(defn parse-system-env
  [env-map]
  (->> (select-keys env-map env-var-strings)
       (map (fn [[k v]] [(keywordize k) v]))
       (into {})))

(defn read-system-env [er] (parse-system-env (system-env er)))

;; what's libpq behavior when PGPASSFILE is set but there's no file there?
;; or it's unreadable? I think it just ignores it.

;; pgservice.conf

;; 1 pgservicefile
;; 2 user .pgservice.conf
;; 3 pgsysconfdir .pgservice.conf overrides SYSCONFDIR (pg_ctl)
;; 4 pg_ctl sysconfdir .pgservice.conf

(defn service
  [er]
  (let [env (read-system-env er)]
    (when-let [service (:pgservice env)]
      (if (:pgservicefile env)
        (env-service er)
        (if-let [options (user-service er)]
          options
          (if (:pgsysconfdir env)
            (env-sysconfdir-service er)
            (sysconfdir-service er)))))))

(defn parse-int
  [m k]
  (merge m (when-let [v (get m k)] {k (Integer/parseInt v)})))

(defn conn-info*
  [er]
  (-> (service er)
      (merge (set/rename-keys (read-system-env er) env-var-conn-params))
      (dissoc :pgservice)))

(defn passwords
  [er]
  (if-let [filename (:pgpassfile (read-system-env er))]
    (password/parse-passwords (env-passwords er))
    (password/parse-passwords (user-passwords er))))

;; if PGPASSWORD is set, don't read password file

(defn password
  [er]
  (let [env (read-system-env er)]
    (if-let [password (:pgpassword env)]
      password
      (let [conn-info (select-keys (conn-info* er)
                                   #{:pgpassfile :dbname :user :host :port})]
        (try
          (password/password conn-info (passwords er))
          (catch ExceptionInfo e
            (when (= ::password/no-matching-entry (:cause (ex-data e)))
              (log/tracef "No matching entry found: %s"
                          (pr-str (into (sorted-map) conn-info))))))))))

(defn conn-info
  "conn-info, including password."
  [er]
  (-> (merge (conn-info* er)
             (when-let [pw (password er)]
               {:password pw}))
      (dissoc :pgpassword :pgpassfile)
      (parse-int :keepalives)
      (parse-int :requiressl)
      (parse-int :sslcompression)
      (parse-int :port)
      (parse-int :connect-timeout)))

(defn booleanize-int [m k] (merge m (when-let [v (get m k)] {k (= 1 v)})))

(defn params
  "libpq params"
  [er]
  (-> (conn-info er)
      (select-keys (keys param-conn-params))
      (set/rename-keys param-conn-params)
      (booleanize-int :tcpKeepAlive)
      (booleanize-int :ssl)))

(defn jdbc-spec
  "Clojure JDBC-compatible spec"
  [er]
  (-> (conn-info er)
      (select-keys (keys jdbc-conn-params))
      (set/rename-keys jdbc-conn-params)
      (booleanize-int :tcpKeepAlive)
      (booleanize-int :ssl)
      (assoc :dbtype "postgresql")))
