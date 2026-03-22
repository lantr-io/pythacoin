package pythacoin

import com.monovore.decline.{Command, Opts}
import scalus.cardano.address.Network

enum Cmd:
    case Blueprint, Start

object Cli:
    private val command = {
        val blueprintCommand = Opts.subcommand("blueprint", "Prints the contract blueprint JSON") {
            Opts(Cmd.Blueprint)
        }

        val startCommand = Opts.subcommand("start", "Start the server") {
            Opts(Cmd.Start)
        }

        Command(name = "pythacoin", header = "Pythacoin CDP Stablecoin")(
          blueprintCommand orElse startCommand
        )
    }

    private def blueprint(): Unit = {
        val pythPolicyId = "0000000000000000000000000000000000000000000000000000000000"
        val script = CdpContract(scalus.uplc.builtin.ByteString.fromHex(pythPolicyId))
        println(s"Script hash: ${script.script.scriptHash.toHex}")
        println(s"Script size: ${script.program.cborEncoded.length} bytes")
    }

    @main
    def start(): Unit = {
        val blockfrostApiKey = System.getenv("BLOCKFROST_API_KEY") match
            case null   => sys.error("BLOCKFROST_API_KEY environment variable is not set")
            case apiKey => apiKey
        val pythPolicyId = System.getenv("PYTH_POLICY_ID") match
            case null => sys.error("PYTH_POLICY_ID environment variable is not set")
            case id   => id
        val appCtx = AppCtx(Network.Testnet, blockfrostApiKey, pythPolicyId)
        println("Starting the Pythacoin server...")
        Server(appCtx).start()
    }

    @main def main(args: String*): Unit = {
        command.parse(args) match
            case Left(help) => println(help)
            case Right(cmd) =>
                cmd match
                    case Cmd.Blueprint => blueprint()
                    case Cmd.Start     => start()
    }
