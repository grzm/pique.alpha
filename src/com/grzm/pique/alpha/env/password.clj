(ns com.grzm.pique.alpha.env.password
  (:require
   [clojure.string :as str]))

(def field-separator #"(?<!\\):")

(defn parse-line
  [line]
  (let [[host port dbname user password :as entry] (str/split line
                                                              field-separator)]
    (when-not (<= 4 (count entry) 5)
      (throw (ex-info "invalid line" {:line line, :cause ::invalid-line})))
    {:host host, :port port, :dbname dbname, :user user, :password password}))

(defn parse-passwords
  [contents]
  (let [lines (->> (or contents
                       "")
                   str/split-lines
                   (filter seq))]
    (map parse-line lines)))

(def wildcard "*")

(defn matches-field?
  [entry conn-info field]
  (let [entry-field (field entry)]
    (or (= wildcard entry-field)
        (= entry-field (field conn-info)))))

;; in libpq, if port isn't supplied, it's set to DEF_PGPORT_STR, the
;; default port number configured during compilation. It would make
;; sense to replicate that behavior here.

(defn matches-entry?
  [conn-info entry]
  (when (every? (partial matches-field? entry conn-info)
                #{:host :port :dbname :user})
    entry))

(defn password
  [{:keys [dbname user], :as conn-info} entries]
  ;; libpq supplies "localhost" if host isn't provided, and the
  ;; configured default port number. Given 5432 is the widely used
  ;; default port number, it makes sense to do the same here.
  (when (and dbname
             user)
    (if-let [password (->> entries
                           (some
                             (partial matches-entry?
                                      (merge {:host "localhost", :port "5432"}
                                             conn-info)))
                           :password)]
      password
      (throw (ex-info "no matching entry"
                      {:entries   entries,
                       :conn-info conn-info,
                       :cause     ::no-matching-entry})))))
