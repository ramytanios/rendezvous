package backend

import cats.effect.IO
import cats.effect.kernel.Fiber
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.std.MapRef
import cats.effect.std.Supervisor
import cats.syntax.all.*
import fs2.concurrent.SignallingMapRef

import java.util.UUID
import scala.concurrent.duration.*
import cats.effect.std.Queue

trait Node:

  def add(data: Data): IO[Unit]

  def data: IO[Seq[Data]]

  def updates: fs2.Stream[IO, Data]

object Node:

  def resource(): Resource[IO, Node] =
    for
      uuid <- IO.randomUUID.toResource
      supervisor <- Supervisor[IO]
      dataRef <- Ref.of[IO, Set[Data]](Set.empty).toResource
      updatesQ <- Queue.unbounded[IO, Data].toResource
      fibers <- SignallingMapRef
        .ofSingleImmutableMap[IO, UUID, Fiber[IO, Throwable, Unit]]()
        .toResource
      _ <- fs2.Stream
        .fixedRateStartImmediately[IO](5.seconds)
        .evalMap: _ =>
          IO.println(s"Node $uuid is up and running")
        .compile
        .drain
        .background
    yield new Node:

      def data: IO[Seq[Data]] = dataRef.get.map(_.toSeq)

      def updates: fs2.Stream[IO, Data] = fs2.Stream.fromQueueUnterminated(updatesQ)

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
