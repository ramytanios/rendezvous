package rendezvous.frontend

import java.util.UUID
import scala.collection.immutable.SortedMap

case class Update(dataId: UUID, nodeId: UUID)

final case class State(
    updates: Set[Update],
    nodes: Map[UUID, List[UUID]]
)

object State:

  val default: State = State(Set.empty[Update], SortedMap.empty)
