package rendezvous.backend

import cats.effect.IO
import mouse.all.*
import munit.CatsEffectSuite

class TestSuite extends CatsEffectSuite:

  test("engine's behavior"):
    Engine.resource().use: engine =>
      for
        nodeId <- engine.createNode(None)
        _ <- assertIOBoolean:
          engine.snapshot.map(_.keySet.size == 1)
        dataId <- IO.randomUUID.flatTap(uuid => engine.addData(Data(uuid)))
        _ <- assertIOBoolean:
          engine.snapshot
            .map(_.get(nodeId))
            .existsF(_.snapshot.map(_.headOption).existsIn(_.id == dataId))
        _ <- engine.removeNode(nodeId)
        _ <- assertIOBoolean:
          engine.snapshot.map(_.keySet.size == 0)

      yield ()
