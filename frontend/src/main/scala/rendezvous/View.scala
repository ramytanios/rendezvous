package rendezvous.frontend

import mouse.all.*
import rendezvous.dtos

import java.util.UUID

trait View:

  self: ff4s.Dsl[State, Action] =>

  import html.*
  import ff4s.shoelace as sl

  def truncateUUID(uuid: UUID): String =
    uuid.toString.split("-").head

  val view =
    useState: state =>
      div(
        cls := "bg-zinc-200 font-mono w-screen h-screen flex flex-col gap-2 items-center justify-center overflow-y-auto",
        div(
          cls := "flex gap-2",
          sl.Button(
            sl.Button.variant := "neutral",
            sl.Button.size := "medium",
            sl.Button.outline := true,
            sl.Button.slots.prefix := sl.Icon(sl.Icon.name := "hdd-rack"),
            onClick := (_ => Some(Action.SendWS(dtos.WSProtocol.Client.AddNode))),
            p(cls := "uppercase", "Add Node")
          ),
          sl.Button(
            sl.Button.variant := "neutral",
            sl.Button.size := "medium",
            sl.Button.outline := true,
            sl.Button.slots.prefix := sl.Icon(sl.Icon.name := "clipboard-data"),
            onClick := (_ => Some(Action.SendWS(dtos.WSProtocol.Client.AddData))),
            p(cls := "uppercase", "Add Data")
          )
        ),
        div(
          cls := "grid grid-cols-3 gap-4",
          state.nodes.map: (nodeId, data) =>
            div(
              cls := "relative",
              sl.Card(
                sl.Card.slots.header := div(
                  cls := "flex justify-between items-center gap-x-2",
                  s"${truncateUUID(nodeId)}",
                  sl.Icon(sl.Icon.name := "hdd-rack")
                ),
                sl.Card.slots.default := div(
                  cls := "flex flex-col items-start justify-center",
                  data.map(dataId =>
                    val blinkCls = state.updates
                      .contains(Update(dataId, nodeId))
                      .valueOrZero(s"transition-color text-pink-500")
                    span(cls := s"$blinkCls", s"${truncateUUID(dataId)}")
                  )
                )
              ),
              span(
                cls := "absolute top-0 right-0.5 -mt-1 -mr-1 h-3 w-3 animate-ping rounded-full bg-green-500"
              ),
              div(
                cls := "absolute -top-3 -left-3",
                sl.IconButton(
                  sl.IconButton.name := "x-circle-fill",
                  sl.IconButton.label := "RemoveNode",
                  onClick := (_ => Some(Action.SendWS(dtos.WSProtocol.Client.RemoveNode(nodeId))))
                )
              )
            )
        )
      )
