(ns com.grzm.pique.alpha.env.service
  (:require
   [clojure.string :as str]
   [com.grzm.pique.alpha.env.environment :as environment]))

(def keywords
  #{"application_name"
    "client_encoding"
    "connect_timeout"
    "dbname"
    "fallback_application_name"
    "gsslib"
    "host"
    "hostaddr"
    "keepalives"
    "keepalives_idle"
    "keepalives_interval"
    "krbsrvname"
    "options"
    "password"
    "port"
    "requirepeer"
    "requiressl"
    "service"
    "sslcert"
    "sslcompression"
    "sslcrl"
    "sslkey"
    "sslmode"
    "sslrootcert"
    "tty"
    "user"})

(def service-keyword "service")

(defn group-line?
  [line]
  (and (.startsWith line "[")
       (.endsWith line "]")))

(defn parse-group-line
  "group line starts with \\[ and ends with \\]"
  [line]
  (when (group-line? line)
    (subs line 1 (dec (count line)))))

(defn parse-option-line
  "doesn't trim whitespace around \\="
  [line]
  (let [n (.indexOf line "=")]
    (when (pos? n)
      (let [kw (subs line 0 n)
            val (subs line (inc n))]
        (when-not (contains? keywords kw)
          (throw (ex-info "syntax error"
                          {:cause ::invalid-keyword, :line line})))
        (when (= kw service-keyword)
          (throw (ex-info "nested service specifications not supported"
                          {:cause ::nested-service-specification, :line line})))
        [kw val]))))

(defn comment-line? [line] (.startsWith line "#"))

(defn parse-comment-line
  [line]
  (when (comment-line? line)
    ::comment))

(defn parse-empty-line
  [line]
  (when (= "" line)
    ::empty))

(defn parse-line*
  [line]
  ((some-fn parse-empty-line
            parse-comment-line
            parse-group-line
            parse-option-line)
   line))

(defn parse-line
  "ignores leading whitespace"
  [raw-line]
  (let [line (str/triml raw-line)]
    (if-let [parsed (parse-line* line)]
      parsed
      (throw (ex-info "can't parse" {:cause ::syntax-error, :line raw-line})))))

(defn assoc-once [m [k v]] (merge {k v} m))

(defn find-service
  [service rdr]
  (let [[service & lines] (->> (line-seq rdr)
                               (map parse-line)
                               (drop-while #(not= service %)))]
    (when service
      (->> lines
           (take-while (complement string?))
           (remove keyword?)
           (reduce assoc-once {})
           environment/keywordize-keys))))
