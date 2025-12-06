project := "rendezvous"
port := env('BACKEND_PORT', '8090')

alias run := run-backend
alias serve := serve-frontend

@default:
    just --list

fmt:
    just --fmt --unstable

ws:
  websocat ws://localhost:8090/api/ws

scalafmt:
    sbt scalafmtAll

scalafix:
    sbt 'scalafixEnable; scalafixAll'

fix:
    just scalafmt scalafix

[confirm]
clean:
    git clean -Xdf

deps:
    sbt dependencyUpdates

stage:
    sbt stage

[working-directory('backend')]
run-staged:
    sh ./target/universal/stage/bin/{{ project }}-backend

run-backend:
    sbt 'backend/run'

compile-frontend:
    sbt 'frontend/fastLinkJS'

compile-backend:
    sbt 'backend/compile'

compile:
    just compile-frontend compile-backend

watch-frontend:
    sbt '~frontend/fastLinkJS'

watch-backend:
    sbt '~/backend/run'

[working-directory('frontend')]
serve-frontend:
    live-server --entry-file=index.html --proxy=/api:http://localhost:{{ port }}/api

[working-directory('frontend')]
watch-css:
    npx @tailwindcss/cli -i ./input.css -o ./output.css --watch

[working-directory('tui')]
tui:
    uv run main.py

[working-directory('tui')]
dev-tui:
    uv run textual run --dev main.py

[working-directory('tui')]
console-tui:
    uv run textual console
