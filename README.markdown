# pique

Pique (pēk) is a Clojure library to read the system environment for
PostgreSQL parameters in the same manner as libpq.

**NOTE: This is alpha. The little API it has is not stable.**

## Releases and Dependency Information

Releases are on [Clojars](https://clojars.org/com.grzm/pique.alpha).

### Clojure [CLI/deps.edn][deps] coordinates:

```clojure
{com.grzm/pique.alpha {:mvn/version "0.1.6"}}
```

### [Leiningen][]/[Boot][] dependency information:

```clojure
[com.grzm/pique.alpha "0.1.6"]
```

### [Maven] dependency information:

```xml
<dependency>
  <groupId>com.grzm</groupId>
  <artifactId>pique.alpha</artifactId>
  <version>0.1.6</version>
</dependency>
```

[deps]: https://clojure.org/reference/deps_and_cli
[Leiningen]: http://leiningen.org/
[Boot]: http://boot-clj.com
[Maven]: http://maven.apache.org/

## Usage

Easily use your PostgreSQL environment variables, service files, and `.pgpass`
with `clojure.java.jdbc`:

```clojure
(require
  '[clojure.java.jdbc :as jdbc]
  '[com.grzm.pique.jdbc :as env])

;; PGDATABASE, PGUSER set in environment

(env/spec)
;; => {:port 5496, :dbname "pique", :user "grzm", :dbtype "postgresql"}

(jdbc/query (env/spec) "SELECT 'Oh, so pleasant!' AS life_with_pique")
;; => ({:life_with_pique "Oh, so pleasant!!"})
```

`clojure.java.jdbc` doesn't use all of the parameters that are
utilized by libpq (and renames some of the canonical libpq parameter
names, and aren't exposed by `jdbc/spec`. You can access *all* libpq
parameters that have been defined in the environment as well:

```clojure
(require '[com.grzm.pique.env :as env])

(env/params)
;; => {:database "pique", :port 5496, :user "grzm"}
```

## Details

Pique reads the same [environment variables][libpq-envars] and
[connection service][libpq-pgservice] and
[password files][libpq-pgpass] used by `libpq`. This includes common
enviroment variables such as `PGDATABASE` and `PGUSER`, and also
`PGSERVICEFILE` and `PGPASSFILE`. Connection service files (e.g.,
`~/.pg_service.conf`) and password files (e.g., `~/.pgpass`) are also
read, just like `libpq` clients such as `psql`.

[libpq-envars]: https://www.postgresql.org/docs/current/static/libpq-envars.html
[libpq-pgservice]: https://www.postgresql.org/docs/current/static/libpq-pgservice.html
[libpq-pgpass]: https://www.postgresql.org/docs/current/static/libpq-pgpass.html

Not all `libpq` connection parameters make sense for JDBC, but those
that do are used to create the connection spec.

## License

© 2017 Michael Glaesemann

Released under the MIT License. See LICENSE for details.
