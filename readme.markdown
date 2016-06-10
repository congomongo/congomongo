CongoMongo <a href="http://travis-ci.org/#!/seancorfield/congomongo/builds"><img src="https://secure.travis-ci.org/seancorfield/congomongo.png" /></a>
===========

What?
------
A toolkit for using MongoDB with Clojure.

CongoMongo 0.5.0 onward no longer supports Clojure 1.3.0 or earlier.
For Clojure 1.3.0, use CongoMongo 0.3.0 thru 0.4.8. CongoMongo 0.4.8 is the last release that supports Clojure 1.3.0.
For Clojure 1.2.1 and earlier, use CongoMongo 0.2.3 or earlier. CongoMongo 0.2.3 is the last release that supports Clojure 1.2.x.

News
--------------
Version 0.5.0 - Jun 6th, 2016

* DROP SUPPORT FOR CLOJURE 1.3.0!
* Add Clojure 1.9.0 compatibility (handling of seqable? in coerce namespace) - issue #147
* Clojure is now a "provided" dependency and should no longer appear as a transitive dependency in projects that use CongoMongo, making it easier to get rid of conflicts! _Potentially a breaking change if a project depended on CongoMongo but did not have an explicit dependency on Clojure itself._

Version 0.4.8 - Feb 25th, 2016

* Update clojure.data.json to 0.2.6
* Update default Clojure version to 1.8.0 and update test-all alias
* Rename `update` test to `update-one` to avoid Clojure `update` name conflict

Version 0.4.7 - Dec 27th, 2015

* Update Java driver to 2.14.0

Version 0.4.6 - Jul 29th, 2015

* Add support for hints on fetches
* Error on unsupported arguments (eg read-preferences on fetch-one)

Version 0.4.5 - Jul 18th, 2015

* update Java driver to 2.13.2
* change default Clojure version from 1.6.0 to 1.7.0 (we still support back to 1.3.0)

Version 0.4.4 - April 29th, 2014

* update Java driver to 2.12.1

Version 0.4.3 - April 8th, 2014

* change default Clojure version from 1.5.1 to 1.6.0 (we still support back to 1.3.0)
* update Java driver to 2.12.0 to support MongoDB 2.6.0
* support `:write-concern` on `mass-insert!`

Version 0.4.2 - February 25th, 2014

* change default Clojure version from 1.4 to 1.5.1 and add test coverage for 1.6.0-beta1
* fix set insertion test for Clojure 1.6
* fix docstring (#130 dwwoelfel)
* fix reflection warnings (#123 niclasmeier)

Version 0.4.1 - March 14th, 2013

* read preference supported (per-connection, per-collection, per-fetch (#122 niclasmeier)
* add-index! supports :background true/false (#121 dwwoelfel)
* namespaced keyword keys in maps are roundtripped correctly (#120 AdamClements)

Version 0.4.0 - January 4th, 2013

BREAKING CHANGES IN THIS RELEASE!

10gen have updated all their drivers to be more consistent in naming. They have also changed the default write concern (from :none to :normal, effectively). The new classes introduced have different APIs to the classes they replace so there are some knock on changes to CongoMongo as well. The biggest changes are that *opt!* has been removed and the actual keyword arguments for *MongoOptions* have changed to match *MongoClientOptions$Builder* instead. An *IllegalArgumentException* is thrown for unknown arguments now.

* You can now pass *:write-concern* to *destroy!*, *insert!* and *update!* #74
* Upgrade to 2.10.1 Java driver (#104)
  * Switches from Mongo to MongoClient
  * Switches from MongoURI to MongoClientURI
  * Switches from MongoOptions to MongoClientOptions
  * Adds seven new write concern names - the old names are deprecated (see set-write-concern below)
  * Changes the default write concern from :unacknowledged (formerly called :normal) to :acknowledged (formerly called :safe or :strict)
* Update clojure.data.json to 0.2.1 (as part of #104)
* Add :replicas-safe write concern (although it is deprecated)
* Add support for :explain? (#102, #103 arohner)
* Switch fetch to use non-deprecated APIs (#101 arohner)

Version 0.3.3 - November 2nd, 2012

* Add dbobject and coerce-ordered-fields to support multi-column sorting (#100)
  * Deprecate coerce-index-fields in favor of coerce-ordered-fields

Version 0.3.2 - October 30th, 2012

* Update Java driver to 2.9.3 for recommended update (#99)

Version 0.3.1 - October 23rd, 2012

* Update Java driver to 2.9.2 for CRITICAL update (#98)

Version 0.3.0 - October 23rd, 2012

* DROP SUPPORT FOR CLOJURE 1.2.1 AND EARLIER!
* Update clojure.data.json to 0.2.0 (#97)
* Update clojure.core.incubator to 0.1.2

Version 0.2.3 - October 30th, 2012 - last release to support Clojure 1.2.x!

* Update Java driver to 2.9.3 for recommended update (#99)

Version 0.2.2 - October 23rd, 2012

* Update Java driver to 2.9.2 for CRITICAL update (#98)

Version 0.2.1 - October 23rd, 2012

* Support insertion of sets (#94, #95)
* Declare MongoDB service for Travis CI (#96)

Version 0.2.0 - October 10th, 2012:

* Added URL / license / mailing list information to project.clj so it will show up in Clojars and provide a better user experience
* Allow make-connection to accept symbols again (#80, fixes issue introduced in #79)
* Prevent fetch one / sort being used together (#81)
* Remove :force option from add-index! since it is no longer effective (#82, #83)
* Add :sparse option to add-index! (#84)
* Upgrade to 2.9.1 Java driver (#85, #89)
* Upgrade project to use Clojure 1.4.0 as base version (#86, #88)
* Upgrade project to use Leiningen 2 (#87)
* Add aggregate function to leverage MongoDB 2.2 aggregation framework (#90)

Version 0.1.10 - July 31st, 2012:

* Add support for MongoDB URI string in make-connection (#79)
* Fix with-connection / with-db interaction (#75)

Version 0.1.9 - April 20th, 2012:

* Bump data.json => 0.1.3
* Bump multi test to 1.4.0 & 1.5.0-SNAPSHOT for Clojure
* Add with-db macro (#53, #54)
* Support vector :only in fetch-and-modify (to match fetch) (#65)
* Add group aggregation (#66)
* Allow insert! to respect previous set-write-concern call (#72)
* Add :safe, :fsync-safe, :replica-safe write concerns (#72)
* In order to get throw on error behavior, you must call set-write-concern with :safe or stricter!
* Deprecate :strict - use :safe instead

Version 0.1.8:

* adds fetch-by-ids (#44)
* improves error handling when connection not set up (#42)
* updates clojure.data.json to 0.1.2 (for performance fixes)
* numerous documentation fixes (#38, #39, #40, #41, #45)
* update to 2.7.3 driver (#46, #48)
* remove version ranges - make 1.3.0 the default Clojure version (#47 etc)
* add multi-version testing
* add Travis CI test hook

Version 0.1.7 adds the ability to create MongoOptions and pass them
into make-connection as the last argument, so that you can control
autoConnectRetry and timeouts and so on. This release also fixes a
number of small bugs around type hints introduced in 0.1.6; corrects
the upsert(?) parameter in fetch-and-modify; upgrades the Java driver
to 2.6.5. The :only parameter can now be a map of field names and
true / false values to allow fields to be included or excluded. The
original vector of field names is still supported to include only
the named fields.

Version 0.1.6 removes (almost) all of the reflection warnings.

Version 0.1.5 adds compatibility with Clojure 1.3, in addition
to 1.2.

Congomongo 0.1.4 introduces support for MongoDB 1.8's modified
map-reduce functionality, wherein the 'out' parameter is
required. With this and future Congomongo releases, it will no longer
be possible to access the map-reduce features of older MongoDB
instances.

As of congomongo 0.1.3, Clojure 1.2 and Clojure-contrib 1.2 are required.
If you need compatibility with Clojure 1.1,
please stick with congomongo 0.1.2.

There is now a [Google Group](http://groups.google.com/group/congomongo-dev)
Come help us make ponies for great good.

Clojars group is congomongo.

=======

CongoMongo is essentially a Clojure api for the mongo-java-driver,
transparently handling coercions between Clojure and Java data types.

Basics
--------

### Setup

#### import
```clojure
(ns my-mongo-app
  (:require [somnium.congomongo :as m]))
```
#### make a connection
```clojure
(def conn
  (m/make-connection "mydb"
                     :host "127.0.0.1"
                     :port 27017))
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
These are the new, official write concerns as of release 0.4.0, using the 2.10 Java
driver. The earlier write concerns are shown in parentheses and are deprecated as
of the 0.4.0 release.
* :errors-ignored will not report any errors - fire and forget (:none)
* :unacknowledged will report network errors - but does not wait for the write to be acknowledged (:normal - this was the default prior to 0.4.0)
* :acknowledged will report key constraint and other errors - this is the default (:safe, :strict was deprecated in 0.1.9)
* :journaled waits until the primary has sync'd the write to the journal (:journal-safe)
* :fsynced waits until a write is sync'd to the filesystem (:fsync-safe)
* :replica-acknowledged waits until a write is sync'd to at least one replica as well (:replicas-safe, :replica-safe)
* :majority waits until a write is sync'd to a majority of replica nodes (no previous equivalent)

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
```clojure
(m/update! :robots my-robot (merge my-robot {:name "asimo"}))

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

Based on [10gen's Java Driver example of aggregation](http://www.mongodb.org/display/DOCS/Using+The+Aggregation+Framework+with+The+Java+Driver).

The aggregate function accepts any number of pipeline operations.

#### authentication
```clojure
(m/authenticate conn "myusername" "my password")

=> true
```
#### advanced initialization using mongo-options
```clojure
(m/make-connection :mydb :host "127.0.0.1" (m/mongo-options :auto-connect-retry true))
```
The available options are hyphen-separated lowercase keyword versions of the camelCase options supported by the Java driver. Prior to CongoMongo 0.4.0, the options matched the fields in the *MongoOptions* class. As of CongoMongo 0.4.0, the options match the method names in the *MongoClientOptions* class instead (and an *IllegalArgumentException* will be thrown if you use an illegal option). The full list (with the 2.10.1 Java driver) is:
```clojure
(:auto-connect-retry :connect-timeout :connections-per-host :cursor-finalizer-enabled
 :db-decoder-factory :db-encoder-factory :description :legacy-defaults
 :max-auto-connect-retry-time :max-wait-time :read-preference :socket-factory
 :socket-keep-alive :socket-timeout :threads-allowed-to-block-for-connection-multiplier
 :write-concern)
```
#### initialization using a Mongo URI
```clojure
(m/make-connection "mongodb://user:pass@host:27071/databasename")
;; note that authentication is handled when given a user:pass@ section
```

A query string may also be specified containing the options supported by the *MongoClientURI* class (as of CongoMongo 0.4.0; previously the *MongoURI* class was used).
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

Install
-------

Leiningen is the recommended way to use congomongo.
If you are using Clojure 1.4.0 or later, just add

    [congomongo "0.5.0"]

to your project.clj (for the latest stable version).

If you are still on Clojure 1.3.0, use CongoMongo 0.3.0-0.4.8.
If you are still on Clojure 1.2.x, use CongoMongo version 0.2.3 instead.

### Feedback

CongoMongo is a work in progress. If you've used, improved,
or abused it tell us about it at our [Google Group](http://groups.google.com/group/congomongo-dev).

### License and copyright

Congomongo is made available under the terms of an MIT-style
license. Please refer to the source code for the full text of this
license and for copyright details.
