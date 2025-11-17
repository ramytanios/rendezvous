package rendezvous.backend

import cats.effect.IO
import cats.syntax.all.*
import mouse.all.*
import munit.CatsEffectSuite
import rendezvous.backend.syntax.*
import rendezvous.backend.types.*

class TestSuite extends CatsEffectSuite:

  test("engine's behavior"):
    Engine.resource().use: engine =>
      def getNode(nodeId: NodeID): IO[Option[Node]] = engine.snapshot.map(_.get(nodeId))
      for
        n1Id <-
          engine.createNode(None).flatTap(_ =>
            assertIOBoolean(engine.snapshot.map(_.keySet.size == 1))
          )
        t1Id <- IO.randomUUID.flatTap(uuid => engine.addTask(Task(uuid.taskId, Work.Forever)))
        _ <- assertIOBoolean(
          getNode(n1Id).existsF(_.snapshot.map(_.headOption).existsIn(_.id == t1Id))
        )
        n2Id <-
          engine.createNode(None).flatTap(_ =>
            assertIOBoolean(engine.snapshot.map(_.keySet.size == 2))
          )
        t2Id <- IO.randomUUID.flatTap(uuid => engine.addTask(Task(uuid.taskId, Work.Forever)))
        _ <-
          val isOnN1 = (Hash.mmh3().hash(s"$t2Id$n1Id") > Hash.mmh3().hash(s"$t2Id$n2Id"))
          (
            IO.whenA(isOnN1)(
              assertIOBoolean(getNode(n1Id).existsF(_.snapshot.map(_.map(_.id).contains(t2Id))))
            ),
            IO.whenA(!isOnN1)(
              assertIOBoolean(getNode(n2Id).existsF(_.snapshot.map(_.map(_.id).contains(t2Id))))
            )
          ).tupled.void
        _ <- engine.removeNode(n1Id)
        _ <- assertIOBoolean(engine.snapshot.map(_.keySet.size == 1))
        _ <- assertIOBoolean(
          getNode(n2Id).existsF(_.snapshot.map {
            case List(t1, t2) if Set(t1.id, t2.id) === Set(t1Id, t2Id) => true
            case _                                                     => false
          })
        )
      yield ()
