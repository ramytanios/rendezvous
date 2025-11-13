package rendezvous.frontend

import cats.syntax.all.*
import mouse.all.*
import rendezvous.dtos

import java.util.UUID

trait View:

  self: ff4s.Dsl[State, Action] =>

  import html.*
  import ff4s.shoelace as sl

  def truncateUUID(uuid: UUID): String = uuid.toString.split("-").head

  val view =
    useState: state =>
      div(
        cls := "bg-zinc-100 font-mono w-screen h-screen flex flex-col gap-4 items-center justify-center overflow-y-auto",
        div(span(cls := "uppercase", "Rendezvous Hashing 🚀")),
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
          cls := "grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-4",
          state.nodes.map: (nodeId, data) =>
            div(
              cls := "relative h-48",
              sl.Card(
                cls := "relative h-full",
                sl.Card.slots.header := div(
                  cls := "text-base flex justify-between items-center gap-x-2",
                  span(cls := "text-shadow-sm", s"${truncateUUID(nodeId)}"),
                  sl.Icon(cls := "text-indigo-600", sl.Icon.name := "hdd-rack"), {
                    val t = state.ttds.get(nodeId)
                    val blinkCls = t.exists(_ < 10).valueOrZero("animate-blink")
                    span(
                      cls := s"absolute $blinkCls text-xs text-shadow-md font-digital text-red-500 bottom-0 right-0",
                      t.map(ttd => s"ca. $ttd").orEmpty
                    )
                  }
                ),
                sl.Card.slots.default := div(
                  cls := "flex flex-col items-start justify-center",
                  data.map(dataId =>
                    val transitionCls = state.updates
                      .contains(Update(dataId, nodeId))
                      .valueOrZero(s"transition-color text-pink-500")
                    span(cls := s"text-sm $transitionCls", s"${truncateUUID(dataId)}")
                  )
                )
              ),
              span(
                cls := "absolute top-0 right-0.5 -mt-1 -mr-1 h-3 w-3 animate-ping rounded-full bg-green-500"
              ),
              div(
                cls := "absolute -top-3 -left-3",
                sl.Tooltip(
                  sl.Tooltip.content := "Stop node",
                  sl.IconButton(
                    sl.IconButton.name := "x-circle-fill",
                    sl.IconButton.label := "RemoveNode",
                    onClick := (_ => Some(Action.SendWS(dtos.WSProtocol.Client.RemoveNode(nodeId))))
                  )
                )
              )
            )
        ),
        div(
          cls := "fixed top-5 right-5 z-50",
          state.notifs.map(_(1)).reverse.map {
            case Notification.Success(label, msg) =>
              sl.Alert(
                sl.Alert.closable := true,
                sl.Alert.open := true,
                sl.Alert.variant := "success",
                sl.Alert.slots.icon := sl.Icon(sl.Icon.name := "check2-circle"),
                div(strong(label), br(), span(msg))
              )
            case Notification.Error(label, msg) =>
              sl.Alert(
                sl.Alert.closable := true,
                sl.Alert.open := true,
                sl.Alert.variant := "danger",
                sl.Alert.slots.icon := sl.Icon(sl.Icon.name := "exclamation-octagon"),
                div(strong(label), br(), span(msg))
              )
            case Notification.Warning(label, msg) =>
              sl.Alert(
                sl.Alert.closable := true,
                sl.Alert.open := true,
                sl.Alert.variant := "warning",
                sl.Alert.slots.icon := sl.Icon(sl.Icon.name := "exclamation-triangle"),
                div(strong(label), br(), span(msg))
              )
          }
        )
      )
