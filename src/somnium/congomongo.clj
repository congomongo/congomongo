; Copyright (c) 2009-2012 Andrew Boekhoff, Sean Corfield

; Permission is hereby granted, free of charge, to any person obtaining a copy
; of this software and associated documentation files (the "Software"), to deal
; in the Software without restriction, including without limitation the rights
; to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
; copies of the Software, and to permit persons to whom the Software is
; furnished to do so, subject to the following conditions:

; The above copyright notice and this permission notice shall be included in
; all copies or substantial portions of the Software.

; THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
; IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
; FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
; AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
; LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
; OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
; THE SOFTWARE.

(ns
  ^{:author "Andrew Boekhoff, Sean Corfield",
    :doc "Various wrappers and utilities for the mongodb-java-driver"}
  somnium.congomongo
  (:require [clojure.string]
            [clojure.walk :refer (postwalk)]
            [somnium.congomongo.config :refer [*mongo-config* *default-query-options*]]
            [somnium.congomongo.coerce :refer [coerce coerce-fields coerce-index-fields]])
  (:import [com.mongodb MongoClient MongoClientOptions MongoClientOptions$Builder
                        MongoClientURI MongoCredential
                        DB DBCollection DBObject DBRef DBCursor CursorType
                        ServerAddress ReadPreference ReadConcern WriteConcern
                        AggregationOptions
                        MapReduceCommand MapReduceCommand$OutputType
                        DBEncoder]
           [com.mongodb.client.model DBCollectionUpdateOptions
                                     DBCollectionCountOptions
                                     DBCollectionFindOptions
                                     Collation]
           [com.mongodb.gridfs GridFS]
           [org.bson.types ObjectId]
           [java.util List]
           [java.util.concurrent TimeUnit]))


(defprotocol StringNamed
  (named [s] "convenience for interchangeably handling keywords, symbols, and strings"))
(extend-protocol StringNamed
  clojure.lang.Named
  (named [s] (name s))
  Object
  (named [s] s))

(def ^{:private true
       :doc "To avoid yet another level of indirection via reflection, use
             wrapper functions for field setters for each type. MongoClientOptions
             only has int and boolean fields."}
      type-to-setter
  {:int (fn [^java.lang.reflect.Field field ^MongoClientOptions options value] (.setInt field options value))
   :boolean (fn [^java.lang.reflect.Field field ^MongoClientOptions options value] (.setBoolean field options value))})

(defn- field->kw
  "Convert camelCase identifier string to hyphen-separated keyword."
  [id]
  (keyword (clojure.string/replace id #"[A-Z]" #(str "-" (Character/toLowerCase ^Character (first %))))))

(def ^:private builder-map
  "A map from keywords to builder invocation functions."
  ;; Be aware that using reflection directly here will also issue Clojure reflection warnings.
  (let [is-builder-method? (fn [^java.lang.reflect.Method f]
                             (let [m (.getModifiers f)]
                               (and (java.lang.reflect.Modifier/isPublic m)
                                    (not (java.lang.reflect.Modifier/isStatic m))
                                    (= MongoClientOptions$Builder (.getReturnType f)))))
        method-name (fn [^java.lang.reflect.Method f] (.getName f))
        builder-call (fn [^MongoClientOptions$Builder m]
                       (eval (list 'fn '[o v]
                                   (list (symbol (str "." m)) 'o 'v))))
        kw-fn-pair (fn [m] [(field->kw m) (builder-call m)])
        method-lookups (->> (.getDeclaredMethods MongoClientOptions$Builder)
                            (filter is-builder-method?)
                            (map method-name)
                            (map kw-fn-pair))]
    (into {} method-lookups)))

(defn mongo-options
  "Return MongoClientOptions, populated by any specified options. e.g.,
     (mongo-options :auto-connect-retry true)"
  [& options]
  (let [option-map (apply hash-map options)
        builder-call (fn [b [k v]]
                       (if-let [f (k builder-map)]
                         (f b v)
                         (throw (IllegalArgumentException.
                                 (str k " is not a valid MongoClientOptions$Builder argument")))))]
    (.build ^MongoClientOptions$Builder (reduce builder-call (MongoClientOptions$Builder.) option-map))))

(defn- make-server-address
  "Convenience to make a ServerAddress without reflection warnings."
  [^String host ^Integer port]
  (ServerAddress. host port))

(defn- make-mongo-client
  (^com.mongodb.MongoClient
   [addresses credential ^MongoClientOptions options]
   (if (pos? (count addresses))
     (MongoClient. ^List addresses ^List [credential] options) ;; This usage is deprecated
     (MongoClient. ^ServerAddress (first addresses)
                   ^MongoCredential credential options)))

  (^com.mongodb.MongoClient
   [addresses ^MongoClientOptions options]
    (if (> (count addresses) 1)
      (MongoClient. ^List addresses options)
      (MongoClient. ^ServerAddress (first addresses) options))))

(defn- make-connection-args
  "Makes a connection with passed database name, [{:host host, :port port}]
  server addresses and MongoClientOptions.

  username, password, and optionally auth-source, may be supplied for authenticated connections."
  [db {:keys [instances instance options ^String username ^String password] {auth-mechanism :mechanism auth-source :source} :auth-source}]
  (when (not= (nil? username) (nil? password))
    (throw (IllegalArgumentException. "Username and password must both be supplied for authenticated connections")))
  (when (and instances instance)
    (throw (IllegalArgumentException. "Only one of instances and instance can be supplied")))

  (let [addresses (map (fn [{:keys [host port] :or {host "127.0.0.1" port 27017}}]
                        (make-server-address host port))
                       (or instances
                           (when instance [instance])
                           [{:host "127.0.0.1" :port 27017}]))

        ^MongoClientOptions options (or options (mongo-options))
        ^MongoCredential credential (when (and username password)
                                      (cond
                                        (= :plain auth-mechanism)
                                        (MongoCredential/createPlainCredential username auth-source
                                                                               (.toCharArray password))

                                        (= :scram-1 auth-mechanism)
                                        (MongoCredential/createScramSha1Credential username auth-source
                                                                                   (.toCharArray password))

                                        (= :scram-256 auth-mechanism)
                                        (MongoCredential/createScramSha256Credential username auth-source
                                                                                     (.toCharArray password))

                                        auth-mechanism
                                        (throw (UnsupportedOperationException. (str auth-mechanism " is not supported.")))

                                        :else
                                        (MongoCredential/createCredential username db (.toCharArray password))))

        mongo (if credential
                (make-mongo-client addresses credential options)
                (make-mongo-client addresses options))

        n-db (if db (.getDB mongo db) nil)]
      {:mongo mongo :db n-db}))

(defn- make-connection-uri
  "Makes a connection with a Mongo URI, authenticating if username and password are passed"
  [db]
  (let [^MongoClientURI mongouri (MongoClientURI. db)
        ^MongoClient client (MongoClient. mongouri)
        ^String db (.getDatabase mongouri)
        conn {:mongo client :db (.getDB client db)}]
    conn))

(defn make-connection
  "Connects to one or more mongo instances, returning a connection
  that can be used with set-connection! and with-mongo.

  Each instance is a map containing values for :host and/or :port.

  May be called with database name and optionally:
    :instance - a server instance to connect to
    :instances - a list of server instances to connect to (deprecated)
    :options - a MongoClientOptions object
    :username - the username to authenticate with
    :password - the password to authenticate with
    :auth-source - the authentication source for authenticated connections in form:
      {:mechanism mechanism :source source}
      Supported authentication mechanisms:
        :plain
        :scram-1
        :scram-256

  If instances are not specified a connection is made to 127.0.0.1:27017

  Username and password must be supplied for authenticated connections.

  A MongoClientURI string is also supported and must be prefixed with mongodb://
  or mongodb+srv://. If username and password are specified the connection will
  be authenticated."
  {:arglists '([db :instances [{:host host, :port port}]
                   :options mongo-options
                   :username username
                   :password password
                   :auth-source {:mechanism mechanism :source source}]
               [mongo-client-uri])}
  [db & {:as args}]
  (let [^String dbname (named db)]
    (if (or (.startsWith dbname "mongodb://")
            (.startsWith dbname "mongodb+srv://"))
      (make-connection-uri dbname)
      (make-connection-args dbname args))))

(defn connection?
  "Returns truth if the argument is a map specifying an active connection."
  [x]
  (and (map? x)
       (contains? x :db)
       (:mongo x)))

(defn ^DB get-db
  "Returns the current connection. Throws exception if there isn't one."
  [conn]
  (assert (connection? conn))
  (:db conn))

(defn close-connection
  "Closes the connection, and unsets it as the active connection if necessary"
  [conn]
  (assert (connection? conn))
  (if (= conn *mongo-config*)
    (if (thread-bound? #'*mongo-config*)
      (set! *mongo-config* nil)
      (alter-var-root #'*mongo-config* (constantly nil))))
  (.close ^MongoClient (:mongo conn)))

(defmacro with-mongo
  "Makes conn the active connection in the enclosing scope.

  When with-mongo and set-connection! interact, last one wins"
  [conn & body]
  `(do
     (let [c# ~conn]
       (assert (connection? c#))
       (binding [*mongo-config* c#]
         ~@body))))

(defmacro with-db
  "Make dbname the active database in the enclosing scope.

  When with-db and set-database! interact, last one wins."
  [dbname & body]
  `(let [^DB db# (.getDB ^MongoClient (:mongo *mongo-config*) (name ~dbname))]
     (binding [*mongo-config* (assoc *mongo-config* :db db#)]
       ~@body)))

(defmacro with-default-query-options
  [options & body]
  `(do
     (binding [*default-query-options* ~options]
       ~@body)))

(defn set-connection!
  "Makes the connection active. Takes a connection created by make-connection.

When with-mongo and set-connection! interact, last one wins"
  [connection]
  (alter-var-root #'*mongo-config*
                  (constantly connection)
                  (when (thread-bound? #'*mongo-config*)
                    (set! *mongo-config* connection))))

(definline ^DBCollection get-coll
  "Returns a DBCollection object"
  [collection]
  `(.getCollection (get-db *mongo-config*)
     ^String (named ~collection)))

(def write-concern-map
  {:acknowledged         WriteConcern/ACKNOWLEDGED
   :fsynced              WriteConcern/JOURNALED
   :journaled            WriteConcern/JOURNALED
   :majority             WriteConcern/MAJORITY
   :replica-acknowledged WriteConcern/W2
   :unacknowledged       WriteConcern/UNACKNOWLEDGED
   ;; these are pre-2.10.x names for write concern:
   :fsync-safe    WriteConcern/JOURNALED
   :journal-safe  WriteConcern/JOURNALED
   :normal        WriteConcern/UNACKNOWLEDGED
   :replicas-safe WriteConcern/W2
   :safe          WriteConcern/ACKNOWLEDGED
   ;; these are left for backward compatibility but are deprecated:
   :replica-safe WriteConcern/W2
   :strict       WriteConcern/ACKNOWLEDGED
   })

(defn set-write-concern
  "Sets the write concern on the connection. Setting is a key in the
  write-concern-map above."
  [connection setting]
  (assert (contains? (set (keys write-concern-map)) setting))
  (.setWriteConcern (get-db connection)
                    ^WriteConcern (get write-concern-map setting)))

(defn set-collection-write-concern!
  "Sets this write concern as default for a collection."
  [collection write-concern]
  (if-let [concern (get write-concern-map write-concern)]
    (.setWriteConcern (get-coll collection) concern)
    (throw (IllegalArgumentException. (str "Unknown write concern " write-concern ".")))))

(defn get-collection-write-concern
  "Gets the currently set write concern for a collection."
  [collection]
    (.getWriteConcern (get-coll collection)))

(defn- illegal-write-concern
  [write-concern]
  (throw (IllegalArgumentException. (str write-concern " is not a valid WriteConcern alias"))))

(def read-concern-map
  {:default      ReadConcern/DEFAULT
   :local        ReadConcern/LOCAL
   :majority     ReadConcern/MAJORITY
   :linearizable ReadConcern/LINEARIZABLE
   :snapshot     ReadConcern/SNAPSHOT
   :available    ReadConcern/AVAILABLE})

(defn- illegal-read-concern
  [read-concern]
  (throw (IllegalArgumentException. (str read-concern " is not a valid ReadConcern alias"))))

;; add some convenience fns for manipulating object-ids
(definline object-id ^ObjectId [^String s]
  `(ObjectId. ~s))

;; Make ObjectIds printable under *print-dup*, hiding the
;; implementation-dependent ObjectId class
(defmethod print-dup ObjectId [^ObjectId x ^java.io.Writer w]
  (.write w (str "#=" `(object-id ~(.toString x)))))

(defn get-timestamp
  "Pulls the timestamp from an ObjectId or a map with a valid ObjectId in :_id."
  [obj]
  (when-let [^ObjectId id (if (instance? ObjectId obj) obj (:_id obj))]
    (.getTime id)))

(defn db-ref
  "Convenience DBRef constructor."
  [ns id]
  (DBRef. ^String (named ns)
          ^Object id))

(defn db-ref? [x]
  (instance? DBRef x))

(defn collection-exists?
  "Query whether the named collection has been created within the DB."
  [collection]
  (.collectionExists (get-db *mongo-config*)
                     ^String (named collection)))
(defn create-collection!
  "Explicitly create a collection with the given name, which must not already exist.

   Most users will not need this function, and will instead allow
   MongoDB to implicitly create collections when they are written
   to. This function exists primarily to allow the creation of capped
   collections, and so supports the following keyword arguments:

   :capped -> boolean: if the collection is capped
   :size   -> int: collection size (in bytes)
   :max    -> int: max number of documents."
  {:arglists
   '([collection :capped :size :max])}
  [collection & {:keys [capped size max] :as options}]
  (.createCollection (get-db *mongo-config*)
                     ^String (named collection)
                     (coerce options [:clojure :mongo])))

(def ^:private read-preference-map
  "Private map of factory functions of ReadPreferences to aliases."
  {:nearest (fn nearest ([] (ReadPreference/nearest)) ([^List tags] (ReadPreference/nearest tags)))
   :primary (fn primary ([] (ReadPreference/primary)) ([_] (throw (IllegalArgumentException. "Read preference :primary does not accept tag sets."))))
   :primary-preferred (fn primary-preferred ([] (ReadPreference/primaryPreferred)) ([^List tags] (ReadPreference/primaryPreferred tags)))
   :secondary (fn secondary ([] (ReadPreference/secondary)) ([^List tags] (ReadPreference/secondary tags)))
   :secondary-preferred (fn secondary-preferred ([] (ReadPreference/secondaryPreferred)) ([^List tags] (ReadPreference/secondaryPreferred tags)))})

(defn- named?
  [x]
  (instance? clojure.lang.Named x))

(defn ->tagset
  [tag]
  (letfn [(->tag [[k v]]
            (com.mongodb.Tag. (if (named? k) (name k) (str k))
                              (if (named? v) (name v) (str v))))]
    (com.mongodb.TagSet. ^List (map ->tag tag))))

(defn read-preference
  "Creates a ReadPreference from an alias and optional tag sets. Valid aliases are
   :nearest, :primary, :primary-preferred, :secondary, and :secondary-preferred."
  {:arglists '([preference {:first-tag "value", :second-tag "second-value"} {:other-tag-set "other-value"}])}
  [preference & tags]
  (if-let [pref-factory (get read-preference-map preference)]
    (if (empty? tags)
      (pref-factory)
      (pref-factory (map ->tagset tags)))
    (throw (IllegalArgumentException. (str preference " is not a valid ReadPreference alias.")))))

(defn set-read-preference
  "Sets the read preference on the connection (you may supply a ReadPreference or a valid alias)."
  [connection preference]
  (let [p (if (instance? ReadPreference preference)
            preference
            (read-preference preference))]
    (.setReadPreference (get-db connection) ^ReadPreference p)))

(defn set-collection-read-preference!
  "Sets the read preference as default for a collection."
  [collection preference & opts]
  (let [pref (apply read-preference preference opts)]
    (.setReadPreference (get-coll collection) pref)
    pref))

(defn get-collection-read-preference
  "Returns the currently set read preference for a collection"
  [collection]
  (.getReadPreference (get-coll collection)))

(defn set-cursor-options!
  "Sets the options on the cursor"
  [^DBCursor cursor {:keys [tailable secondary-preferred slaveok oplog notimeout awaitdata]}]
  (when tailable
    (.cursorType cursor CursorType/Tailable))
  (when awaitdata
    (.cursorType cursor CursorType/TailableAwait))
  (when (or secondary-preferred slaveok)
    (.setReadPreference cursor (ReadPreference/secondaryPreferred)))
  (when oplog
    (.oplogReplay cursor true))
  (when notimeout
    (.noCursorTimeout cursor true)))

(defn fetch
  "Fetches objects from a collection.
   Note that MongoDB always adds the `_id` and `_ns` fields to objects returned from the database.

   Required parameters:
   collection         -> the database collection

   Optional parameters include:
   :one?               -> will result in `findOne` query (defaults to false, use `fetch-one` as a shortcut)
   :count?             -> will result in `getCount` query (defaults to false, use `fetch-count` as a shortcut)
   :as                 -> what to return (defaults to `:clojure`, can also be `:json` or `:mongo`)
   :from               -> argument type, same options as above
   :where              -> the selection criteria using query operators (a query map)
   :only               -> `projection`; a set of fields to return for all matching documents (an array of keys)
   :sort               -> the sort criteria to apply to the query (`null` means an undefined order of results)
   :skip               -> number of documents to skip
   :limit              -> number of documents to return
   :batch-size         -> number of documents to return per batch (by default, server chooses an appropriate)
   :max-time-ms        -> set the maximum execution time on the server for this operation
   :max-await-time-ms  -> set the maximum await execution time on the server for this operation
   :cursor-type        -> set the cursor type (either `NonTailable`, `Tailable`, or `TailableAwait`)
   :no-cursor-timeout? -> prevent server from timing out idle cursors after an inactivity period (10 min)
   :oplog-replay?      -> users should not set this under normal circumstances
   :partial?           -> get partial results from a sharded cluster if one or more shards are unreachable
   :read-preference    -> set the read preference (e.g. :primary or ReadPreference instance)
   :read-concern       -> set the read concern (e.g. :local, see the `read-concern-map` for available options)
   :collation          -> set the collation
   :comment            -> set the comment to the query
   :hint               -> tell the query which index to use (name (string) or [:compound :index] (seq of keys))
   :max                -> set the exclusive upper bound for a specific index
   :min                -> set the minimum inclusive lower bound for a specific index
   :return-key?        -> if true the find operation will return only the index keys in the resulting documents
   :show-record-id?    -> set to true to add a field `$recordId` to the returned documents
   :explain?           -> returns performance information on the query, instead of rows
   :options            -> query options [:tailable :slaveok :oplogreplay :notimeout :awaitdata] (see the NOTE)

   However, not all of the aforementioned optional params affect the `count?` mode, but only these:
   `hint`, `skip`, `limit`, `max-time-ms`, `read-preference`, `read-concern`, and `collation`.

   As well, the `one?` mode doesn't support `options`, `explain?`, `sort`, `limit`, or `hint`.

   NOTE: The `options` use a custom data format, are unnecessary and left for backward compatibility,
         and might be removed in the next major 'congomongo' release."
  {:arglists
   '([collection {:one? false :count? false :as :clojure :from :clojure :where {} :only [] :sort nil :skip 0 :limit 0
             :batch-size nil :max-time-ms nil :max-await-time-ms nil :cursor-type nil :no-cursor-timeout? nil
             :oplog-replay? nil :partial? nil :read-preference nil :read-concern nil :collation nil :comment nil
             :hint nil :max nil :min nil :return-key? nil :show-record-id? nil :explain? false :options []}])}
  [collection & {:as params}]
  (let [{:keys [one? count? as from where only sort skip limit
                batch-size max-time-ms max-await-time-ms cursor-type no-cursor-timeout? oplog-replay? partial?
                read-preference read-preferences read-concern collation comment hint max min return-key? show-record-id?
                explain? options]}
        (merge {:one? false :count? false :as :clojure :from :clojure
                :where {} :only [] :sort nil :skip 0 :limit 0
                :explain? false :options []} ;; specific to `DBCursor`
               *default-query-options*
               params)]
    (when (and one? sort)
      (throw (IllegalArgumentException. "The `fetch-one` (`:one? true`) can't be used with `sort`.
Please, use `fetch` with `:limit 1` instead.")))
    (when (and one? (or (not= [] options) explain? (not= 0 limit) hint))
      (throw (IllegalArgumentException. "The `fetch-one` doesn't support `options`, `explain?`, `limit`, or `hint`")))
    (when-not (or (nil? hint)
                  (string? hint)
                  (and (instance? clojure.lang.Sequential hint)
                       (every? #(or (keyword? %)
                                    (and (instance? clojure.lang.Sequential %)
                                         (= 2 (count %))
                                         (-> % first keyword?)
                                         (-> % second #{1 -1})))
                               hint)))
      (throw (IllegalArgumentException. ":hint requires a string name of the index, or a seq of keywords that is the index definition")))

    (let [n-where (coerce where [from :mongo])
          n-only  (when only (coerce-fields only))
          n-coll  (get-coll collection)
          ;; Congomongo originally used do convert passed `limit` into negative number because
          ;; Mongo protocol says:
          ;;  > If the number is negative, then the database will return that number and close
          ;;  > the cursor. No further results for that query can be fetched.
          ;; (see https://docs.mongodb.com/manual/reference/mongodb-wire-protocol/#op-query)
          ;;
          ;; But after bumping mongo-driver from 3.0.2 to 3.2.2 we discovered that if a number
          ;; of available matching documents in Mongo is bigger than `batchSize` (101 by default)
          ;; and `limit` is a negative number then Mongo will return only `batchSize`
          ;; of results and close the cursor. Which is in agreement with Mongo shell docs:
          ;;  > A negative limit is similar to a positive limit but closes the cursor after
          ;;  > returning a single batch of results. As such, with a negative limit, if the
          ;;  > limited result set does not fit into a single batch, the number of documents
          ;;  > received will be less than the specified limit. By passing a negative limit,
          ;;  > the client indicates to the server that it will not ask for a subsequent batch
          ;;  > via getMore.
          ;; (see https://docs.mongodb.com/manual/reference/method/cursor.limit/#negative-values)
          ;;
          ;; Maybe protocol description implies that number can't be bigger than a `batchSize`
          ;; or maybe protocol has been changed and docs aren't updated. Anyway, the current
          ;; behaviour (with negative `limit`) doesn't match expectations, therefore changed
          ;; to keep `limit` as is.
          n-limit (or limit 0)
          ;; TODO: Backward compatibility issue. Can't drop these `read-preferences` which had
          ;;       this extra 's' in the end. Remove on the next major 'congomongo' release.
          read-preference (or read-preference read-preferences)
          ;; TODO: Requires smth. like `set-collection-read-preference!` to be able to pass tags.
          n-preference (cond
                         (nil? read-preference) nil
                         (instance? ReadPreference read-preference) read-preference
                         :else (somnium.congomongo/read-preference read-preference))
          read-concern (when read-concern
                         (or (read-concern read-concern-map)
                             (illegal-read-concern read-concern)))]
      (if count?
        (.getCount ^DBCollection n-coll
                   ^DBObject n-where
                   ^DBCollectionCountOptions
                   (let [opts (DBCollectionCountOptions.)]
                     (when hint
                       (if (string? hint)
                         (.hintString opts ^String hint)
                         (.hint opts ^DBObject (coerce-index-fields hint))))
                     (when (int? skip)
                       (.skip opts ^int skip))
                     (when (int? n-limit)
                       (.limit opts ^int n-limit))
                     (when (int? max-time-ms)
                       (.maxTime opts ^long max-time-ms TimeUnit/MILLISECONDS))
                     (when n-preference
                       (.readPreference opts ^ReadPreference n-preference))
                     (when read-concern
                       (.readConcern opts ^ReadConcern read-concern))
                     (when (instance? Collation collation)
                       (.collation opts ^Collation collation))
                     opts))

        (let [findOpts (let [opts (DBCollectionFindOptions.)]
                         (when sort
                           (.sort opts ^DBObject (coerce sort [from :mongo])))
                         (when (int? skip)
                           (.skip opts ^int skip))
                         (when (int? n-limit)
                           (.limit opts ^int n-limit))
                         (when (int? batch-size)
                           (.batchSize opts ^int batch-size))
                         (when n-only
                           (.projection opts ^DBObject n-only))
                         (when (int? max-time-ms)
                           (.maxTime opts ^long max-time-ms TimeUnit/MILLISECONDS))
                         (when (int? max-await-time-ms)
                           (.maxAwaitTime opts ^long max-await-time-ms TimeUnit/MILLISECONDS))
                         (when (instance? CursorType cursor-type)
                           (.cursorType opts ^CursorType cursor-type))
                         (when (boolean? no-cursor-timeout?)
                           (.noCursorTimeout opts ^boolean no-cursor-timeout?))
                         (when (boolean? oplog-replay?)
                           (.oplogReplay opts ^boolean oplog-replay?))
                         (when (boolean? partial?)
                           (.showRecordId opts ^boolean partial?))
                         (when n-preference
                           (.readPreference opts ^ReadPreference n-preference))
                         (when read-concern
                           (.readConcern opts ^ReadConcern read-concern))
                         (when (instance? Collation collation)
                           (.collation opts ^Collation collation))
                         (when (string? comment)
                           (.comment opts ^String comment))
                         (when hint
                           (if (string? hint)
                             ;; NOTE: This is currently the one line that prevents
                             ;;       upgrading to the 4.3 driver. String `hint`
                             ;;       doesn't exist in that driver, but it will
                             ;;       be added back in 4.4.
                             ;;       https://jira.mongodb.org/browse/JAVA-4281
                             (.put (.getModifiers opts) "$hint" hint)
                             ;(.hintString opts ^String hint) ;; TODO: Use this instead.
                             (.hint opts ^DBObject (coerce-index-fields hint))))
                         (when max
                           (.max opts ^DBObject (coerce max [from :mongo])))
                         (when min
                           (.min opts ^DBObject (coerce min [from :mongo])))
                         (when (boolean? return-key?)
                           (.returnKey opts ^boolean return-key?))
                         (when (boolean? show-record-id?)
                           (.showRecordId opts ^boolean show-record-id?))
                         opts)]
          (if one?
            (when-some [res (.findOne ^DBCollection n-coll
                                      ^DBObject n-where
                                      ^DBCollectionFindOptions findOpts)]
              (coerce res [:mongo as]))

            (when-let [cursor (.find ^DBCollection n-coll
                                     ^DBObject n-where
                                     ^DBCollectionFindOptions findOpts)]
              ;; TODO: Backward compatibility issue. Can't drop these `options`,
              ;;       because they use a custom data format. Remove on the next
              ;;       major 'congomongo' release.
              (when options
                (set-cursor-options! cursor options))
              (if explain?
                (coerce (.explain ^DBCursor cursor) [:mongo as] :many false)
                (coerce cursor [:mongo as] :many true)))))))))

(defn fetch-one [col & options]
  (apply fetch col (concat options '[:one? true])))

(defn fetch-count [col & options]
  (apply fetch col (concat options '[:count? true])))

;; add fetch-by-id fn
(defn fetch-by-id [col id & options]
  (apply fetch col (concat options [:one? true :where {:_id id}])))

(defn fetch-by-ids [col ids & options]
  (apply fetch col (concat options [:where {:_id {:$in ids}}])))

(defn with-ref-fetching
  "Returns a decorated fetcher fn which eagerly loads db-refs."
  [fetcher]
  (fn [& args]
    (let [as (or (second (drop-while (partial not= :as) args))
                 :clojure)
          f  (fn [[k v]]
               [k (if (db-ref? v)
                    (let [v ^DBRef v
                          coll (get-coll (.getCollectionName v))]
                      (coerce (.findOne coll (.getId v)) [:mongo as]))
                    v)])]
      (postwalk (fn [x]
                  (if (map? x)
                    (into {} (map f x))
                    x))
                (apply fetcher args)))))

(defn distinct-values
  "Queries a collection for the distinct values of a given key.
   Returns a vector of the values by default (but see the :as keyword argument).
   The key (a String) can refer to a nested object, using dot notation, e.g., \"foo.bar.baz\".

   Optional arguments include
   :where  -> a query object.  If supplied, distinct values from the result of the query on the collection (rather than from the entire collection) are returned.
   :from   -> specifies what form a supplied :where query is in (:clojure, :json, or :mongo).  Defaults to :clojure.  Has no effect if there is no :where query.
   :as     -> results format (:clojure, :json, or :mongo).  Defaults to :clojure."
  {:arglists
   '([collection key :where :from :as])}
  [coll ^String k & {:keys [where from as]
             :or {where {} from :clojure as :clojure}}]
  (let [^DBObject query (coerce where [from :mongo])]
    (coerce (.distinct ^DBCollection (get-coll coll) k query)
            [:mongo as])))

(defn insert!
  "Inserts a map into collection. Will not overwrite existing maps.
   Takes optional from and to keyword arguments. To insert
   as a side-effect only specify :to as nil."
  {:arglists '([coll obj {:many false :from :clojure :to :clojure :write-concern nil}])}
  [coll obj & {:keys [from to many write-concern]
               :or {from :clojure to :clojure many false}}]
  (let [coerced-obj (coerce obj [from :mongo] :many many)
        list-obj (if many coerced-obj (list coerced-obj))
        res (if write-concern
              (if-let [wc (write-concern write-concern-map)]
                (.insert ^DBCollection (get-coll coll) ^List list-obj ^WriteConcern wc)
                (illegal-write-concern write-concern))
              (.insert ^DBCollection (get-coll coll) ^List list-obj))]
    (coerce coerced-obj [:mongo to] :many many)))

(defn mass-insert!
  {:arglists '([coll objs {:from :clojure :to :clojure}])}
  [coll objs & {:keys [from to write-concern]
                :or {from :clojure to :clojure}}]
  (insert! coll objs :from from :to to :many true :write-concern write-concern))

(defn update!
  "Alters/inserts a map in a collection. Overwrites existing objects.
   The shortcut forms need a map with valid `:_id` and `:_ns` fields or
   a collection and a map with a valid `:_id` field.

   Required parameters:
   collection     -> the database collection
   query          -> the selection criteria for the update (a query map)
   update         -> the modifications to apply (a modifications map)

   Optional parameters include:
   :upsert?       -> do upsert, i.e. insert if document not present (default is `true`)
   :multiple?     -> whether this will update all documents matching the query filter (default is `false`)
   :as            -> what to return (defaults to `:clojure`, can also be `:json` or `:mongo`)
   :from          -> argument type, same options as above
   :write-concern -> set the write concern (e.g. :normal, see the `write-concern-map` for available options)
   :bypass-document-validation -> set the bypass document level validation flag
   :encoder       -> set the encoder (of BSONObject to BSON)
   :collation     -> set the collation
   :array-filters -> set the array filters option"
  {:arglists '([collection old new {:upsert? true :multiple? false :as :clojure :from :clojure
                     :write-concern nil :bypass-document-validation nil :encoder nil :collation nil
                     :array-filters nil}])}
  [collection query update & {:keys [upsert? multiple? as from write-concern bypass-document-validation
                                     upsert multiple ;; TODO: For backward compatibility. Remove later.
                                     encoder collation array-filters]
                              :or {upsert? true multiple? false as :clojure from :clojure}}]
  (coerce (.update ^DBCollection (get-coll collection)
                   ^DBObject (coerce query [from :mongo])
                   ^DBObject (coerce update [from :mongo])
                   ^DBCollectionUpdateOptions
                   (let [opts (DBCollectionUpdateOptions.)]
                     (.upsert opts ^boolean (or upsert upsert?))
                     (.multi opts ^boolean (or multiple multiple?))
                     (when write-concern
                       (if-let [wc (write-concern write-concern-map)]
                         (.writeConcern opts ^WriteConcern wc)
                         (illegal-write-concern write-concern)))
                     (when (boolean? bypass-document-validation)
                       (.bypassDocumentValidation opts ^boolean bypass-document-validation))
                     (when (instance? DBEncoder encoder)
                       (.encoder opts ^DBEncoder encoder))
                     (when (instance? Collation collation)
                       (.collation opts ^Collation collation))
                     (when array-filters
                       (let [coerced-array-filters (coerce array-filters [:clojure :mongo] :many true)]
                         (.arrayFilters opts ^List coerced-array-filters)))
                     opts))
          [:mongo as]))

(defn fetch-and-modify
  "Finds the first document in the query and updates it.
   Parameters:
       coll         -> the collection
       where        -> query to match
       update       -> update to apply
       :only        -> fields to be returned
       :sort        -> sort to apply before picking first document
       :remove?     -> if true, document found will be removed
       :return-new? -> if true, the updated document is returned,
                       otherwise the old document is returned
                       (or it would be lost forever)
       :upsert?     -> do upsert (insert if document not present)
       :max-time-ms -> set the maximum execution time for operations"
  {:arglists '([collection where update {:only nil :sort nil :remove? false
                                         :return-new? false :upsert? false :max-time-ms 0 :from :clojure :as :clojure}])}
  [coll where update & {:as params}]
  (let [{:keys [only sort remove? return-new? upsert? from as max-time-ms]}
        (merge {:only nil :sort nil :remove? false
                :return-new? false :upsert? false
                :max-time-ms 0 :from :clojure :as :clojure}
               *default-query-options*
               params)]
      (coerce (.findAndModify ^DBCollection (get-coll coll)
                              ^DBObject (coerce where [from :mongo])
                              ^DBObject (coerce-fields only)
                              ^DBObject (coerce sort [from :mongo])
                              remove?
                              ^DBObject (coerce update [from :mongo])
                              return-new? upsert?
                              max-time-ms TimeUnit/MILLISECONDS) [:mongo as])))


(defn destroy!
   "Removes map from collection. Takes a collection name and
    a query map"
   {:arglists '([collection where {:from :clojure :write-concern nil}])}
   [c q & {:keys [from write-concern]
           :or {from :clojure}}]
   (if write-concern
     (if-let [wc (write-concern write-concern-map)]
       (.remove (get-coll c) ^DBObject (coerce q [from :mongo]) ^WriteConcern wc)
       (illegal-write-concern write-concern))
     (.remove (get-coll c) ^DBObject (coerce q [from :mongo]))))

(defn add-index!
  "Adds an index on the collection for the specified fields if it does not exist.  Ordering of fields is
   significant; an index on [:a :b] is not the same as an index on [:b :a].

   By default, all fields are indexed in ascending order.  To index a field in descending order, specify it as
   a vector with a direction signifier (i.e., -1), like so:

   [:a [:b -1] :c]

   This will generate an index on:

      :a ascending, :b descending, :c ascending

   Similarly, [[:a 1] [:b -1] :c] will generate the same index (\"1\" indicates ascending order, the default).

    Options include:
    :name   -> defaults to the system-generated default
    :unique -> defaults to false
    :sparse -> defaults to false
    :background -> defaults to false
    :partial-filter-expression -> defauls to no filter expression"
   {:arglists '([collection fields {:name nil :unique false :sparse false :partial-filter-expression nil}])}
   [c f & {:keys [name unique sparse background partial-filter-expression]
           :or {name nil unique false sparse false background false}}]
   (-> (get-coll c)
       (.createIndex (coerce-index-fields f) ^DBObject (coerce (merge {:unique unique :sparse sparse :background background}
                                                                       (if name {:name name})
                                                                       (if partial-filter-expression
                                                                         {:partialFilterExpression partial-filter-expression}))
                                                                [:clojure :mongo]))))
(defn drop-index!
  "Drops an index on the collection for the specified fields.

  `index` may be a vector representing the key(s) of the index (see somnium.congomongo/add-index! for the
  expected format).  It may also be a String or Keyword, in which case it is taken to be the name of the
  index to be deleted.

  Due to how the underlying MongoDB driver works, if you defined an index with a custom name, you *must*
  delete the index using that name, and not the keys."
  [coll index]
  (if (vector? index)
    (.dropIndex (get-coll coll) (coerce-index-fields index))
    (.dropIndex (get-coll coll) ^String (coerce index [:clojure :mongo]))))

(defn drop-all-indexes!
  "Drops all indexes from a collection"
  [coll]
  (.dropIndexes (get-coll coll)))

(defn get-indexes
  "Get index information on collection"
  {:arglists '([collection :as (:clojure)])}
   [coll & {:keys [as]
            :or {as :clojure}}]
   (map #(into {} %) (.getIndexInfo (get-coll coll))))

(defn aggregate
  "Executes a pipeline of operations using the Aggregation Framework.
   Returns map {:serverUsed ... :result ... :ok ...}
   :serverUsed - string representing server address
   :result     - the result of the aggregation (if successful)
   :ok         - 1.0 for success
   Requires MongoDB 2.2!"
  {:arglists '([coll op & ops {:from :clojure :to :clojure}])}
  [coll op & ops-and-from-to]
  (let [ops (take-while (complement keyword?) ops-and-from-to)
        from-and-to (drop-while (complement keyword?) ops-and-from-to)
        {:keys [from to] :or {from :clojure to :clojure}} from-and-to
        cursor (.aggregate (get-coll coll)
                           ^List (coerce (conj ops op) [from :mongo])
                           ^AggregationOptions (-> (AggregationOptions/builder)
                                                   (.build)))]
    {:serverUsed (.toString (.getServerAddress cursor))
     :result (coerce cursor [:mongo to] :many true)
     :ok 1.0}))

(defn command
  "Executes a database command."
  {:arglists '([cmd {:options nil :from :clojure :to :clojure}])}
  [cmd & {:keys [options from to]
          :or {options nil from :clojure to :clojure}}]
  (let [db (get-db *mongo-config*)
        coerced ^DBObject (coerce cmd [from :mongo])]
    (coerce (if options
              (.command db coerced (int options))
              (.command db coerced))
            [:mongo to])))

(defn drop-database!
 "drops a database from the mongo server"
 [title]
 (.dropDatabase ^MongoClient (:mongo *mongo-config*) ^String (named title)))

(defn set-database!
  "atomically alters the current database"
  [title]
  (if-let [db (.getDB ^MongoClient (:mongo *mongo-config*) ^String (named title))]
    (alter-var-root #'*mongo-config* merge {:db db})
    (throw (RuntimeException. (str "database with title " title " does not exist.")))))

;;;; go ahead and have these return seqs

(defn databases
  "List databases on the mongo server" []
  (seq (.getDatabaseNames ^MongoClient (:mongo *mongo-config*))))

(defn collections
  "Returns the set of collections stored in the current database" []
  (seq (.getCollectionNames (get-db *mongo-config*))))

(defn drop-coll!
  [collection]
  "Permanently deletes a collection. Use with care."
  (.drop ^DBCollection (.getCollection (get-db *mongo-config*)
                                       ^String (named collection))))

;;;; GridFS, contributed by Steve Purcell
;;;; question: keep the camelCase keyword for :contentType ?

(definline ^GridFS get-gridfs
  "Returns a GridFS object for the named bucket"
  [bucket]
  `(GridFS. (get-db *mongo-config*) ^String (named ~bucket)))

;; The naming of :contentType is ugly, but consistent with that
;; returned by GridFSFile
(defn insert-file!
  "Insert file data into a GridFS. Data should be either a File,
   InputStream or byte array.
   Options include:
   :filename    -> defaults to nil
   :contentType -> defaults to nil
   :metadata    -> defaults to nil
   :_id         -> defaults to nil (autogenerate)"
  {:arglists '([fs data {:filename nil :contentType nil :metadata nil}])}
  [fs data & {:keys [^String filename ^String contentType ^DBObject metadata _id]
              :or {filename nil contentType nil metadata nil _id nil}}]
  (let [^com.mongodb.gridfs.GridFSInputFile f (.createFile ^GridFS (get-gridfs fs) data)]
    (if filename (.setFilename f ^String filename))
    (if contentType (.setContentType f contentType))
    (if metadata (.setMetaData f (coerce metadata [:clojure :mongo])))
    (if _id (.setId f _id))
    (.save f)
    (coerce f [:mongo :clojure])))

(defn destroy-file!
   "Removes file from gridfs. Takes a GridFS name and
    a query map"
   {:arglists '([fs where {:from :clojure}])}
   [fs q & {:keys [from]
            :or {from :clojure}}]
   (.remove (get-gridfs fs)
            ^DBObject (coerce q [from :mongo])))

(defn fetch-files
  "Fetches objects from a GridFS
   Note that MongoDB always adds the _id and _ns
   fields to objects returned from the database.
   Optional arguments include
   :where  -> takes a query map
   :from   -> argument type, same options as above
   :one?   -> defaults to false, use fetch-one-file as a shortcut"
  {:arglists
   '([fs :where :from :one?])}
  [fs & {:keys [where from one?]
         :or {where {} from :clojure one? false}}]
  (let [n-where (coerce where [from :mongo])
        n-fs   (get-gridfs fs)]
    (if one?
      (if-let [m (.findOne ^GridFS n-fs ^DBObject n-where)]
        (coerce m [:mongo :clojure]) nil)
      (if-let [m (.find ^GridFS n-fs ^DBObject n-where)]
        (coerce m [:mongo :clojure] :many true) nil))))

(defn fetch-one-file [fs & options]
  (apply fetch-files fs (concat options '[:one? true])))

(defn write-file-to
  "Writes the data stored for a file to the supplied output, which
   should be either an OutputStream, File, or the String path for a file."
  [fs file out]
  ;; since .findOne is overloaded and coerce returns different types, we cannot remove the reflection warning:
  (if-let [^com.mongodb.gridfs.GridFSDBFile f (.findOne ^GridFS (get-gridfs fs) ^DBObject (coerce file [:clojure :mongo]))]
    ;; since .writeTo is overloaded and we can pass different types, we cannot remove the reflection warning:
    (.writeTo f out)))

(defn stream-from
  "Returns an InputStream from the GridFS file specified"
  [fs file]
  ;; since .findOne is overloaded and coerce returns different types, we cannot remove the reflection warning:
  (if-let [^com.mongodb.gridfs.GridFSDBFile f (.findOne ^GridFS (get-gridfs fs) ^DBObject (coerce file [:clojure :mongo]))]
    (.getInputStream f)))

(defn- mapreduce-type
  [k]
  (get {:replace MapReduceCommand$OutputType/REPLACE
        :merge   MapReduceCommand$OutputType/MERGE
        :reduce  MapReduceCommand$OutputType/REDUCE}
       k
       MapReduceCommand$OutputType/INLINE))

(defn map-reduce
  "Performs a map-reduce job on the server.

  Mandatory arguments
  collection -> the collection to run the job on
  mapfn -> a JavaScript map function, as a String.  Should take no arguments.
  reducefn -> a JavaScript reduce function, as a String.  Should take two arguments: a key, and a corresponding array of values
  out -> output descriptor
      With MongoDB 1.8, there are many options:
          a collection name (String or Keyword): output is saved in the named collection, removing any data that previously existed there.
      Or, a configuration map:
          {:replace collection-name}: same as above
          {:merge collection-name}: incorporates results of the MapReduce with any data already in the collection
          {:reduce collection-name}: further reduces with any data already in the collection
          {:inline 1}: creates no collection, and returns the results directly

  See http://www.mongodb.org/display/DOCS/MapReduce for more information, as well as the test code in congomongo_test.clj.

  Optional Arguments
  :out-from    -> indicates what form the out parameter is specified in (:clojure, :json, or :mongo).  Defaults to :clojure.
  :query       -> a query map against collection; if this is specified, the map-reduce job is run on the result of this query instead of on the collection as a whole.
  :query-from  -> if query is supplied, specifies what form it is in (:clojure, :json, or :mongo).  Defaults to :clojure.
  :sort        -> if you want query sorted (for optimization), specify a map of sort clauses here.
  :sort-from   -> if sort is supplied, specifies what form it is in (:clojure, :json, or :mongo).  Defaults to :clojure.
  :limit       -> the number of objects to return from a query collection (defaults to 0; that is, everything).  This pertains to query, NOT the result of the overall map-reduce job!
  :finalize    -> a finalizaton function (JavaScript, as a String).  Should take two arguments: a key and a single value (not an array of values).
  :scope       -> a scope object; variables in the object will be available in the global scope of map, reduce, and finalize functions.
  :scope-from  -> if scope is supplied, specifies what form it is in (:clojure, :json, or :mongo).  Defaults to :clojure.
  :output      -> if you want the resulting documents from the map-reduce job, specify :documents; otherwise, if you want the name of the result collection as a keyword, specify :collection.
                  Defaults to :documents.  If the value of 'out' is {:inline 1}, you will get documents, regardless of what you actually put here.
  :as          -> if :output is set to :documents, determines the form the results take (:clojure, :json, or :mongo) (has no effect if :output is set to :collection; that is always returned as a Clojure keyword).
"
  {:arglists
   '([collection mapfn reducefn out :out-from :query :query-from :sort :sort-from :limit :finalize :scope :scope-from :output :as])}
  [collection mapfn reducefn out & {:keys [out-from query query-from sort sort-from limit finalize scope scope-from output as]
                                    :or {out-from :clojure query nil query-from :clojure sort nil sort-from :clojure
                                         limit nil finalize nil scope nil scope-from :clojure output :documents as :clojure}}]
  (let [mr-query (coerce (or query {}) [query-from :mongo])
        ;; The output collection and output-type are inherently bound to each
        ;; other. If out is a string/keyword then the output type should be
        ;; INLINE (and the out 'collection' converted to a string)
        ;;
        ;; If out is a map then it should have a single entry, the key is the
        ;; output-type and the value is the output collection
        [out-collection mr-type] (letfn [(convert-map [[k v]]
                                          [(named v) (mapreduce-type k)])]
                                  (cond
                                    (= out {:inline 1}) [nil (mapreduce-type :inline)]
                                    (map? out)          (-> out first convert-map)
                                    (named? out)        [(named out) (mapreduce-type :replace)]))
        ;; Verbose is true by default
        ;; http://api.mongodb.org/java/3.0/com/mongodb/MapReduceCommand.html#setVerbose-java.lang.Boolean-
        mr-command (MapReduceCommand. (get-coll collection)
                                      mapfn
                                      reducefn
                                      (str out-collection)
                                      mr-type
                                      mr-query)]
    (when sort
      (.setSort mr-command (coerce sort [sort-from :mongo])))
    (when limit
      (.setLimit mr-command limit))
    (when finalize
      (.setFinalize mr-command finalize))
    (when scope
      (.setScope mr-command (coerce scope [scope-from :mongo])))

    (let [^com.mongodb.MapReduceOutput result (.mapReduce (get-coll collection) mr-command)]
      (if (or (= output :documents)
              (= (coerce out [out-from :clojure])
                 {:inline 1}))
        (coerce (.results result) [:mongo as] :many true)
        (-> (.getOutputCollection result)
            .getName
            keyword)))))
