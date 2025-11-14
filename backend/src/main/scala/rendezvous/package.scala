package rendezvous

import java.util.UUID

package object backend:

  opaque type TaskID = UUID

  object TaskID:
    def apply(uuid: UUID): TaskID = uuid

    extension (taskId: TaskID)
      def value: UUID = taskId

  opaque type NodeID = UUID

  object NodeID:
    def apply(uuid: UUID): NodeID = uuid

    extension (nodeId: NodeID)
      def value: UUID = nodeId
