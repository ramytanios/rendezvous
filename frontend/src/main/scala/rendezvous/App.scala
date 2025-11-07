package rendezvous.frontend

import cats.effect.Async

class App[F[_]: Async] extends ff4s.App[F, State, Action] with View:
  override def store = Store.resource[F]
