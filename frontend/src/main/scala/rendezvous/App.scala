package rendezvous.frontend

import cats.effect.Async
import cats.effect.std.SecureRandom

class App[F[_]: Async: SecureRandom] extends ff4s.App[F, State, Action] with View:
  override def store = Store.resource[F]
