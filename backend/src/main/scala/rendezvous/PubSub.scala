package rendezvous.backend

import cats.effect.IO
import fs2.concurrent.Topic

trait PubSub[A]:

  def publish(a: A): IO[Unit]

  def subscribe: fs2.Stream[IO, A]

object PubSub:

  def apply[A]: IO[PubSub[A]] =
    Topic.apply[IO, A].map: topic =>
      new PubSub[A]:
        def publish(a: A): IO[Unit] = topic.publish1(a).void
        def subscribe: fs2.Stream[IO, A] = topic.subscribe(1000)
