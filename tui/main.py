import asyncio
import dataclasses
import json
from dataclasses import dataclass
from typing import List, Tuple

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


@dataclass
class MsgPing:
    type: str = "Ping"


@dataclass
class MsgAddNode:
    type: str = "AddNode"


@dataclass
class MsgAddTask:
    type: str = "AddTask"


@dataclass
class MsgRemoveNode:
    node: str
    type: str = "RemoveNode"


@dataclass
class MsgPong:
    type: str = "Pong"


@dataclass
class MsgNodes:
    nodes: List[Tuple[str, List[str]]]
    type: str = "Nodes"


@dataclass
class MsgNodeAdded:
    node: str
    type: str = "NodeAdded"


@dataclass
class MsgNodeRemoved:
    node: str
    type: str = "NodeRemoved"


@dataclass
class MsgUpdate:
    node: str
    task: str
    type: str = "Update"


@dataclass
class MsgTtds:
    ttds: dict[str, int]
    type: str = "Ttds"


type MsgClient = MsgPing | MsgAddNode | MsgAddTask | MsgRemoveNode

type MsgServer = (
    MsgPong | MsgNodes | MsgNodeAdded | MsgNodeRemoved | MsgUpdate | MsgTtds
)


def decode_server_msg(msg: str) -> MsgServer | None:
    match json.loads(msg):
        case {"type": "Pong"}:
            return MsgPong()
        case {"type": "Nodes", "nodes": nodes}:
            return MsgNodes(nodes)
        case {"type": "NodeAdded", "node": node}:
            return MsgNodeAdded(node)
        case {"type": "NodeRemoved", "node": node}:
            return MsgNodeRemoved(node)
        case {"type": "Update", "node": node, "task": task}:
            return MsgUpdate(node, task)
        case {"type": "Ttds", "ttds": ttds}:
            return MsgTtds(ttds)
        case _:
            log.warning(f"unexpected server message: {msg}")


class DataclassJsonEncoder(json.JSONEncoder):
    def default(self, o):
        if dataclasses.is_dataclass(o):
            return dataclasses.asdict(o)
        return super().default(o)


def show_uuid(uuid: str) -> str:
    return uuid.split("-")[0]


# queues
q_out = asyncio.Queue[MsgClient]()
q_in = asyncio.Queue[MsgServer]()


async def ws_async() -> None:
    async with websockets.connect("ws://127.0.0.1:8090/api/ws") as ws:

        async def send_heartbeat():
            try:
                while True:
                    await q_out.put(MsgPing())
                    await asyncio.sleep(3)
            except Exception as e:
                e.add_note(f"sending heartbeat failed: {e}")
                raise

        async def send_loop():
            try:
                while True:
                    msg = await q_out.get()
                    await ws.send(json.dumps(msg, cls=DataclassJsonEncoder))
                    log.info(f"sent ws message: {msg}")
            except Exception as e:
                e.add_note(f"send loop failed: {e}")
                raise

        async def recv_loop():
            try:
                async for msg in ws:
                    if isinstance(msg, bytes):
                        msg = msg.decode("utf-8")
                    msg = decode_server_msg(msg)
                    if msg:
                        await q_in.put(msg)
                    else:
                        log.warning("unknown server message")
            except Exception as e:
                e.add_note(f"recv loop failed: {e}")
                raise

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
        await q_out.put(MsgAddNode())

    @on(Button.Pressed, "#add-task")
    @work(exclusive=True)
    async def add_task(self) -> None:
        await q_out.put(MsgAddTask())

    def compose(self):
        yield Button("Add Node", id="add-node", variant="default", flat=True)
        yield Button("Add Task", id="add-task", variant="default", flat=True)


class X(Widget):
    def __init__(self, node: "Node", *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._node = node

    def compose(self) -> ComposeResult:
        yield Label("✖", classes="node-x")

    async def on_click(self, event: Click) -> None:
        await self._node.action_remove_node()


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
        await q_out.put(MsgRemoveNode(self._node))
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
        [], recompose=True, layout=True, bindings=True
    )

    ttds: reactive[dict[str, int]] = reactive({})

    nodes_lock = asyncio.Lock()
    ttds_lock = asyncio.Lock()

    # async is important here, it guarantees watcher is added to the widget's
    # event Q and therefore is only executed  after the `compose`
    async def watch_ttds(self, new_ttds: dict[str, int]) -> None:
        for node_id, _ in self.nodes:
            node = self.query_one(f"#node-{node_id}", Node)
            ttd = new_ttds.get(node._node)
            node.ttd = ttd

    def compose(self) -> ComposeResult:
        for node, tasks in self.nodes:
            yield Node(node, tasks, id=f"node-{node}")

    async def update_nodes(self, nodes: List[Tuple[str, List[str]]]) -> None:
        async with self.nodes_lock:
            self.nodes = nodes

    async def update_ttds(self, ttds: dict[str, int]) -> None:
        async with self.ttds_lock:
            self.ttds = ttds


class RendezVous(App):
    BINDINGS = [
        ("d", "toggle_dark", "Toggle dark mode"),
        ("n", "add_node", "Add node"),
        ("t", "add_task", "Add task"),
        ("q", "quit", "Quit"),
    ]
    CSS_PATH = "styles.tcss"

    is_app_healthy = reactive(False, bindings=True)

    THEME_LIGHT = "catppuccin-latte"
    THEME_DARK = "catppuccin-mocha"

    q_nodes = asyncio.Queue[MsgNodes](1000)
    q_ttds = asyncio.Queue[MsgTtds](1000)

    def check_action(self, action: str, parameters: tuple[object, ...]) -> bool | None:
        if action == "quit":
            return True
        elif not self.is_app_healthy:
            return None
        elif action == "add_task" and not self.query_one(Monitor).nodes:
            return None
        else:
            return True

    async def monitor_q_sizes(self, Q: asyncio.Queue) -> None:
        while True:
            log.debug(f"queue current size {Q.qsize()}")
            await asyncio.sleep(3)

    async def ws_recv_async(self) -> None:
        while True:
            msg = await q_in.get()
            match msg:
                case MsgNodes() as nodes:
                    try:
                        self.q_nodes.put_nowait(nodes)
                    except asyncio.QueueFull as e:
                        s = self.q_nodes.qsize()
                        log.warning(f"Q `q_nodes` is full, size={s}: {e}")
                case MsgTtds() as ttds:
                    try:
                        self.q_ttds.put_nowait(ttds)
                    except asyncio.QueueFull as e:
                        s = self.q_ttds.qsize()
                        log.warning(f"Q `q_ttds` is full, size={s}: {e}")

    async def update_nodes_async(self) -> None:
        while True:
            nodes = await self.q_nodes.get()
            await self.query_one(Monitor).update_nodes(nodes.nodes)

    async def update_ttds_async(self) -> None:
        while True:
            ttds = await self.q_ttds.get()
            await self.query_one(Monitor).update_ttds(ttds.ttds)

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
