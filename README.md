CongoMongo [![ClojarsVersionRelease](https://img.shields.io/clojars/v/congomongo)](https://clojars.org/congomongo) [![ClojarsDownloads](https://img.shields.io/clojars/dt/congomongo)](https://clojars.org/congomongo) [![CircleCI](https://circleci.com/gh/congomongo/congomongo/tree/master.svg?style=svg)](https://circleci.com/gh/congomongo/congomongo/tree/master)
===========

What?
------
A toolkit for using MongoDB with Clojure. This library is a pretty lightweight wrapper around the MongoDB Java driver, and can be paired with your favorite framworks or functions for validation such as [clojure.spec.alpha](https://clojure.org/guides/spec).

Releases and Dependency Information
----------------------------------------

Latest stable release is **2.5.1**.

[Leiningen] dependency information:

    [congomongo "2.5.1"]

[Clojure CLI]:

    congomongo {:mvn/version "2.5.1"}

[Maven] dependency information:

    <dependency>
      <groupId>congomongo</groupId>
      <artifactId>congomongo</artifactId>
      <version>2.5.1</version>
    </dependency>

[Gradle] dependency information:

    compile 'congomongo:congomongo:2.5.1'

[Leiningen]: https://leiningen.org/
[Clojure CLI]: https://clojure.org/guides/deps_and_cli
[Maven]: https://maven.apache.org/
[Gradle]: https://www.gradle.org/

Basics
--------

### Setup

#### import
```clojure
(ns my-mongo-app
  (:require [somnium.congomongo :as m]))
```
#### make a connection using array of hosts
```clojure
(def conn
  (m/make-connection "mydb"
                     :instances [{:host "127.0.0.1" :port 27017}]))
=> #'user/conn

conn => {:mongo #<MongoClient Mongo: /127.0.0.1:20717>, :db #<DBApiLayer mydb>}
```
#### or using a mongodb uri
```clojure
(def conn
  (m/make-connection "mongodb://127.0.0.1/mydb"))
=> #'user/conn

conn => {:mongo #<MongoClient Mongo: /127.0.0.1:20717>, :db #<DBApiLayer mydb>}
```
#### set the connection globally
```clojure
(m/set-connection! conn)
```
#### or locally
```clojure
(m/with-mongo conn
    (m/insert! :robots {:name "robby"}))
```
#### close the connection
```clojure
(m/close-connection conn)
```
#### specify a write concern
```clojure
(m/set-write-concern conn :journaled)
```
These are the official write concerns, more details about them can be found in the [WriteConcern javadoc](https://mongodb.github.io/mongo-java-driver/3.6/javadoc/com/mongodb/WriteConcern.html).
* `:unacknowledged` will report network errors - but does not wait for the write to be acknowledged
* `:acknowledged` will report key constraint and other errors - this is the default
* `:journaled` waits until the primary has sync'd the write to the journal
* `:fsynced` waits until a write is sync'd to the filesystem
* `:replica-acknowledged` waits until a write is sync'd to at least one replica as well
* `:majority` waits until a write is sync'd to a majority of replica nodes

#### specify a read preference
You can pass a simple read preference (without tags) to each function accepting read preferences. This may look like:

```clojure
(m/fetch :fruit :read-preference :nearest)
```

to get the fruit from the nearest server. You may create more advances read preferences using the `read-preference` function.

```clojure
(let [p (m/read-preference :nearest {:location "Europe"})]
   (fetch :fruit :read-preference p)
)
```
to be more specific to get the nearest fruit. You may also set a default `ReadPreference` on a per collection or connection basis using `set-read-preference` or `set-collection-read-preference!`.

```clojure
(m/set-read-preference conn :primary-preferred)
(m/set-collection-read-preference! :news :secondary)
```


### Simple Tasks
------------------

#### create
```clojure
(m/insert! :robots
           {:name "robby"})
```
#### read
```clojure
(def my-robot (m/fetch-one :robots)) => #'user/my-robot

my-robot => {:name "robby",
             :_id  #<ObjectId> "0c23396f7e53e34a4c8cf400">}
```
#### update
Update query and modifier can use all [mongodb operators](https://docs.mongodb.com/manual/reference/operator/)
```clojure
(m/update! :robots
  {:_id (:_id my-robot)}
  {:$set {:name "asimo"}})

=>  #<WriteResult { "serverUsed" : "/127.0.0.1:27017" ,
                    "updatedExisting" : true ,
                    "n" : 1 ,
                    "connectionId" : 169 ,
                    "err" :  null  ,
                    "ok" : 1.0}>
```
#### destroy
```clojure
(m/destroy! :robots {:name "asimo"}) => #<WriteResult { "serverUsed" : "/127.0.0.1:27017" ,
                                                        "n" : 1 ,
                                                        "connectionId" : 170 ,
                                                        "err" :  null  ,
                                                        "ok" : 1.0}>
(m/fetch :robots) => ()
```
### More Sophisticated Tasks
----------------------------

#### mass inserts
```clojure
(dorun (m/mass-insert!
         :points
         (for [x (range 100) y (range 100)]
           {:x x
            :y y
            :z (* x y)}))

=> nil ;; without dorun this would produce a WriteResult with 10,000 maps in it!

(m/fetch-count :points)
=> 10000
```

#### ad-hoc queries
```clojure
(m/fetch-one
  :points
  :where {:x {:$gt 10
              :$lt 20}
          :y 42
          :z {:$gt 500}})

=> {:x 12, :y 42, :z 504, :_id ... }
```

#### aggregation (requires mongodb 2.2 or later)
```clojure
(m/aggregate
  :expenses
  {:$match {:type "airfare"}}
  {:$project {:department 1, :amount 1}}
  {:$group {:_id "$department", :average {:$avg "$amount"}}})

=> {:serverUsed "...", :result [{:_id ... :average ...} {:_id ... :average ...} ...], :ok 1.0}
```
This pipeline of operations selects expenses with type = 'airfare', passes just the department and amount fields thru, and groups by department with an average for each.

Based on [10gen's Java Driver example of aggregation](https://www.mongodb.org/display/DOCS/Using+The+Aggregation+Framework+with+The+Java+Driver).

The aggregate function accepts any number of pipeline operations.

#### authentication
```clojure
(m/authenticate conn "myusername" "my password")

=> true
```

#### advanced initialization using mongo-options
```clojure
(m/make-connection :mydb
                   :instances [{:host "127.0.0.1"}]
                   :options (m/mongo-options :auto-connect-retry true))
```
The available options are hyphen-separated lowercase keyword versions of the camelCase options supported by the Java driver. Prior to CongoMongo 0.4.0, the options matched the fields in the *MongoOptions* class. As of CongoMongo 0.4.0, the options match the method names in the *MongoClientOptions* class instead (and an *IllegalArgumentException* will be thrown if you use an illegal option). The full list (with the 2.10.1 Java driver) is:
```clojure
(:auto-connect-retry :connect-timeout :connections-per-host :cursor-finalizer-enabled
 :db-decoder-factory :db-encoder-factory :description :legacy-defaults
 :max-auto-connect-retry-time :max-wait-time :read-preference :socket-factory
 :socket-keep-alive :socket-timeout :threads-allowed-to-block-for-connection-multiplier
 :write-concern)
```

#### specifying authentication mechanism and source

```clojure
(m/make-connection :mydb
                   :instances [{:host "127.0.0.1"}]
                   :username "user"
                   :password "password"
                   :auth-source {:mechanism :scram-1 :source "authsourcedb"})
```
Supported authentication mechanisms:

| Key | Mechanism |
| --- | --- |
| `:plain` | AuthenticationMechanism.PLAIN |
| `:scram-1` | AuthenticationMechanism.SCRAM_SHA_1 |
| `:scram-256` | AuthenticationMechanism.SCRAM_SHA_256 |

#### initialization using a Mongo URI
```clojure
(m/make-connection "mongodb://user:pass@host:27071/databasename")
;; note that authentication is handled when given a user:pass@ section

;; or using SRV DNS service detection
(m/make-connection "mongodb+srv://user:pass@mymongocluster/databasename")

```

A query string may also be specified containing the options supported by the *MongoClientURI* class.

#### easy json
```clojure
(m/fetch-one :points
             :as :json)

=> "{ \"_id\" : \"0c23396ffe79e34a508cf400\" ,
      \"x\" : 0 , \"y\" : 0 , \"z\" : 0 }"
```

#### custom type conversions

For example, use Joda types for dates:

```clojure
(extend-protocol somnium.congomongo.coerce.ConvertibleFromMongo
  Date
  (mongo->clojure [^java.util.Date d keywordize] (new org.joda.time.DateTime d)))

(extend-protocol somnium.congomongo.coerce.ConvertibleToMongo
  org.joda.time.DateTime
  (clojure->mongo [^org.joda.time.DateTime dt] (.toDate dt)))
```

#### explain
Use :explain? on fetch to get performance information about a query. Returns a map of statistics about the query, not rows:

```clojure
(m/fetch :users :where {:login "alice"} :explain? true)
{:nscannedObjects 2281,
 :nYields 0,
 :nscanned 2281,
 :millis 2,
 :isMultiKey false,
 :cursor "BasicCursor",
 :n 1,
 :indexOnly false,
 :allPlans [{:cursor "BasicCursor", :indexBounds {}}],
 :nChunkSkips 0,
 :indexBounds {},
 :oldPlan {:cursor "BasicCursor", :indexBounds {}}}
```

#### default query options
Sometimes it's very helpful to be able to provide default options for all queries (for example, specify default
`max-time-ms` timeout) instead of specifying it for each call. You can do this by wrapping your code in the
`with-default-query-options` macro. Options specified in a particular call will have a priority and overwrite
`default-query-options`.

```clojure
; This call will only return one item, since it is using the default options
(with-default-query-options {:limit 1}
  (fetch :thingies :where {:foo 1}))

; You can also override the defaults by specifying a new value
(with-default-query-options {:limit 1}
  (fetch :thingies :where {:foo 1} :limit 2)) ; returns 2 items
```

Developer information
---------------------

* [GitHub project](https://github.com/congomongo/congomongo)
* [Google Group](https://groups.google.com/group/congomongo-dev)
* [Continuous Integration](https://circleci.com/gh/congomongo/congomongo)

Change Log
----------

[Read the change log](CHANGES.md)

Discussions and contributions
-----------------------------

There is now a [Google Group](https://groups.google.com/group/congomongo-dev)
Come help us make ponies for great good.

Clojars group is congomongo.

License and copyright
---------------------

Congomongo is made available under the terms of an
[MIT-style license](LICENSE). Please refer to the source code for
the full text of this license and for copyright details.
