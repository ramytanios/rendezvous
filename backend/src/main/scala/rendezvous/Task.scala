package rendezvous.backend

import cats.effect.IO

case class Task(id: TaskID, work: Work)

enum Work:
  case Dummy

object Exec:

  def run(task: Task): IO[Unit] = task.work match
    case Work.Dummy => IO.pure(())
