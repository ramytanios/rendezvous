package rendezvous.frontend

import cats.effect.*
import cats.effect.implicits.*
import cats.effect.std.Queue
import cats.effect.std.SecureRandom
import cats.effect.std.Supervisor
import cats.effect.std.UUIDGen
import cats.syntax.all.*
import fs2.dom.Window
import monocle.syntax.all.*
import rendezvous.dtos

import scala.concurrent.duration.*

object Store:

  def resource[F[_]: SecureRandom](using F: Async[F]): Resource[F, ff4s.Store[F, State, Action]] =
    for
      sendQ <- Queue.unbounded[F, dtos.WSProtocol.Client].toResource

      supervisor <- Supervisor[F]

      updates <- ExpirySet[F, Update]

      notifsQ <- Queue.unbounded[F, Notification].toResource

      localStorage <- Resource.pure(Window[F].localStorage)

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

      _ <- fs2.Stream
        .fromQueueUnterminated(notifsQ)
        .parEvalMapUnbounded: notif =>
          UUIDGen.fromSecureRandom[F]
            .randomUUID.flatMap: uuid =>
              store.dispatch:
                Action.ModifyState(_.focus(_.notifs).modify(_ :+ (uuid -> notif)))
              .flatMap: _ =>
                F.sleep(3.seconds)
              .flatMap: _ =>
                store.dispatch:
                  Action.ModifyState(_.focus(_.notifs).modify(_.filterNot(_(0) == uuid)))
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
          case dtos.WSProtocol.Server.NoNodesAvailable =>
            notifsQ.offer(Notification.Error("Failed to add data", "No nodes available"))
          case dtos.WSProtocol.Server.NodeAdded(nodeId) =>
            notifsQ.offer(Notification.Success("Node added", nodeId.toString))
          case dtos.WSProtocol.Server.DataAdded(dataId) =>
            notifsQ.offer(Notification.Success("Data added", dataId.toString))
          case dtos.WSProtocol.Server.NodeRemoved(nodeId) =>
            notifsQ.offer(Notification.Warning("Node removed", nodeId.toString))
          case dtos.WSProtocol.Server.Ttls(ttls) =>
            store.dispatch(Action.ModifyState(_.focus(_.ttls).replace(ttls)))
        },
        fs2.Stream.fromQueueUnterminated(sendQ)
      )
        .background
    yield store
