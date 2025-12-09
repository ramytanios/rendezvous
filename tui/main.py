import asyncio
import json
import re
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import List, Literal, Tuple

import websockets
from textual import log, work
from textual._on import on
from textual.app import App, ComposeResult
from textual.containers import (
    Horizontal,
    HorizontalGroup,
    ScrollableContainer,
    VerticalGroup,
)
from textual.events import Click, Message
from textual.reactive import reactive
from textual.widget import Widget
from textual.widgets import Button, Footer, Header, Label, Static
from textual.worker import Worker, WorkerState

WS_URL = "ws://127.0.0.1:8090/api/ws"


def pascal_to_snake(name: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()


def convert_keys(d: dict) -> dict:
    return {pascal_to_snake(k): v for k, v in d.items()}


def show_uuid(uuid: str) -> str:
    return uuid.split("-")[0]


@dataclass
class Out(ABC):
    @abstractmethod
    def to_js(self) -> dict[str, any]:
        pass


@dataclass
class Ping(Out):
    def to_js(self) -> dict[str, any]:
        return {"Ping": {}}


@dataclass
class AddNode(Out):
    def to_js(self) -> dict[str, any]:
        return {"AddNode": {}}


@dataclass
class AddTask(Out):
    def to_js(self) -> dict[str, any]:
        return {"AddTask": {}}


@dataclass
class RemoveNode(Out):
    id: str

    def to_js(self) -> dict[str, any]:
        return {"RemoveNode": {"id": self.id}}


@dataclass
class In(ABC):
    pass

    @staticmethod
    def from_js(data: dict[str, any]) -> "In":
        match data.get("type"):
            case None:
                raise Exception(f"missing `type` discriminator in {data}")
            case t:
                match globals().get(t):
                    case None:
                        raise Exception(f"failed to decode data {data}")
                    case kls:
                        return kls(**convert_keys(data))


@dataclass
class Pong(In):
    type: Literal["Pong"] = "Pong"


@dataclass
class Nodes(In):
    nodes: List[Tuple[str, List[str]]]
    type: Literal["Nodes"] = "Nodes"


@dataclass
class NodeAdded(In):
    node_id: str
    type: Literal["NodeAdded"] = "NodeAdded"


@dataclass
class NodeRemoved(In):
    node_id: str
    type: Literal["NodeRemoved"] = "NodeRemoved"


@dataclass
class Update(In):
    node_id: str
    task_id: str
    type: Literal["Update"] = "Update"


@dataclass
class Ttds(In):
    ttds: dict[str, int]
    type: Literal["Ttds"] = "Ttds"


# queues
q_out = asyncio.Queue[Out]()
q_in = asyncio.Queue[In]()


async def ws_async() -> None:
    async with websockets.connect(WS_URL) as ws:

        async def send_heartbeat():
            while True:
                try:
                    await q_out.put(Ping())
                except Exception as e:
                    log.warning(f"sending heartbeat failed: {e}")
                await asyncio.sleep(3)

        async def send_loop():
            while True:
                msg = await q_out.get()
                log.info(f"sent ws message: {msg}")
                await ws.send(json.dumps(msg.to_js()))

        async def recv_loop():
            async for msg in ws:
                try:
                    js = json.loads(msg)
                    in_msg = In.from_js(js)
                    await q_in.put(in_msg)
                except Exception as e:
                    log.warning(f"failed to decode {msg}: {e}")

        try:
            async with asyncio.TaskGroup() as tg:
                tg.create_task(send_heartbeat())
                tg.create_task(send_loop())
                tg.create_task(recv_loop())
        except* Exception as e:
            log.error(f"WS connection failed in task group: ${e.exceptions}")


class Control(HorizontalGroup):
    @on(Button.Pressed, "#add-node")
    @work(exclusive=True)
    async def add_node(self) -> None:
        await q_out.put(AddNode())

    @on(Button.Pressed, "#add-task")
    @work(exclusive=True)
    async def add_task(self) -> None:
        await q_out.put(AddTask())

    def compose(self) -> None:
        yield Button("Add Node", id="add-node", variant="default", flat=True)
        yield Button("Add Task", id="add-task", variant="default", flat=True)


class X(Widget):
    def __init__(self, node: "Node", *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._node = node

    def compose(self) -> ComposeResult:
        yield Label("✖", classes="node-x")

    async def on_click(self, event: Click) -> None:
        self._node.remove_node()


class Prompt(VerticalGroup):
    def __init__(self, node_id: str, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._node = node_id

    class Closed(Message):
        pass

    BINDINGS = [("Y", "yes", "YES"), ("N", "no", "NO")]

    async def action_yes(self) -> None:
        self.prompt_yes()

    async def action_no(self) -> None:
        self.prompt_no()

    @on(Button.Pressed, "#prompt-yes")
    @work(exclusive=True)
    async def prompt_yes(self) -> None:
        await q_out.put(RemoveNode(self._node))
        self.post_message(self.Closed())

    @on(Button.Pressed, "#prompt-no")
    def prompt_no(self) -> None:
        self.post_message(self.Closed())

    def compose(self) -> ComposeResult:
        yield Label(f"Are you sure to remove node {show_uuid(self._node)} ?")
        with HorizontalGroup():
            yield Button("YES", id="prompt-yes", variant="warning", flat=True)
            yield Button("NO", id="prompt-no", variant="primary", flat=True)


class Node(VerticalGroup):
    def __init__(self, node: str, tasks: List[str]):
        super().__init__()
        self._node = node
        self._tasks = tasks

    BINDINGS = [("backspace", "remove_node", "Delete")]

    class Xed(Message):
        def __init__(self, node_id: str):
            self.node_id = node_id
            super().__init__()

    async def action_remove_node(self) -> None:
        self.post_message(self.Xed(self._node))

    def compose(self) -> ComposeResult:
        with Horizontal(id="node-footer-outer"):
            yield Horizontal(
                Static(show_uuid(self._node), classes="node-header"),
                id="node-footer-inner",
            )
            yield X(self)
        tasks = [Static(show_uuid(task)) for task in self._tasks]
        yield ScrollableContainer(*tasks, can_focus=True, classes="node-body")


class Monitor(HorizontalGroup):
    content: reactive[List[Tuple[str, List[str]]] | None] = reactive(
        None, recompose=True, layout=True
    )

    def compose(self) -> ComposeResult:
        match self.content:
            case None:
                pass
            case d:
                for node, tasks in d:
                    yield Node(node, tasks)


class RendezVous(App):
    BINDINGS = [
        ("d", "toggle_dark", "Toggle dark mode"),
        ("n", "add_node", "Add node"),
        ("t", "add_task", "Add task"),
        ("q", "quit", "Quit"),
    ]
    CSS_PATH = "styles.tcss"

    is_app_healthy = reactive(False)

    THEME_LIGHT = "catppuccin-latte"
    THEME_DARK = "catppuccin-mocha"

    async def update_nodes_async(self) -> None:
        lock = asyncio.Lock()
        while True:
            msg = await q_in.get()
            match msg:
                case Nodes(nodes, _):
                    async with lock:
                        self.query_one(Monitor).content = nodes

    async def on_mount(self) -> None:
        self.theme = self.THEME_DARK
        self.run_worker(ws_async(), exit_on_error=False)
        self.run_worker(self.update_nodes_async())

    def on_worker_state_changed(self, event: Worker.StateChanged) -> None:
        match event.worker.state:
            case WorkerState.RUNNING:
                self.is_app_healthy = True
            case _:
                self.is_app_healthy = False

    def watch_is_app_healthy(self, is_healthy: bool) -> None:
        label = self.query_one("#right-label", Label)
        label.content = "OK" if is_healthy else "NOT OK"
        label.classes = "health ok" if is_healthy else "health not"

    def compose(self) -> ComposeResult:
        yield Header(show_clock=True)
        yield Control()
        yield Monitor()
        with Horizontal(id="footer-outer"):
            yield Horizontal(Footer(), id="footer-inner")
            yield Label("NOT OK", id="right-label", classes="health not")

    def action_add_node(self) -> None:
        button = self.query_one("#add-node", Button)
        button.press()

    def action_add_task(self) -> None:
        button = self.query_one("#add-task", Button)
        button.press()

    def action_toggle_dark(self) -> None:
        self.theme = (
            self.THEME_LIGHT if self.theme == self.THEME_DARK else self.THEME_DARK
        )

    def on_node_xed(self, message: Node.Xed) -> None:
        self.mount(Prompt(message.node_id))
        button = self.query_one("#prompt-yes", Button)
        self.set_focus(button)

    def on_prompt_closed(self, message: Prompt.Closed) -> None:
        self.query_one(Prompt).remove()


if __name__ == "__main__":
    app = RendezVous()
    app.run()
