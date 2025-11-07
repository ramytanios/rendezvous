package rendezvous.frontend

import cats.effect.*
import cats.effect.implicits.*
import cats.effect.std.Queue
import cats.effect.std.Supervisor
import rendezvous.dtos
import monocle.syntax.all.*
import scala.concurrent.duration.*

object Store:

  def resource[F[_]](using F: Async[F]): Resource[F, ff4s.Store[F, State, Action]] =
    for
      sendQ <- Queue.unbounded[F, dtos.WSProtocol.Client].toResource

      supervisor <- Supervisor[F]

      updates <- ExpirySet[F, Update]

      store <- ff4s.Store[F, State, Action](State.default): _ =>
        case (Action.SendWS(msg), state)    => state -> sendQ.offer(msg)
        case (Action.ModifyState(f), state) => f(state) -> F.unit

      _ <- F.unit.toResource

      _ <- fs2.Stream
        .fixedDelay(30.second)
        .evalMap(_ => sendQ.offer(dtos.WSProtocol.Client.Ping))
        .compile
        .drain
        .background

      _ <- updates
        .updates
        .evalMap: data =>
          store.dispatch:
            Action.ModifyState(_.focus(_.updates).replace(data))
        .compile
        .drain
        .background

      _ <- ff4s.WebSocketClient[F].bidirectionalJson[
        dtos.WSProtocol.Server,
        dtos.WSProtocol.Client
      ](
        s"ws://127.0.0.1:8090/api/ws",
        _.evalMap {
          case dtos.WSProtocol.Server.Pong => F.delay(println("pong received"))
          case dtos.WSProtocol.Server.Update(nodeId, dataId) =>
            updates.set(Update(dataId, nodeId), 1.second)
          case dtos.WSProtocol.Server.Nodes(nodes) =>
            store.dispatch(Action.ModifyState(_.focus(_.nodes).replace(nodes)))
          case dtos.WSProtocol.Server.NoNodesAvailable => F.delay(println("no nodes available"))
        },
        fs2.Stream.fromQueueUnterminated(sendQ)
      )
        .background
    yield store
