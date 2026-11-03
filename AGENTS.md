# Guide for coding agents

This repository is **GoGame**, a Go (Baduk/Weiqi) server forked from Lichess's
[lila](https://github.com/lichess-org/lila) and [lila-ws](https://github.com/lichess-org/lila-ws).
Read [README.md](README.md) first.

- Go rules, scoring, SGF and the bot live in `modules/goban` (pure Scala, tests in `modules/goban/src/test`).
- The computer opponent goes through `lila.round.AiEngine`: KataGo over GTP (`goban/Gtp.scala`), on by
  default and installed into `katago/` by `bin/setup-katago`. Levels map to human ranks of KataGo's
  human-style network (`round.ai.katago.levels`, `conf/katago.cfg`). The built-in `goban.Bot` is only
  the fallback when KataGo is off, missing or fails.
- `core.Game` wraps `goban.Game`. The chess library (scalachess) is still a dependency, but only for
  shared primitives: `Color`, `ByColor`, `Clock`, `Centis`, `Ply`, `Status`, `Speed` and Glicko ratings.
- Status mapping: a game won by counting is `Status.VariantEnd`, a jigo is `Status.Draw`.
- Ply follows lila: even plies are White's turn, so even games start at ply 1 (Black first)
  and handicap games at ply 0.
- Moves on the wire and in the database are SGF coordinates (`"pd"`) or `"pass"`.
- The game page is `ui/round` (a small snabbdom app). Lobby setup is in `ui/lobby/src/setupCtrl.ts`.
- `lila.ui.deadLink` marks links to pages removed with the chess features.

Build and test:

```bash
./lila.sh test        # Scala tests
./ui/build --tsc      # TypeScript type check
./ui/build            # full front-end build
cd ws && sbt compile  # websocket server
```
