package rendezvous.frontend

import rendezvous.dtos

enum Action:
  case SendWS(msg: dtos.WSProtocol.Client)
  case ModifyState(f: State => State)
