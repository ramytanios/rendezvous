package rendezvous.frontend

import java.util.UUID

case class Update(dataId: UUID, nodeId: UUID)

final case class State(
    updates: Set[Update],
    nodes: List[(UUID, List[UUID])]
)

object State:

  val default: State = State(Set.empty[Update], List.empty)
