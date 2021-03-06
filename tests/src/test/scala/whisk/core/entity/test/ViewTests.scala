/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.entity.test

import java.time.Clock
import java.time.Instant

import scala.concurrent.Await
import scala.language.postfixOps
import scala.util.Try

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import common.StreamLogging
import common.WskActorSystem
import spray.json.JsObject
import whisk.core.WhiskConfig
import whisk.core.controller.test.WhiskAuthHelpers
import whisk.core.database.ArtifactStore
import whisk.core.database.StaleParameter
import whisk.core.database.test.DbUtils
import whisk.core.entity._
import whisk.core.entity.WhiskEntityQueries._

@RunWith(classOf[JUnitRunner])
class ViewTests extends FlatSpec
    with BeforeAndAfter
    with BeforeAndAfterAll
    with Matchers
    with DbUtils
    with ExecHelpers
    with WskActorSystem
    with StreamLogging {

    def aname = MakeName.next("viewtests")
    def afullname(namespace: EntityPath) = FullyQualifiedEntityName(namespace, aname)

    object MakeName {
        @volatile var counter = 1
        def next(prefix: String = "test")(): EntityName = {
            counter = counter + 1
            EntityName(s"${prefix}_name$counter")
        }
    }

    val creds1 = WhiskAuthHelpers.newAuth(Subject("s12345"))
    val namespace1 = EntityPath(creds1.subject.asString)

    val creds2 = WhiskAuthHelpers.newAuth(Subject("t12345"))
    val namespace2 = EntityPath(creds2.subject.asString)

    val config = new WhiskConfig(WhiskEntityStore.requiredProperties ++ WhiskActivationStore.requiredProperties)
    val entityStore = WhiskEntityStore.datastore(config)
    val activationStore = WhiskActivationStore.datastore(config)

    after {
        cleanup()
    }

    override def afterAll() {
        println("Shutting down store connections")
        entityStore.shutdown()
        activationStore.shutdown()
        super.afterAll()
    }

    behavior of "Datastore View"

    def getAllInNamespace[Au <: WhiskEntity](store: ArtifactStore[Au], ns: EntityPath)(implicit entities: Seq[WhiskEntity]) = {
        implicit val tid = transid()
        val result = Await.result(listAllInNamespace(store, ns, false, StaleParameter.No), dbOpTimeout).values.toList.flatten
        val expected = entities filter { _.namespace.root.toPath == ns }
        result.length should be(expected.length)
        result should contain theSameElementsAs expected.map(_.summaryAsJson)
    }

    def getEntitiesInNamespace(ns: EntityPath)(implicit entities: Seq[WhiskEntity]) = {
        implicit val tid = transid()
        val map = Await.result(listEntitiesInNamespace(entityStore, ns, false), dbOpTimeout)
        val result = map.values.toList flatMap { t => t }
        val expected = entities filter { !_.isInstanceOf[WhiskActivation] } filter { _.namespace.root.toPath == ns }
        map.get(WhiskActivation.collectionName) should be(None)
        result.length should be(expected.length)
        expected forall { e => result contains e.summaryAsJson } should be(true)
    }

    def getKindInNamespace[Au <: WhiskEntity](store: ArtifactStore[Au], ns: EntityPath, kind: String, f: (WhiskEntity) => Boolean)(implicit entities: Seq[WhiskEntity]) = {
        implicit val tid = transid()
        val result = Await.result(listCollectionInNamespace(store, kind, ns, 0, 0, stale = StaleParameter.No, convert = None) map { _.left.get map { e => e } }, dbOpTimeout)
        val expected = entities filter { e => f(e) && e.namespace.root.toPath == ns }
        result.length should be(expected.length)
        expected forall { e => result contains e.summaryAsJson } should be(true)
    }

    def getPublicPackages(implicit entities: Seq[WhiskEntity]) = {
        implicit val tid = transid()
        val result = Await.result(listCollectionInAnyNamespace(entityStore, "packages", 0, 0, true, convert = None) map { _.left.get map { e => e } }, dbOpTimeout)
        val expected = entities filter { case (e: WhiskPackage) => e.publish && e.binding.isEmpty case _ => false }
        result.length should be >= (expected.length)
        expected forall { e => result contains e.summaryAsJson } should be(true)
    }

    def getKindInNamespaceWithDoc[T](ns: EntityPath, kind: String, f: (WhiskEntity) => Boolean, convert: Option[JsObject => Try[T]])(implicit entities: Seq[WhiskEntity]) = {
        implicit val tid = transid()
        val result = Await.result(listCollectionInNamespace(entityStore, kind, ns, 0, 0, stale = StaleParameter.No, convert = convert) map { _.right.get }, dbOpTimeout)
        val expected = entities filter { e => f(e) && e.namespace.root.toPath == ns }
        result.length should be(expected.length)
        expected forall { e => result contains e } should be(true)
    }

    def getKindInNamespaceByName[Au <: WhiskEntity](store: ArtifactStore[Au], ns: EntityPath, kind: String, name: EntityName, f: (WhiskEntity) => Boolean)(implicit entities: Seq[WhiskEntity]) = {
        implicit val tid = transid()
        val result = Await.result(listCollectionByName(store, kind, ns, name, 0, 0, stale = StaleParameter.No, convert = None) map { _.left.get map { e => e } }, dbOpTimeout)
        val expected = entities filter { e => f(e) && e.namespace.root.toPath == ns }
        result.length should be(expected.length)
        expected forall { e => result contains e.summaryAsJson } should be(true)
    }

    def getKindInPackage(ns: EntityPath, kind: String, f: (WhiskEntity) => Boolean)(implicit entities: Seq[WhiskEntity]) = {
        implicit val tid = transid()
        val result = Await.result(listCollectionInNamespace(entityStore, kind, ns, 0, 0, stale = StaleParameter.No, convert = None) map { _.left.get map { e => e } }, dbOpTimeout)
        val expected = entities filter { e => f(e) && e.namespace == ns }
        result.length should be(expected.length)
        expected forall { e => result contains e.summaryAsJson } should be(true)
    }

    def getKindInNamespaceByNameSortedByDate[Au <: WhiskEntity](
        store: ArtifactStore[Au], ns: EntityPath, kind: String, name: EntityName, skip: Int, count: Int, start: Option[Instant], end: Option[Instant], f: (WhiskEntity) => Boolean)(
            implicit entities: Seq[WhiskEntity]) = {
        implicit val tid = transid()
        val result = Await.result(listCollectionByName(store, kind, ns, name, skip, count, start, end, StaleParameter.No, convert = None) map { _.left.get map { e => e } }, dbOpTimeout)
        val expected = entities filter { e => f(e) && e.namespace.root.toPath == ns } sortBy { case (e: WhiskActivation) => e.start.toEpochMilli; case _ => 0 } map { _.summaryAsJson }
        result.length should be(expected.length)
        result should be(expected reverse)
    }

    it should "query whisk view by namespace, collection and entity name in whisk actions db" in {
        implicit val tid = transid()
        val exec = bb("image")
        val pkgname1 = namespace1.addPath(aname)
        val pkgname2 = namespace2.addPath(aname)
        val actionName = aname
        def now = Instant.now(Clock.systemUTC())

        // creates 17 entities in each namespace as follows:
        // - 2 actions in each namespace in the default namespace
        // - 2 actions in the same package within a namespace
        // - 1 action in two different packages in the same namespace
        // - 1 action in package with prescribed name
        // - 2 triggers in each namespace
        // - 2 rules in each namespace
        // - 2 packages in each namespace
        // - 2 package bindings in each namespace
        implicit val entities = Seq(
            WhiskAction(namespace1, aname, exec),
            WhiskAction(namespace1, aname, exec),
            WhiskAction(namespace1.addPath(aname), aname, exec),
            WhiskAction(namespace1.addPath(aname), aname, exec),
            WhiskAction(pkgname1, aname, exec),
            WhiskAction(pkgname1, aname, exec),
            WhiskAction(pkgname1, actionName, exec),
            WhiskTrigger(namespace1, aname),
            WhiskTrigger(namespace1, aname),
            WhiskRule(namespace1, aname, trigger = afullname(namespace1), action = afullname(namespace1)),
            WhiskRule(namespace1, aname, trigger = afullname(namespace1), action = afullname(namespace1)),
            WhiskPackage(namespace1, aname),
            WhiskPackage(namespace1, aname),
            WhiskPackage(namespace1, aname, Some(Binding(namespace2.root, aname))),
            WhiskPackage(namespace1, aname, Some(Binding(namespace2.root, aname))),

            WhiskAction(namespace2, aname, exec),
            WhiskAction(namespace2, aname, exec),
            WhiskAction(namespace2.addPath(aname), aname, exec),
            WhiskAction(namespace2.addPath(aname), aname, exec),
            WhiskAction(pkgname2, aname, exec),
            WhiskAction(pkgname2, aname, exec),
            WhiskTrigger(namespace2, aname),
            WhiskTrigger(namespace2, aname),
            WhiskRule(namespace2, aname, trigger = afullname(namespace2), action = afullname(namespace2)),
            WhiskRule(namespace2, aname, trigger = afullname(namespace2), action = afullname(namespace2)),
            WhiskPackage(namespace2, aname),
            WhiskPackage(namespace2, aname),
            WhiskPackage(namespace2, aname, Some(Binding(namespace1.root, aname))),
            WhiskPackage(namespace2, aname, Some(Binding(namespace1.root, aname))))

        entities foreach { put(entityStore, _) }
        waitOnView(entityStore, namespace1, 15)
        waitOnView(entityStore, namespace2, 14)

        getAllInNamespace(entityStore, namespace1)
        getKindInNamespace(entityStore, namespace1, "actions", { case (e: WhiskAction) => true case (_) => false })
        getKindInNamespace(entityStore, namespace1, "triggers", { case (e: WhiskTrigger) => true case (_) => false })
        getKindInNamespace(entityStore, namespace1, "rules", { case (e: WhiskRule) => true case (_) => false })
        getKindInNamespace(entityStore, namespace1, "packages", { case (e: WhiskPackage) => true case (_) => false })
        getKindInPackage(pkgname1, "actions", { case (e: WhiskAction) => true case (_) => false })
        getKindInNamespaceByName(entityStore, pkgname1, "actions", actionName, { case (e: WhiskAction) => (e.name == actionName) case (_) => false })
        getEntitiesInNamespace(namespace1)

        getAllInNamespace(entityStore, namespace2)
        getKindInNamespace(entityStore, namespace2, "actions", { case (e: WhiskAction) => true case (_) => false })
        getKindInNamespace(entityStore, namespace2, "triggers", { case (e: WhiskTrigger) => true case (_) => false })
        getKindInNamespace(entityStore, namespace2, "rules", { case (e: WhiskRule) => true case (_) => false })
        getKindInNamespace(entityStore, namespace2, "packages", { case (e: WhiskPackage) => true case (_) => false })
        getKindInPackage(pkgname2, "actions", { case (e: WhiskAction) => true case (_) => false })
        getEntitiesInNamespace(namespace2)
    }

    it should "query whisk view by namespace, collection and entity name in whisk activations db" in {
        implicit val tid = transid()
        val actionName = aname
        def now = Instant.now(Clock.systemUTC())

        // creates 5 entities in each namespace as follows:
        // - some activations in each namespace (some may have prescribed action name to query by name)
        implicit val entities = Seq(
            WhiskActivation(namespace1, aname, Subject(), ActivationId(), start = now, end = now),
            WhiskActivation(namespace1, aname, Subject(), ActivationId(), start = now, end = now),

            WhiskActivation(namespace2, aname, Subject(), ActivationId(), start = now, end = now),
            WhiskActivation(namespace2, actionName, Subject(), ActivationId(), start = now, end = now),
            WhiskActivation(namespace2, actionName, Subject(), ActivationId(), start = now, end = now))

        entities foreach { put(activationStore, _) }
        waitOnView(activationStore, namespace1, 2)
        waitOnView(activationStore, namespace2, 3)

        getAllInNamespace(activationStore, namespace1)
        getKindInNamespace(activationStore, namespace1, "activations", { case (e: WhiskActivation) => true case (_) => false })

        getAllInNamespace(activationStore, namespace2)
        getKindInNamespace(activationStore, namespace2, "activations", { case (e: WhiskActivation) => true case (_) => false })
        getKindInNamespaceByName(activationStore, namespace2, "activations", actionName, { case (e: WhiskActivation) => (e.name == actionName) case (_) => false })
    }

    it should "query whisk view for activations sorted by date" in {
        implicit val tid = transid()
        val actionName = aname
        val now = Instant.now(Clock.systemUTC())
        val others = Seq(
            WhiskAction(namespace1, aname, jsDefault("??")),
            WhiskAction(namespace1, aname, jsDefault("??")))
        implicit val entities = Seq(
            WhiskActivation(namespace1, actionName, Subject(), ActivationId(), start = now, end = now),
            WhiskActivation(namespace1, actionName, Subject(), ActivationId(), start = now.plusSeconds(20), end = now.plusSeconds(20)),
            WhiskActivation(namespace1, actionName, Subject(), ActivationId(), start = now.plusSeconds(10), end = now.plusSeconds(20)),
            WhiskActivation(namespace1, actionName, Subject(), ActivationId(), start = now.plusSeconds(40), end = now.plusSeconds(20)),
            WhiskActivation(namespace1, actionName, Subject(), ActivationId(), start = now.plusSeconds(30), end = now.plusSeconds(20)))

        others foreach { put(entityStore, _) }
        entities foreach { put(activationStore, _) }
        if (config.dbActivations == config.dbWhisk) {
            waitOnView(entityStore, namespace1, others.length + entities.length)
        } else {
            waitOnView(entityStore, namespace1, others.length)
            waitOnView(activationStore, namespace1, entities.length)
        }

        getKindInNamespaceByNameSortedByDate(activationStore, namespace1, "activations", actionName, 0, 5, None, None, {
            case (e: WhiskActivation) => e.name == actionName
            case (_)                  => false
        })

        val since = entities(1).start
        val upto = entities(4).start
        getKindInNamespaceByNameSortedByDate(activationStore, namespace1, "activations", actionName, 0, 5, Some(since), Some(upto), {
            case (e: WhiskActivation) => e.name == actionName && (e.start.equals(since) || e.start.equals(upto) || (e.start.isAfter(since) && e.start.isBefore(upto)))
            case (_)                  => false
        })
    }

    it should "query whisk and retrieve full documents" in {
        implicit val tid = transid()
        val actionName = aname
        val now = Instant.now(Clock.systemUTC())
        implicit val entities = Seq(
            WhiskAction(namespace1, aname, jsDefault("??")),
            WhiskAction(namespace1, aname, jsDefault("??")))

        entities foreach { put(entityStore, _) }
        waitOnView(entityStore, namespace1, entities.length)
        getKindInNamespaceWithDoc[WhiskAction](namespace1, "actions", {
            case (e: WhiskAction) => true case (_) => false
        }, Some {
            case (o: JsObject) => Try { WhiskAction.serdes.read(o) }
        })
    }

    it should "query whisk for public packages in all namespaces" in {
        implicit val tid = transid()
        implicit val entities = Seq(
            WhiskPackage(namespace1, aname, publish = true),
            WhiskPackage(namespace1, aname, publish = false),
            WhiskPackage(namespace1, aname, Some(Binding(namespace2.root, aname)), publish = true),
            WhiskPackage(namespace1, aname, Some(Binding(namespace2.root, aname))),

            WhiskPackage(namespace2, aname, publish = true),
            WhiskPackage(namespace2, aname, publish = false),
            WhiskPackage(namespace2, aname, Some(Binding(namespace1.root, aname)), publish = true),
            WhiskPackage(namespace2, aname, Some(Binding(namespace1.root, aname))))

        entities foreach { put(entityStore, _) }
        waitOnView(entityStore, namespace1, entities.length / 2)
        waitOnView(entityStore, namespace2, entities.length / 2)
        getPublicPackages(entities)
    }
}
