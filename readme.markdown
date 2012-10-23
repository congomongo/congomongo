CongoMongo <a href="http://travis-ci.org/#!/seancorfield/congomongo/builds"><img src="https://secure.travis-ci.org/seancorfield/congomongo.png" /></a>
===========

What?
------
A toolkit for using MongoDB with Clojure.

For Clojure 1.2.1 and earlier, use CongoMongo 0.2.2 or earlier. CongoMongo 0.2.2 is the last release that will support Clojure 1.2.x. CongoMongo 0.3.0 onward no longer supports Clojure 1.2.x.

News
--------------
Version 0.3.1 - October 23rd, 2012

* Update Java driver to 2.9.2 for CRITICAL update (#98)

Version 0.3.0 - October 23rd, 2012

* DROP SUPPORT FOR CLOJURE 1.2.1 AND EARLIER!
* Update clojure.data.json to 0.2.0 (#97)
* Update clojure.core.incubator to 0.1.2

Version 0.2.2 - October 23rd, 2012 - last release to support Clojure 1.2.x!

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
  (:use somnium.congomongo))
```
#### make a connection
```clojure
(def conn
  (make-connection "mydb"
                   :host "127.0.0.1"
                   :port 27017))
=> #'user/conn

conn => {:mongo #<Mongo Mongo: 127.0.0.1:20717>, :db #<DBApiLayer mydb>}
```
#### set the connection globally
```clojure
(set-connection! conn)
```
#### or locally
```clojure
(with-mongo conn
    (insert! :robots {:name "robby"}))
```
#### specify a write concern (if you want errors reported)
```clojure
(set-write-concern conn :safe)
;; :none will not report any errors
;; :normal will report network errors
;; :safe will report key constraint and other errors
;; :fsync-safe waits until a write is sync'd to the filesystem
;; :replica-safe waits until a write is sync'd to at least one replica as well
;; :strict is a synonym for :safe but is deprecated (as of 0.1.9)
```
### Simple Tasks
------------------

#### create
```clojure
(insert! :robots
         {:name "robby"})
```
#### read
```clojure
(def my-robot (fetch-one :robots)) => #'user/my-robot

my-robot => { :name "robby",
              :_id  #<ObjectId> "0c23396f7e53e34a4c8cf400">,
              :_ns  "robots"}
```
#### update
```clojure
(update! :robots my-robot (merge my-robot { :name "asimo" }))

=>  { :name "asimo" ,
      :_id  #<ObjectId> "0c23396f7e53e34a4c8cf400"> ,
      :_ns : "robots" }
```
#### destroy
```clojure
(destroy! :robots my-robot) => nil
(fetch :robots) => ()
```
### More Sophisticated Tasks
----------------------------

#### mass inserts
```clojure
(mass-insert!
  :points
  (for [x (range 100) y (range 100)]
    {:x x
     :y y
     :z (* x y)}))

 =>  nil

(fetch-count :points)
=> 10000
```
#### ad-hoc queries
```clojure
(fetch-one
  :points
  :where {:x {:$gt 10
              :$lt 20}
          :y 42
          :z {:$gt 500}})

=> {:x 12, :y 42, :z 504,  :_ns "points", :_id ... }
```

#### aggregation (requires mongodb 2.2)
```clojure
(aggregate
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
(authenticate conn "myusername" "my password")

=> true
```
#### advanced initialization using mongo-options
```clojure
(make-connection :mydb :host "127.0.0.1" (mongo-options :auto-connect-retry true))
```
#### initialization using a Mongo URI
```clojure
(make-connection "mongodb://user:pass@host:27071/databasename")
;note that authentication is handled when given a user:pass@ section
```
#### easy json
```clojure
(fetch-one :points
           :as :json)

=> "{ \"_id\" : \"0c23396ffe79e34a508cf400\" ,
      \"x\" : 0 , \"y\" : 0 , \"z\" : 0 , \"_ns\" : \"points\"}"
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

Install
-------

Leiningen is the recommended way to use congomongo.
Just add

    [congomongo "0.2.0"]

to your project.clj and do

    $lein deps

to get congomongo and all of its dependencies.

### Feedback

CongoMongo is a work in progress. If you've used, improved,
or abused it tell us about it at our [Google Group](http://groups.google.com/group/congomongo-dev).

### License and copyright

Congomongo is made available under the terms of an MIT-style
license. Please refer to the source code for the full text of this
license and for copyright details.

