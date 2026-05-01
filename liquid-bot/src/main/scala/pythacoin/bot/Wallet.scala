package pythacoin.bot

import scalus.cardano.address.Address
import scalus.cardano.ledger.Transaction
import scalus.cardano.txbuilder.TransactionSigner
import scalus.uplc.builtin.ByteString

/** Loads the bot operator's signing material from configuration and signs
  * transactions. Holds raw key bytes only — no derivation, no HD paths.
  *
  * Both the payment signing key (32 bytes) and verification key (32 bytes) are
  * supplied as hex strings via env (`PYTHACOIN_BOT_KEY`, `PYTHACOIN_BOT_VKEY`).
  * The bot's payment address is supplied separately as bech32
  * (`PYTHACOIN_BOT_ADDR`) and parsed once at startup; key/address consistency is
  * the operator's responsibility.
  */
final class Wallet(
    val address: Address,
    signer: TransactionSigner
) {
    def sign(unsigned: Transaction): Transaction = signer.sign(unsigned)
}

object Wallet {

    /** Ed25519 keys are exactly 32 bytes. Validate at the env-var boundary so a
      * typo or truncated paste fails fast at startup instead of producing
      * confusing signing errors mid-flight.
      */
    private val Ed25519KeyHexLen = 64

    def fromConfig(cfg: BotConfig): Wallet = {
        val priv = decodeKey("PYTHACOIN_BOT_KEY", cfg.signingKeyHex)
        val pub = decodeKey("PYTHACOIN_BOT_VKEY", cfg.verificationKeyHex)
        val signer = TransactionSigner(Set(priv -> pub))
        val address = Address.fromBech32(cfg.walletAddrBech32)
        new Wallet(address, signer)
    }

    private def decodeKey(name: String, hex: String): ByteString = {
        val trimmed = hex.trim
        if trimmed.length != Ed25519KeyHexLen then
            sys.error(
              s"$name must be 32 bytes (64 hex chars), got ${trimmed.length} chars"
            )
        ByteString.fromHex(trimmed)
    }
}
