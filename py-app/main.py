import asyncio
import json
import re
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import List, Literal, Tuple

import websockets
from loguru import logger

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
class Update(In):
    node_id: str
    task_id: str
    type: Literal["Update"] = "Update"


@dataclass
class Ttds(In):
    ttds: dict[str, int]
    type: Literal["Ttds"] = "Ttds"


async def main_async():
    async with websockets.connect(WS_URL) as ws:
        q_out = asyncio.Queue[Out]()
        q_in = asyncio.Queue[In]()

        async def send_heartbeat():
            while True:
                try:
                    await q_out.put(Ping())
                except Exception as e:
                    logger.warning(f"sending heartbeat failed: {e}")
                await asyncio.sleep(3)

        async def send_loop():
            while True:
                msg = await q_out.get()
                await ws.send(json.dumps(msg.to_js()))

        async def recv_loop():
            async for msg in ws:
                try:
                    js = json.loads(msg)
                    in_msg = In.from_js(js)
                    await q_in.put(in_msg)
                except Exception as e:
                    logger.warn(f"failed to decode {msg}: {e}")

        async def log_messages():
            while True:
                msg = await q_in.get()
                logger.warn(msg)

        try:
            async with asyncio.TaskGroup() as tg:
                tg.create_task(send_heartbeat())
                tg.create_task(send_loop())
                tg.create_task(recv_loop())
                tg.create_task(log_messages())
        except* Exception as e:
            logger.error(f"WS connection failed in task group: ${e.exceptions}")


if __name__ == "__main__":
    asyncio.run(main_async())
