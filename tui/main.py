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
    Container,
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
    async with websockets.connect("ws://127.0.0.1:8090/api/ws") as ws:

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
        self._node.action_remove_node()


class Dialogue(Container):
    def __init__(self, node_id: str, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._node = node_id

    class Closed(Message):
        pass

    BINDINGS = [("Y", "yes", "YES"), ("N", "no", "NO")]

    async def action_yes(self) -> None:
        self.dialogue_yes()

    async def action_no(self) -> None:
        self.dialogue_no()

    @on(Button.Pressed, "#dialogue-yes")
    @work(exclusive=True)
    async def dialogue_yes(self) -> None:
        await q_out.put(RemoveNode(self._node))
        self.post_message(self.Closed())

    @on(Button.Pressed, "#dialogue-no")
    def dialogue_no(self) -> None:
        self.post_message(self.Closed())

    def compose(self) -> ComposeResult:
        yield Static(
            f"Are you sure to remove node {show_uuid(self._node)} ?",
            classes="dialogue-question",
        )
        with Horizontal(classes="dialogue-buttons"):
            yield Button("YES", id="dialogue-yes", variant="success", flat=True)
            yield Button("NO", id="dialogue-no", variant="error", flat=True)


class Node(VerticalGroup):
    ttd: reactive[int | None] = reactive(None)

    def __init__(self, node: str, tasks: List[str], *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._node = node
        self._tasks = tasks

    BINDINGS = [("backspace", "remove_node", "Delete")]

    class Xed(Message):
        def __init__(self, node_id: str):
            self.node_id = node_id
            super().__init__()

    async def action_remove_node(self) -> None:
        self.post_message(self.Xed(self._node))

    def watch_ttd(self, new_ttd: int) -> None:
        if self.is_mounted:
            self.query_one("#static", Static).update(
                f"{show_uuid(self._node)} - {new_ttd}"
            )

    def compose(self) -> ComposeResult:
        with Horizontal(id="node-footer-outer"):
            with Horizontal(id="node-footer-inner"):
                label = f"{show_uuid(self._node)}"
                match self.ttd:
                    case int() as ttd:
                        label = f"{show_uuid(self._node)} {ttd}"
                yield Static(label, id="static", classes="node-header")
            yield X(self)
        tasks = [Static(show_uuid(task)) for task in self._tasks]
        yield ScrollableContainer(*tasks, can_focus=True, classes="node-body")


class Monitor(HorizontalGroup):
    nodes: reactive[List[Tuple[str, List[str]]]] = reactive(
        [], recompose=True, layout=True
    )

    ttds: reactive[dict[str, int]] = reactive({})

    # async is important here, it guarantees watcher is added to the widget's
    # event Q and thereforeis only executed  after the `compose`
    async def watch_ttds(self, new_ttds: dict[str, int]) -> None:
        for node_id, _ in self.nodes:
            node = self.query_one(f"#node-{node_id}", Node)
            ttd = new_ttds.get(node._node)
            node.ttd = ttd

    def compose(self) -> ComposeResult:
        for node, tasks in self.nodes:
            yield Node(node, tasks, id=f"node-{node}")


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

    q_nodes = asyncio.Queue[Nodes](1000)
    q_ttds = asyncio.Queue[Ttds](1000)

    async def monitor_q_sizes(self, Q: asyncio.Queue) -> None:
        while True:
            log.debug(f"queue current size {Q.qsize()}")
            await asyncio.sleep(3)

    async def ws_recv_async(self) -> None:
        while True:
            msg = await q_in.get()
            match msg:
                case Nodes() as nodes:
                    try:
                        self.q_nodes.put_nowait(nodes)
                    except asyncio.QueueFull as e:
                        s = self.q_nodes.qsize()
                        log.warning(f"Q `q_nodes` is full, size={s}: {e}")
                case Ttds() as ttds:
                    try:
                        self.q_ttds.put_nowait(ttds)
                    except asyncio.QueueFull as e:
                        s = self.q_ttds.qsize()
                        log.warning(f"Q `q_ttds` is full, size={s}: {e}")

    async def update_nodes_async(self) -> None:
        lock = asyncio.Lock()
        while True:
            nodes = await self.q_nodes.get()
            async with lock:
                self.query_one(Monitor).nodes = nodes.nodes

    async def update_ttds_async(self) -> None:
        lock = asyncio.Lock()
        while True:
            ttds = await self.q_ttds.get()
            async with lock:
                self.query_one(Monitor).ttds = ttds.ttds

    async def on_mount(self) -> None:
        self.theme = self.THEME_DARK
        self.run_worker(ws_async(), exit_on_error=False)
        self.run_worker(self.ws_recv_async(), exit_on_error=False)
        self.run_worker(self.update_nodes_async(), exit_on_error=False)
        self.run_worker(self.update_ttds_async(), exit_on_error=False)

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
        self.mount(Dialogue(message.node_id, id="dialogue"))
        button = self.query_one("#dialogue-yes", Button)
        self.set_focus(button)

    def on_dialogue_closed(self, message: Dialogue.Closed) -> None:
        self.query_one(Dialogue).remove()


if __name__ == "__main__":
    app = RendezVous()
    app.run()
