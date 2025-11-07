package rendezvous.frontend

import java.util.UUID

case class Update(dataId: UUID, nodeId: UUID)

final case class State(
    dataUpdates: Set[Update]
)

object State:

  val default: State = State(Set.empty[Update])
