package rendezvous.backend

import cats.effect.IO
import cats.effect.kernel.Fiber
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.std.MapRef
import cats.effect.std.Queue
import cats.effect.std.Supervisor
import cats.syntax.all.*
import fs2.concurrent.SignallingMapRef

import java.util.UUID
import scala.collection.immutable.ListSet
import scala.concurrent.duration.*

trait Node:

  def add(data: Data): IO[Unit]

  def remove(data: Data): IO[Unit]

  def snapshot: IO[List[Data]]

  def updates: fs2.Stream[IO, Data]

object Node:

  def resource(
      nodeId: UUID,
      timeToLive: Option[FiniteDuration] = None,
      heartbeat: PubSub[UUID]
  ): Resource[IO, Node] =
    for
      supervisor <- Supervisor[IO]
      dataRef <- Ref.of[IO, ListSet[Data]](ListSet.empty).toResource
      updatesQ <- Queue.unbounded[IO, Data].toResource
      fibers <- SignallingMapRef
        .ofSingleImmutableMap[IO, UUID, Fiber[IO, Throwable, Unit]]()
        .toResource
      _ <-
        val s = fs2.Stream
          .fixedRateStartImmediately[IO](1.second)
          .evalMap(_ => heartbeat.publish(nodeId))
        timeToLive
          .fold(s)(s.interruptAfter)
          .compile
          .drain
          .background
    yield new Node:

      def snapshot: IO[List[Data]] = dataRef.get.map(_.toList)

      def updates: fs2.Stream[IO, Data] = fs2.Stream.fromQueueUnterminated(updatesQ)

      def remove(data: Data): IO[Unit] =
        dataRef.update(_ - data) *> fibers(data.id).get.flatMap(_.foldMapM(_.cancel))

      def add(data: Data): IO[Unit] =
        supervisor
          .supervise:
            fs2.Stream
              .fixedRateStartImmediately[IO](3.seconds)
              .evalMap(_ => updatesQ.offer(data))
              .compile
              .drain
          .flatMap: fib =>
            fibers.getAndSetKeyValue(data.id, fib) <* dataRef.update(_ + data)
          .flatMap(_.foldMapM(_.cancel))
