package rendezvous.backend

import cats.effect.IO

case class Task(id: TaskID, work: Work)

enum Work:
  case Forever

object Exec:

  def run(task: Task): IO[Unit] = task.work match
    case Work.Forever => IO.never
