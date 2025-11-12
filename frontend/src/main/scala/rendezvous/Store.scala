package rendezvous.frontend

import cats.effect.*
import cats.effect.implicits.*
import cats.effect.std.Queue
import cats.effect.std.Random
import cats.effect.std.SecureRandom
import cats.effect.std.Supervisor
import cats.effect.std.UUIDGen
import cats.syntax.all.*
import fs2.dom.Window
import monocle.syntax.all.*
import rendezvous.dtos

import java.util.UUID
import scala.concurrent.duration.*
import scala.math.max

object Store:

  def resource[F[_]: SecureRandom](using F: Async[F]): Resource[F, ff4s.Store[F, State, Action]] =
    for
      sendQ <- Queue.unbounded[F, dtos.WSProtocol.Client].toResource

      supervisor <- Supervisor[F]

      updates <- ExpirySet[F, Update]

      notifsQ <- Queue.unbounded[F, Notification].toResource

      newNodesQ <- Queue.unbounded[F, UUID].toResource

      localStorage <- Resource.pure(Window[F].localStorage)

      store <- ff4s.Store[F, State, Action](State.default): _ =>
        case (Action.AddNode, state) => state -> {
            for
              ttl <- Random[F].betweenLong(60L, 181L)
              nodeId <- UUIDGen[F].randomUUID
              _ <- sendQ.offer(dtos.WSProtocol.Client.AddNode(nodeId, Some(ttl)))
              _ <- localStorage.setItem(nodeId.toString, ttl.toString)
              _ <- newNodesQ.offer(nodeId)
            yield ()
          }
        case (Action.SendWS(msg), state)    => state -> sendQ.offer(msg)
        case (Action.ModifyState(f), state) => f(state) -> F.unit

      _ <- F.unit.toResource

      _ <- fs2.Stream
        .fixedDelay(30.second)
        .evalMap(_ => sendQ.offer(dtos.WSProtocol.Client.Ping))
        .compile
        .drain
        .background

      _ <- store
        .state
        .map(_.nodes.map(_(0)))
        .discrete
        .filter(_.nonEmpty)
        .take(1)
        .evalMap: ids =>
          fs2.Stream.emits(ids).parEvalMapUnbounded(newNodesQ.offer).compile.drain
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
        .fromQueueUnterminated(newNodesQ)
        .evalMap: nodeId =>
          supervisor.supervise:
            F.sleep(
              0.5.seconds
            ) *> // not nice but needed for the `interruptWhen` not to immediately kick in
              fs2.Stream.fixedRateStartImmediately[F](1.second)
                .evalMap: _ =>
                  localStorage.getItem(nodeId.toString).flatMap:
                    _.foldMapM: str =>
                      F.catchNonFatal(str.toLong).flatMap: ttl =>
                        store.dispatch:
                          Action.ModifyState(state =>
                            val upd = state.remainingTime.updatedWith(nodeId) {
                              case None    => Some(ttl)
                              case Some(n) => Some(max(n - 1, 0L))
                            }
                            state.focus(_.remainingTime).replace(upd)
                          )
                      .flatMap: _ =>
                        store.state.get.flatMap:
                          _.remainingTime.get(nodeId).foldMapM: ttl =>
                            localStorage.setItem(nodeId.toString, ttl.toString)
                .interruptWhen(store.state.map(_.nodes.find(_(0) == nodeId)).map(_.isEmpty))
                .compile
                .drain
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
        },
        fs2.Stream.fromQueueUnterminated(sendQ)
      )
        .background
    yield store
