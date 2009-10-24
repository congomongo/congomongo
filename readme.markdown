congomongo
===========

What?
------
A toolkit for using MongoDB with Clojure.

Summary
---------
Provides a convenience wrapper around a subset of methods in the
mongodb-java-driver. It also introduces some coercion utilities 
for convenient serialization of Clojure data-structures. It is 
still pre-alpha so the api is subject to change. Bug reports and
patches are welcome!

Basics
--------

### Setup

    (ns my-mongo-app  
      (:use somnium.congomongo))  
    (mongo!  
      :db "mydb") 

### Simple Tasks
------------------

#### create

    (insert! :robots    
             {:name "robby"}

#### read

    (def my-robot (fetch-one :robots)) => #'user/my-robot

    my-robot => { :name "robby", 
                  :_id  #<ObjectId 0c23396f7e53e34a4c8cf400>, 
                  :_ns  "robots"}

#### update

    (update! (merge my-robot { :name "asimo" }))

    =>  #<BasicDBObject { "name" : "asimo" , 
                          "_id" : "0c23396f7e53e34a4c8cf400" , 
                          "_ns" : "robots"}>

#### destroy

    (destroy! my-robot) => nil
    (fetch :robots) => ()

### More Sophisticated Tasks
----------------------------

#### mass inserts

    (mass-insert!  
      :points
      (for [x (range 100) y (range 100)] 
        {:x x :y y :z (* x y)}) 

     =>  nil

    (fetch-count :points)
    => 10000

#### ad-hoc queries

    (fetch-one
      :points
      :where {:x {'> 10  
                  '< 20}
              :y 42
              :z '>500})

    => {:x 12, :y 42, :z 504,  :_ns "points", :_id ... }

#### nested queries with regular expressions

     ;; let's make some documents with strings in an embedded document

    (let [first-names ["bob" "joe" "mary" "sue" "jack" "jill"]
          last-names  ["smith" "holmes" "churchhill" "miyazaki"]]
          (mass-insert! :people
                        (for [a first-names
                              b last-names ]
                          {:name {:first a 
                                  :last b }}))])  =>  nil
 
    (fetch-one :people 
               :where {:name.first #".*ob$"
                       :name.last  #".*yaz.*"})

    => {:name {:first "bob", 
               :last "miyazaki", 
               :_ns "people", 
               :_id #<ObjectId 0c23396f7b83e34a63b3f400> }}

#### Some Handy Coercions
------------------------------------------------------------------------

    (fetch-one :points 
               :as :json)

    => "{ \"_id\" : \"0c23396ffe79e34a508cf400\" , 
          \"x\" : 0 , \"y\" : 0 , \"z\" : 0 , \"_ns\" : \"points\"}"

    (fetch-one :points 
               :where {:x 5}
               :as :mongo)
    #<BasicDBObject { "x" : 0 , "y" : 0 , "z" : 0 , 
                      "_id" : "0c23396ffe79e34a508cf400" , 
                      "_ns" : "points"}>

    ;; MongoDB and Clojure make a nice pair, don't you think?

More About Coercions
--------------------

  The mongodb-java-driver will serialize any collections that
implement java.util.Map or java.util.List. That covers most
of clojure already, for convenience congomongo coerces all keywords
to strings on insert, and coerces map keys back to keywords
on fetch (unless you're fetching json).

  It also coerces query shortcuts (like `'>`) to their Mongo form
("$gt"). The current list includes:

      '<      "$lt"
      '<=     "$lte"
      '>      "$gt"
      '>=     "$gte"
      '!=     "$ne"
      'in     "$in"
      '!in    "$nin"
      'mod    "$mod"
      'all    "$all"
      'size   "$size"
      'exists "$exists"
      'where  "$where

  Strings mapped to keys that begin with an underscore and end with id
are coerced to com.mongodb.ObjectId instances. This comes in handy for
querying by object-id in case you're in relational mood.

  If all this coercion happens to bother you, it's easy to turn it off:

    (mongo!
      :db "my-db"
      :coerce-to   []
      :coerce-from [])

  You can also write your own coercions using the defcoercion macro in
congomongo.coerce. See the source for details.
   
Dependencies
------------

Congomongo requires the mongodb-java-driver and clojure on your classpath.
A working jar of the mongodb-java-api is included in deps, but you can
also get the latest one [here](http://www.github.com/mongodb/mongo-java-driver).   
Just in case you don't have clojure try looking [here](http://www.github.com/richhickey/clojure).

Building congomongo
-------

You will need a recent version of the mongo-java-driver due to a bug interfering  
with serialization of nested maps in earlier versions. 

A working jar is included in deps.

In the congomongo root directory type:

    ant -Dclojure.jar=<path/to/my/clojure.jar>

and voila, you should find a shiny new congomongo jar that's ready   
and raring to get on the classpath.

TODO
----

* Full test coverage for core functionality
* MapReduce
* inline coercions
* concurrency
* validations?
* collection specific attributes?

### Feedback

CongoMongo is a work in progress. If you've used, improved, 
or abused it I'd love to hear about it. Contact me at somnium@gmx.us
