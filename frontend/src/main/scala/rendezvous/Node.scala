package rendezvous.frontend

import java.util.UUID
import mouse.all.*

trait Node:
  self: ff4s.Dsl[State, Action] =>

  import html.*
  import ff4s.shoelace as sl

  def nodesView =
    useState: state =>
      div(
        state.nodes.toList.map: (nodeId, data) =>
          sl.Card(
            sl.Card.slots.header := span(s"$nodeId"),
            sl.Card.slots.default := div(
              cls := "flex flex-col items-start justify-center",
              data.map(dataId =>
                val blinkCls = state.updates
                  .contains(Update(dataId, nodeId))
                  .valueOrZero(s"transition-colors indigo-500")
                span(cls := s"$blinkCls", s"$dataId")
              )
            )
          )
      )
