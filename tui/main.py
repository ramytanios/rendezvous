import asyncio
import json
import re
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import List, Literal, Tuple

import websockets
from textual import log, work
from textual.app import App, ComposeResult
from textual.containers import HorizontalGroup
from textual.reactive import reactive
from textual.widgets import Button, Footer, Header, Label, ListItem, ListView

WS_URL = "ws://127.0.0.1:8090/api/ws"


def pascal_to_snake(name: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()


def convert_keys(d: dict) -> dict:
    return {pascal_to_snake(k): v for k, v in d.items()}


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
    node_id: str

    def to_js(self) -> dict[str, any]:
        return {"RemoveNode": {"nodeId": self.node_id}}


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

        async def log_messages():
            while True:
                msg = await q_in.get()
                log.info(f"received ws message: {msg}")

        try:
            async with asyncio.TaskGroup() as tg:
                tg.create_task(send_heartbeat())
                tg.create_task(send_loop())
                tg.create_task(recv_loop())
        except* Exception as e:
            log.error(f"WS connection failed in task group: ${e.exceptions}")


class Control(HorizontalGroup):
    async def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "add_node":
            self.add_node()
        elif event.button.id == "add_task":
            self.add_task()

    @work(exclusive=True)
    async def add_node(self) -> None:
        await q_out.put(AddNode())

    @work(exclusive=True)
    async def add_task(self) -> None:
        await q_out.put(AddTask())

    def compose(self) -> None:
        yield Button("Add Node", id="add_node", variant="primary", flat=True)
        yield Button("Add Task", id="add_task", variant="primary", flat=True)


class Monitor(HorizontalGroup):
    content: reactive[List[Tuple[str, List[str]]] | None] = reactive(
        None, recompose=True
    )

    def compose(self) -> ComposeResult:
        match self.content:
            case None:
                pass
            case d:
                items = [ListItem(Label(node_id)) for node_id, _ in d]
                yield ListView(*items)


class RendezVous(App):
    BINDINGS = [
        ("d", "toggle_dark", "Toggle dark mode"),
        ("n", "add_node", "Add node"),
        ("t", "add_task", "Add task"),
        ("q", "quit", "Quit"),
    ]
    CSS_PATH = "styles.tcss"

    async def update_nodes_async(self) -> None:
        lock = asyncio.Lock()
        while True:
            msg = await q_in.get()
            match msg:
                case Nodes(nodes, _):
                    async with lock:
                        self.query_one(Monitor).content = nodes

    async def on_mount(self) -> None:
        self.run_worker(ws_async())
        self.run_worker(self.update_nodes_async())

    def compose(self) -> ComposeResult:
        yield Header()
        yield Control()
        yield Monitor()
        yield Footer()

    def action_add_node(self) -> None:
        button = self.query_one("#add_node", Button)
        button.press()

    def action_add_task(self) -> None:
        button = self.query_one("#add_task", Button)
        button.press()

    def action_toggle_dark(self) -> None:
        self.theme = (
            "textual-dark" if self.theme == "textual-light" else "textual-light"
        )


if __name__ == "__main__":
    app = RendezVous()
    app.run()
