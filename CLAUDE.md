# Pythacoin – synthetic CDP-based stablecoin using Pyth ADA/USD price oracle

## Project Overview

Pythacoin – synthetic CDP-based stablecoin using Pyth oracles network for ADA/USD price oracle.
It's a project for Pythaton – Pyth hackathon.

## Context

https://pyth.network
https://docs.pyth.network/price-feeds/pro/integrate-as-consumer/cardano
https://github.com/pyth-network/pyth-examples

## Commands

Use `sbtn` for all build commands.

| Command                 | Purpose                                 |
|-------------------------|-----------------------------------------|
| `sbtn test`             | Run unit tests (emulator-based)         |
| `sbtn integration/test` | Run integration tests (requires Docker) |
| `sbtn run`              | Run the main application / HTTP server  |
| `sbtn compile`          | Compile the core module                 |
| `sbtn Test/compile`     | Compile core module with tests          |

**Note:** Integration tests use Yaci DevKit via Testcontainers and require Docker to be running.

## Scala 3 Code Style

Use `{}` for top-level definitions and multi-line function bodies.
Use indentation-based syntax for `if`/`match`/`try`/`for`, unless it's too big (more than 20 lines).
Use braces otherwise.
Use `then` in `if` expressions and `do` in `while` loops.

```scala
object Example {
  def exampleFunction(x: Int): Int = {
    if x > 0 then x * 2
    else
      val y = -x
      y * 2
  }

  def describe(x: Any): String = x match
    case 1 => "one"
    case _ => "other"
}
```

## Commit Guidelines

- Use conventional commit style: `feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`
- Keep messages short: 1-2 paragraphs
- Mention key changes
- Never add "Co-authored by Claude Code" or similar

## Scalus Sources Reference

The Scalus library sources are at `/Users/nau/projects/lantr/scalus`. Consult them for API details,
implementation patterns, and usage examples.

| Area                | Path                                                                    |
|---------------------|-------------------------------------------------------------------------|
| Core API            | `scalus-core/shared/src/main/scala/scalus/`                             |
| Standard library    | `scalus-core/shared/src/main/scala/scalus/prelude/`                     |
| SIR compiler        | `scalus-core/shared/src/main/scala/scalus/compiler/sir/`                |
| UPLC                | `scalus-core/shared/src/main/scala/scalus/uplc/`                        |
| Transaction builder | `scalus-cardano-ledger/shared/src/main/scala/scalus/cardano/txbuilder/` |
| Ledger types        | `scalus-cardano-ledger/shared/src/main/scala/scalus/cardano/`           |
| Testkit             | `scalus-testkit/`                                                       |
| Examples            | `scalus-examples/shared/src/main/scala/scalus/examples/`                |
| Compiler plugin     | `scalus-plugin/src/main/scala/scalus/plugin/`                           |

## Important Files

- `build.sbt` - Build configuration, dependencies, Scalus plugin setup
- `.scalafmt.conf` - Code formatting (4-space indent, 100 col max)
- `src/main/scala/pythacoin/onchain/CdpValidator.scala` - CDP validator with Pyth oracle integration
- `src/main/scala/pythacoin/onchain/StrictLookups.scala` - On-chain utility extensions
- `src/main/scala/pythacoin/CdpContract.scala` - Off-chain contract compilation
- `src/main/scala/pythacoin/Server.scala` - REST API server and AppCtx
- `src/main/scala/pythacoin/Main.scala` - Entry point
- `src/test/scala/pythacoin/CdpValidatorTest.scala` - Validator unit tests
