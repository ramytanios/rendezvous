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

trait Node:

  def add(data: Data): IO[Unit]

  def data: IO[Seq[Data]]

object Node:

  def resource(): Resource[IO, Node] =
    for
      uuid <- IO.randomUUID.toResource
      supervisor <- Supervisor[IO]
      instruments <- Ref.of[IO, Set[Data]](Set.empty).toResource
      fibers <- SignallingMapRef
        .ofSingleImmutableMap[IO, UUID, Fiber[IO, Throwable, Unit]]()
        .toResource
      _ <- fs2.Stream
        .fixedRateStartImmediately[IO](3.seconds)
        .evalMap: _ =>
          IO.println(s"Node $uuid is up and running")
        .compile
        .drain
        .background
    yield new Node:

      def data: IO[Seq[Data]] = instruments.get.map(_.toSeq)

      def add(data: Data): IO[Unit] =
        IO.println(s"adding data $data") *>
          supervisor
            .supervise:
              fs2.Stream
                .fixedRateStartImmediately[IO](3.seconds)
                .evalMap(_ => IO.println(s"$data"))
                .compile
                .drain
            .flatMap: fib =>
              fibers.getAndSetKeyValue(data.id, fib) <* instruments.update(_ + data)
            .flatMap(_.foldMapM(_.cancel))
