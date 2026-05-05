package pythacoin.integration

import org.slf4j.LoggerFactory
import scalus.cardano.address.{Address, StakeAddress, StakePayload}
import scalus.cardano.ledger.*
import scalus.cardano.node.BlockchainProvider
import scalus.cardano.txbuilder.{TransactionSigner, TwoArgumentPlutusScriptWitness, txBuilder}
import scalus.cardano.txbuilder.ScriptSource
import scalus.cardano.onchain.plutus.prelude.List as PList
import scalus.uplc.PlutusV3
import scalus.uplc.builtin.{ByteString, Data}
import scalus.utils.await

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

/** Bootstraps the Pyth State UTxO on Yaci-DevKit using `PlutusV3.alwaysOk` as
  * BOTH the minting policy (so its hash is the test's `pythPolicyId`) and the
  * "Pyth withdraw script" referenced from the State datum.
  *
  * The bot's `PythClient.fetchPythState` looks up the State by asset
  * `(pythPolicyId, "Pyth State")` via Blockfrost, then reads the 4th datum
  * field as the withdraw script hash. So the datum we ship is:
  *
  *   `Constr 0 [(), [], [], <alwaysOkScriptHash bytes>]`
  *
  * The alwaysOk script is attached as a `scriptRef` on the same output so
  * Yaci's `/scripts/{hash}/cbor` endpoint serves it when the bot's
  * `fetchScript` resolves the withdraw script.
  */
object PythStateBootstrap {

    private val log = LoggerFactory.getLogger(PythStateBootstrap.getClass)

    /** ASCII-encoded asset name expected by the bot. */
    val PythStateAssetName: AssetName = AssetName(ByteString.fromString("Pyth State"))

    /** PlutusV3 always-true. Doubles as the test's "Pyth" policy and withdraw script. */
    val alwaysOkScript: PlutusScript = PlutusV3.alwaysOk.script

    /** Test's pretend Pyth policy ID — same hash as the alwaysOk script. */
    val pythPolicyId: ScriptHash = alwaysOkScript.scriptHash

    /** The hash that goes into the State datum's `withdraw_script` field. */
    val withdrawScriptHash: ScriptHash = alwaysOkScript.scriptHash

    /** Build, sign, and submit the bootstrap tx. Returns the resulting State
      * UTxO (input + output) so the caller can verify or reference it.
      */
    def bootstrap(
        provider: BlockchainProvider,
        signer: TransactionSigner,
        sourceAddr: Address,
        cardanoInfo: CardanoInfo
    ): TransactionInput = {
        given CardanoInfo = cardanoInfo

        val emptyList: Data = Data.List(PList.Nil)
        val datum: Data = Data.Constr(
          0,
          PList(
            Data.unit,
            emptyList,
            emptyList,
            Data.B(ByteString.unsafeFromArray(withdrawScriptHash.bytes))
          )
        )

        val stateOutput: TransactionOutput = TransactionOutput.Babbage(
          address = sourceAddr,
          value = Value.lovelace(2_000_000L) + Value.asset(pythPolicyId, PythStateAssetName, 1L),
          datumOption = Some(DatumOption.Inline(datum)),
          scriptRef = Some(ScriptRef(alwaysOkScript))
        )

        // The bot's `liquidateCdp` invokes the Pyth withdraw script via the
        // "withdraw zero" pattern (`withdrawRewards(stakeAddr, 0, witness)`).
        // That requires the script-stake credential to be *registered* on the
        // ledger first — without it the node rejects the tx with
        // `WithdrawalsNotInRewardsCERTS`. Real preprod has it pre-registered;
        // on a fresh Yaci-DevKit we must register it as part of bootstrap.
        val withdrawStakeAddress =
            StakeAddress(cardanoInfo.network, StakePayload.Script(withdrawScriptHash))
        val withdrawWitness = TwoArgumentPlutusScriptWitness(
          ScriptSource.PlutusScriptValue(alwaysOkScript),
          Data.unit
        )

        val builder = txBuilder
            .mint(PlutusV3.alwaysOk, Map(PythStateAssetName -> 1L), Data.unit)
            .output(stateOutput)
            .registerStake(withdrawStakeAddress, withdrawWitness)

        val completed = builder.complete(provider, sourceAddr).await(30.seconds)
        val signed = signer.sign(completed.transaction)
        val txHash = provider.submit(signed).await(30.seconds) match
            case Right(hash) => hash
            case Left(err)   => sys.error(s"PythStateBootstrap submit failed: $err")
        log.info(s"Bootstrap submitted: txid=${txHash}")

        // The State UTxO is the output that holds the NFT. Its index is the
        // first one we appended via .output(...) AFTER any change output.
        // We don't know the index without reconstructing balancing, so resolve
        // by querying the address for the asset.
        val stateInput = locateStateUtxo(provider, sourceAddr)
        log.info(s"Bootstrap Pyth State UTxO at $stateInput")
        stateInput
    }

    /** Wait for the bootstrapped UTxO to appear in the indexer, then return its input ref. */
    private def locateStateUtxo(
        provider: BlockchainProvider,
        addr: Address,
        maxAttempts: Int = 30
    ): TransactionInput = {
        var attempt = 0
        while attempt < maxAttempts do
            provider.findUtxos(addr).await(15.seconds) match
                case Right(utxos) =>
                    utxos.find { case (_, out) => out.value.hasAsset(pythPolicyId, PythStateAssetName) } match
                        case Some((input, _)) => return input
                        case None             => ()
                case Left(_) => ()
            Thread.sleep(2_000)
            attempt += 1
        sys.error(s"PythStateBootstrap: NFT did not appear at $addr within ${maxAttempts * 2}s")
    }
}
