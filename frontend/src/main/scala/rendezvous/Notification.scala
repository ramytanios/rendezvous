package rendezvous.frontend

enum Notification:
  case Success(label: String, msg: String)
  case Warning(label: String, msg: String)
  case Error(label: String, msg: String)
