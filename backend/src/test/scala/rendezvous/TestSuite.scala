package rendezvous.backend

import munit.CatsEffectSuite
import cats.effect.IO
import mouse.all.*

class TestSuite extends CatsEffectSuite:

  test("engine's behavior"):
    Engine.resource().use: engine =>
      for
        nodeId <- engine.createNode
        _ <- assertIOBoolean:
          engine.snapshot.map(_.keySet.size == 1)
        dataId <- IO.randomUUID.flatTap(uuid => engine.addData(Data(uuid)))
        _ <- assertIOBoolean:
          engine.snapshot
            .map(nodes => nodes.get(nodeId))
            .existsF(_.snapshot.map(_.headOption).existsIn(_.id == dataId))
      yield ()
