(ns com.grzm.pique.alpha.env.system-environment
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [com.grzm.pique.alpha.env.environment :as environment]
   [com.grzm.pique.alpha.env.service :as service])
  (:import
   (clojure.lang ExceptionInfo)
   (java.io FileNotFoundException)
   (java.lang IllegalArgumentException)
   (java.nio.file Files Paths LinkOption FileSystems NoSuchFileException)
   (java.nio.file.attribute PosixFilePermissions PosixFilePermission)))

(defprotocol Env
  (-getenv [e]))

(defn getenv [e] (-getenv e))

(defrecord SystemEnv []
  Env
  (-getenv [se] (System/getenv)))


(defprotocol SysConfDir
  (-sysconfdir [scd]))

(defn sysconfdir [scd] (-sysconfdir scd))

(defrecord PgConfigSysConfDir []
  SysConfDir
  (-sysconfdir [scd]
    (let [{:keys [exit out err] :as ret} (shell/sh "pg_config" "--sysconfdir")]
      (if (zero? exit)
        (str/trim out)
        (log/debug (format "error running pg_config: %s" (pr-str ret)))))))


(defprotocol UserDir
  (-user-dir [ud])
  (-password-filename [ud])
  (-pg-config-sysconfdir [ud]))

(defn password-file [ud] (io/file (-user-dir ud) (-password-filename ud)))

(defn service-file [ud] (io/file (-user-dir ud) ".pg_service.conf"))


(defrecord AppData [app-data]
  UserDir
  (-user-dir [ud] (io/file app-data "postgresql"))
  (-password-filename [ud] "pgpass.conf"))

(defrecord UserHome [user-home]
  UserDir
  (-user-dir [ud] (io/file user-home))
  (-password-filename [ud] ".pgpass"))


(defn default-service-file [dir] (io/file dir "pg_service.conf"))

(defn user-service-file [{:keys [user-dir]}] (service-file user-dir))

(defn env-service-file
  [er]
  (-> (environment/read-system-env er)
      :pgservicefile
      io/file))

(defn env-sysconfdir-service-file
  [er]
  (-> (environment/read-system-env er)
      :pgsysconfdir
      default-service-file))

(defn sysconfdir-service-file
  [{sysconf :sysconfdir}]
  (some-> (sysconfdir sysconf)
          default-service-file))

(defn read-service-file*
  [service file]
  (try
    (with-open [rdr (io/reader file)]
      (service/find-service service rdr))
    (catch FileNotFoundException e
      (throw (ex-info (format "service file not found \"%s\"" (str file))
                      {:cause ::service-file-not-found, :file file}
                      e)))))

(defn read-service-file
  [er file-fn]
  (when-let [service (:pgservice (environment/read-system-env er))]
    (when-let [file (file-fn er)]
      (try
        (if-let [definition (read-service-file* service file)]
          definition
          (log/warnf "definition of service \"%s\" not found" service))
        (catch ExceptionInfo e
          (let [{:keys [cause file]} (ex-data e)]
            (if (= ::service-file-not-found cause)
              (log/tracef "service file \"%s\" not found" file)
              (throw e))))))))

(defn posix?
  []
  (let [views (.supportedFileAttributeViews (FileSystems/getDefault))]
    (contains? views "posix")))

(defn get-file
  [filename]
  (let [f (io/file filename)] (when (.exists f) (slurp f))))

(defn get-perms
  [file]
  (let [path (.toPath file)]
    (Files/getPosixFilePermissions path (LinkOption/values))))

(defn valid-permissions?*
  [perms]
  (and (contains? perms PosixFilePermission/OWNER_READ)
       (empty? (set/difference (set perms)
                               #{PosixFilePermission/OWNER_READ
                                 PosixFilePermission/OWNER_WRITE}))))

(defn valid-permissions?
  "PostgreSQL likes to keep things tight. At least on POSIX."
  ([filename] (valid-permissions? filename (posix?)))
  ([filename posix?]
   (if posix?
     (valid-permissions?* (get-perms filename))
     true)))

(defn read-password-file
  [file]
  (try
    (if (valid-permissions? file)
      (get-file file)
      (log/warnf (str "password file \"%s\" has group or world access; "
                      "permissions should be u=rw (0600) or less")
                 (str file)))
    (catch NoSuchFileException _
      (log/tracef "password file \"%s\" not found" (str file)))))

(defrecord SystemEnvironment [user-dir sysconfdir env]
  environment/EnvironmentReader
  (-system-env [{:keys [env]}] (getenv env))
  (-env-passwords [er]
    (let [password-file (:pgpassfile (environment/read-system-env er))]
      (read-password-file (io/file password-file))))

  (-user-passwords [{:keys [user-dir]}]
    (read-password-file (password-file user-dir)))

  (-env-service [er] (read-service-file er env-service-file))
  (-user-service [er] (read-service-file er user-service-file))
  (-env-sysconfdir-service [er]
    (read-service-file er env-sysconfdir-service-file))
  (-sysconfdir-service [er] (read-service-file er sysconfdir-service-file)))

(defn system-environment
  []
  ;; XXX The method used here to determine whether to use user.home or
  ;; APPDATA is likely a hack.  It's at the very least
  ;; untested. There's likely a better way to determine whether we're
  ;; on windows.
  (let [user-dir   (if (posix?)
                     (->UserHome (System/getProperty "user.home"))
                     (->AppData (System/getenv "APPDATA")))
        sysconfdir (->PgConfigSysConfDir)
        env        (->SystemEnv)]
    (map->SystemEnvironment {:user-dir   user-dir
                             :sysconfdir sysconfdir
                             :env        env})))
