import asyncio
import json
import logging
from abc import ABC
from dataclasses import asdict, dataclass
from typing import List, Literal, Tuple
from uuid import UUID

import websockets

WS_URL = "ws://127.0.0.1:8090/api/ws"


@dataclass
class Out(ABC):
    pass


@dataclass
class Ping(Out):
    pass


@dataclass
class In(ABC):
    pass

    @staticmethod
    def from_js(data: dict[str, any]) -> "In":
        match data.get["type"]:
            case "Pong":
                Pong(**data)
            case "Nodes":
                Nodes(**data)


@dataclass
class Pong(In):
    type: Literal["Pong"] = "Pong"


@dataclass
class Nodes(In):
    nodes: List[Tuple[UUID, List[UUID]]]
    type: Literal["Nodes"] = "Nodes"


async def main_async():
    logger = logging.getLogger(__name__)

    async with websockets.connect(WS_URL) as ws:
        q_out = asyncio.Queue[Out]()
        q_in = asyncio.Queue[In]()

        async def send_heartbeat():
            while True:
                try:
                    await q_out.put(Ping())
                except Exception as e:
                    logging.warning(f"sending heartbeat failed: {e}")
                asyncio.sleep(3)

        async def send_loop():
            while True:
                msg = await q_out.get()
                await ws.send(json.dumps(asdict(msg)))

        async def recv_loop():
            async for msg in ws:
                try:
                    js = json.loads(msg)
                    out = In.from_js(js)
                    await q_in.put(out)
                except Exception as e:
                    logger.warning(f"failed to decode {msg}: {e}")

        async def log_messages():
            while True:
                msg = await q_in.get()
                logger.info(asdict(msg))

        try:
            async with asyncio.TaskGroup() as tg:
                tg.create_task(send_heartbeat())
                tg.create_task(send_loop())
                tg.create_task(recv_loop())
                tg.create_task(log_messages())
        except* Exception as e:
            logger.info(f"WS connection failed in task group: ${e.exceptions}")


if __name__ == "__main__":
    asyncio.run(main_async())
