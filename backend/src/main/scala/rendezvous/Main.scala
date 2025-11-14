package rendezvous.backend

import cats.effect.IO
import cats.effect.IOApp
import cats.effect.std.Queue
import cats.effect.std.Random
import cats.syntax.all.*
import fs2.concurrent.SignallingRef
import rendezvous.dtos

import java.util.UUID
import scala.concurrent.duration.*
import scala.math.max

object Main extends IOApp.Simple:

  case class HttpServerException(msg: String) extends RuntimeException(msg)

  def ws(
      engine: Engine,
      ttdsRef: SignallingRef[IO, Map[UUID, Long]]
  ): fs2.Pipe[IO, dtos.WSProtocol.Client, dtos.WSProtocol.Server] =

    (in: fs2.Stream[IO, dtos.WSProtocol.Client]) =>
      for
        outQ <- fs2.Stream.eval(Queue.unbounded[IO, dtos.WSProtocol.Server])
        rng <- fs2.Stream.eval(Random.scalaUtilRandom[IO])
        outMessage <-
          fs2.Stream.fromQueueUnterminated(outQ)
            .concurrently:
              in.evalMap:
                case dtos.WSProtocol.Client.Ping =>
                  outQ.offer(dtos.WSProtocol.Server.Pong)
                case dtos.WSProtocol.Client.AddNode =>
                  rng.betweenLong(30, 180).flatMap: maxLife =>
                    engine.createNode(maxLife.seconds.some).flatMap: nodeId =>
                      outQ.offer(dtos.WSProtocol.Server.NodeAdded(nodeId)) *>
                        ttdsRef.update(_ + (nodeId -> maxLife))
                case dtos.WSProtocol.Client.AddTask =>
                  IO.randomUUID.flatTap: taskId =>
                    engine.addTask(Task(taskId))
                  .flatMap: taskId =>
                    outQ.offer(dtos.WSProtocol.Server.TaskAdded(taskId))
                  .handleErrorWith:
                    case Engine.NoNodesAvailable =>
                      outQ.offer(dtos.WSProtocol.Server.NoNodesAvailable)
                case dtos.WSProtocol.Client.RemoveNode(nodeId) =>
                  engine.removeNode(nodeId) *>
                    outQ.offer(dtos.WSProtocol.Server.NodeRemoved(nodeId)) *>
                    ttdsRef.update(_ - nodeId)
            .concurrently:
              engine
                .stream
                .evalMap:
                  _.toList
                    .traverse: (uuid, node) =>
                      node.snapshot.map(tasks => uuid -> tasks.map(_.id))
                .changes
                .evalMap: data =>
                  outQ.offer(dtos.WSProtocol.Server.Nodes(data))
            .concurrently:
              engine
                .updates
                .evalMap: (nodeId, task) =>
                  outQ.offer(dtos.WSProtocol.Server.Update(nodeId, task.id))
            .concurrently:
              ttdsRef
                .discrete
                .evalMap: ttds =>
                  outQ.offer(dtos.WSProtocol.Server.Ttds(ttds))
            .concurrently:
              fs2.Stream
                .fixedRateStartImmediately[IO](1.second)
                .evalMap(_ => ttdsRef.update(_.view.mapValues(ttl => max(ttl - 1, 0)).toMap))
      yield outMessage

  override def run: IO[Unit] =
    Engine.resource().use: engine =>
      SignallingRef.of[IO, Map[UUID, Long]](Map.empty).flatMap: ttdsRef =>
        new Server("127.0.0.1", 8090, ws(engine, ttdsRef)).run
