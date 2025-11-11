package rendezvous.frontend

import rendezvous.dtos

enum Action:
  case AddNode
  case SendWS(msg: dtos.WSProtocol.Client)
  case ModifyState(f: State => State)
