# Version 2.2.2 - Aug 3, 2021

Fix some reflection warnings in preparation for upgrade to MongoDB 4.x driver

# Version 2.2.1 - Jan 23, 2020

Fix some reflection warnings to improve performance

# Version 2.2.0 - Nov 26, 2019

Adds support for authentication source using one of the following authentication mechanisms:
  AuthenticationMechanism.PLAIN,
  AuthenticationMechanism.SCRAM_SHA_1,
  AuthenticationMechanism.SCRAM_SHA_256

# Version 2.1.0 - Nov 25, 2019

Adds support for setting default query options, see documentation above.

# Version 2.0.0 - Nov 22, 2019

BREAKING CHANGES IN THIS RELEASE!
The `group`, `eval` and `geoNear` commands have been deprecated for a long while now and were finally removed
in MongoDB 4.2. They have now been removed from this library as well. You are recommended to use the `aggregate`
command to replace the functionality of `group` and `geoNear`.

# Version 1.1.0 - Apr 8, 2019

Added ability to specify timeout for fetch and fetch-and-modify operations.

# Version 1.0.1 - Sep 11, 2018

Fixed bug where too few documents were returned if fetching with a limit that was larger than the
default batch size.

# Version 1.0.0 - Sep 11, 2018

Updated to support mongo-java-driver 3.0+ which enables use of TLS connections and DNS SRV connection strings.
Authentication has changed significantly in the 3.0 driver which necessitated some breaking API changes around connecting and authenticating.

BREAKING CHANGES IN THIS RELEASE!
* Usernames and passwords for authenticated connections must be supplied to `make-connection` rather than authenticating after the connection has been created. The `make-connection` API has been changed to accomodate this. Optional parameters (instances, Mongo options, username and password) are now passed via keyword args.
* The `authenticate` function has been removed.
* The deprecated `mongo!` function has been removed. Use `(set-connection (make-connection ...))`

# Version 0.5.3 - Aug 30, 2018

* Add new option `:partial-filter-expression` to `add-index!` in order to support partial indexes.

# Version 0.5.2 - May 2, 2018

* Make aggregate method compatible with MongoDB 3.6

# Version 0.5.1 - Jan 19, 2018

* Add support for setting `_id` when creating GridFS files
* Update Java driver to 2.14.2

# Version 0.5.0 - Jun 6, 2016

* DROP SUPPORT FOR CLOJURE 1.3.0! CongoMongo 0.5.0 onward no longer supports Clojure 1.3.0 or earlier. For Clojure 1.3.0, use CongoMongo 0.3.0 thru 0.4.8. CongoMongo 0.4.8 is the last release that supports Clojure 1.3.0. For Clojure 1.2.1 and earlier, use CongoMongo 0.2.3 or earlier. CongoMongo 0.2.3 is the last release that supports Clojure 1.2.x.
* Add Clojure 1.9.0 compatibility (handling of seqable? in coerce namespace) - issue #147
* Clojure is now a "provided" dependency and should no longer appear as a transitive dependency in projects that use CongoMongo, making it easier to get rid of conflicts! _Potentially a breaking change if a project depended on CongoMongo but did not have an explicit dependency on Clojure itself._

# Version 0.4.8 - Feb 25, 2016

* Update clojure.data.json to 0.2.6
* Update default Clojure version to 1.8.0 and update test-all alias
* Rename `update` test to `update-one` to avoid Clojure `update` name conflict

# Version 0.4.7 - Dec 27, 2015

* Update Java driver to 2.14.0

# Version 0.4.6 - Jul 29, 2015

* Add support for hints on fetches
* Error on unsupported arguments (eg read-preferences on fetch-one)

# Version 0.4.5 - Jul 18, 2015

* update Java driver to 2.13.2
* change default Clojure version from 1.6.0 to 1.7.0 (we still support back to 1.3.0)

# Version 0.4.4 - April 29, 2014

* update Java driver to 2.12.1

# Version 0.4.3 - April 8, 2014

* change default Clojure version from 1.5.1 to 1.6.0 (we still support back to 1.3.0)
* update Java driver to 2.12.0 to support MongoDB 2.6.0
* support `:write-concern` on `mass-insert!`

# Version 0.4.2 - February 25, 2014

* change default Clojure version from 1.4 to 1.5.1 and add test coverage for 1.6.0-beta1
* fix set insertion test for Clojure 1.6
* fix docstring (#130 dwwoelfel)
* fix reflection warnings (#123 niclasmeier)

# Version 0.4.1 - March 14, 2013

* read preference supported (per-connection, per-collection, per-fetch (#122 niclasmeier)
* add-index! supports :background true/false (#121 dwwoelfel)
* namespaced keyword keys in maps are roundtripped correctly (#120 AdamClements)

# Version 0.4.0 - January 4, 2013

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

# Version 0.3.3 - November 2nd, 2012

* Add dbobject and coerce-ordered-fields to support multi-column sorting (#100)
  * Deprecate coerce-index-fields in favor of coerce-ordered-fields

# Version 0.3.2 - October 30th, 2012

* Update Java driver to 2.9.3 for recommended update (#99)

# Version 0.3.1 - October 23rd, 2012

* Update Java driver to 2.9.2 for CRITICAL update (#98)

# Version 0.3.0 - October 23rd, 2012

* DROP SUPPORT FOR CLOJURE 1.2.1 AND EARLIER!
* Update clojure.data.json to 0.2.0 (#97)
* Update clojure.core.incubator to 0.1.2

# Version 0.2.3 - October 30th, 2012 - last release to support Clojure 1.2.x!

* Update Java driver to 2.9.3 for recommended update (#99)

# Version 0.2.2 - October 23rd, 2012

* Update Java driver to 2.9.2 for CRITICAL update (#98)

# Version 0.2.1 - October 23rd, 2012

* Support insertion of sets (#94, #95)
* Declare MongoDB service for Travis CI (#96)

# Version 0.2.0 - October 10th, 2012:

* Added URL / license / mailing list information to project.clj so it will show up in Clojars and provide a better user experience
* Allow make-connection to accept symbols again (#80, fixes issue introduced in #79)
* Prevent fetch one / sort being used together (#81)
* Remove :force option from add-index! since it is no longer effective (#82, #83)
* Add :sparse option to add-index! (#84)
* Upgrade to 2.9.1 Java driver (#85, #89)
* Upgrade project to use Clojure 1.4.0 as base version (#86, #88)
* Upgrade project to use Leiningen 2 (#87)
* Add aggregate function to leverage MongoDB 2.2 aggregation framework (#90)

# Version 0.1.10 - July 31st, 2012:

* Add support for MongoDB URI string in make-connection (#79)
* Fix with-connection / with-db interaction (#75)

# Version 0.1.9 - April 20th, 2012:

* Bump data.json => 0.1.3
* Bump multi test to 1.4.0 & 1.5.0-SNAPSHOT for Clojure
* Add with-db macro (#53, #54)
* Support vector :only in fetch-and-modify (to match fetch) (#65)
* Add group aggregation (#66)
* Allow insert! to respect previous set-write-concern call (#72)
* Add :safe, :fsync-safe, :replica-safe write concerns (#72)
* In order to get throw on error behavior, you must call set-write-concern with :safe or stricter!
* Deprecate :strict - use :safe instead

# Version 0.1.8:

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
