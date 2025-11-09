package rendezvous.backend

import munit.CatsEffectSuite
import cats.effect.IO

class TestSuite extends CatsEffectSuite:

  test("engine's behavior"):
    Engine.resource().use: engine =>
      for
        nodeId <- engine.createNode
        _ <- assertIO(engine.snapshot.map(_.keySet.size), 1)
      yield ()
