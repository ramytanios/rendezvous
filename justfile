# set dotenv-required := true
# set dotenv-load := true

project := "equity-quoting-engine"
port := env('BACKEND_PORT', '8090')

alias run := run-backend
alias serve := serve-frontend

@default:
    just --list

fmt:
    just --fmt --unstable

[unix]
ws:
  websocat ws://localhost:8090/api/ws

[unix]
scalafmt:
    sbt scalafmtAll

[unix]
scalafix:
    sbt 'scalafixEnable; scalafixAll'

[unix]
fix:
    just scalafmt scalafix

[confirm]
[unix]
clean:
    git clean -Xdf

[unix]
deps:
    sbt dependencyUpdates

[unix]
stage:
    sbt stage

[unix]
[working-directory('backend')]
run-staged:
    echo {{ MAGENTA }}$PWD{{ NORMAL }}
    sh ./target/universal/stage/bin/{{ project }}-backend
    # direnv exec . ./target/universal/stage/bin/{{ project }}-backend

[unix]
run-backend:
    sbt 'backend/run'

[unix]
compile-frontend:
    sbt 'frontend/fastLinkJS'

[unix]
compile-backend:
    sbt 'backend/compile'

[unix]
compile:
    just compile-frontend compile-backend

[unix]
watch-frontend:
    sbt '~frontend/fastLinkJS'

[unix]
watch-backend:
    sbt '~/backend/run'

[unix]
[working-directory('frontend')]
serve-frontend:
    echo {{ MAGENTA }}$PWD{{ NORMAL }}
    live-server --entry-file=index.html --proxy=/api:http://localhost:{{ port }}/api
