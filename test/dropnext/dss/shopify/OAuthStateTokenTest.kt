package dropnext.dss.shopify

import dropnext.dss.lib.shopify.oauth.isValidSignedOAuthState
import dropnext.dss.lib.shopify.oauth.signedOAuthState
import java.time.Instant
import kotlin.test.Test

class OAuthStateTokenTest {

  private val secret = "client-secret-xyz"
  private val shop = "acme.myshopify.com"
  private val now = Instant.parse("2026-05-22T12:00:00Z")

  @Test
  fun `signed state validates with matching inputs`() {
    val state = signedOAuthState(shop, secret, now)
    assert(isValidSignedOAuthState(state, shop, secret, now))
  }

  @Test
  fun `validates when checked a few seconds after signing`() {
    val state = signedOAuthState(shop, secret, now)
    assert(isValidSignedOAuthState(state, shop, secret, now.plusSeconds(30)))
  }

  @Test
  fun `expires after 5 minutes`() {
    val state = signedOAuthState(shop, secret, now)
    assert(!isValidSignedOAuthState(state, shop, secret, now.plusSeconds(301)))
  }

  @Test
  fun `rejects mismatched shop`() {
    val state = signedOAuthState(shop, secret, now)
    assert(!isValidSignedOAuthState(state, "other.myshopify.com", secret, now))
  }

  @Test
  fun `rejects wrong secret`() {
    val state = signedOAuthState(shop, secret, now)
    assert(!isValidSignedOAuthState(state, shop, "different-secret", now))
  }

  @Test
  fun `rejects tampered shop portion`() {
    val state = signedOAuthState(shop, secret, now)
    val parts = state.split('|')
    val tampered = listOf("evil.myshopify.com", parts[1], parts[2], parts[3]).joinToString("|")
    assert(!isValidSignedOAuthState(tampered, "evil.myshopify.com", secret, now))
  }

  @Test
  fun `rejects malformed state with three segments`() {
    assert(!isValidSignedOAuthState("only|three|parts", shop, secret, now))
  }

  @Test
  fun `rejects malformed state with five segments`() {
    assert(!isValidSignedOAuthState("a|b|c|d|e", shop, secret, now))
  }

  @Test
  fun `rejects empty state`() {
    assert(!isValidSignedOAuthState("", shop, secret, now))
  }

  @Test
  fun `rejects non-numeric expiry`() {
    val state = "$shop|notANumber|nonce|signature"
    assert(!isValidSignedOAuthState(state, shop, secret, now))
  }

  @Test
  fun `two consecutive signs produce different nonces`() {
    val a = signedOAuthState(shop, secret, now)
    val b = signedOAuthState(shop, secret, now)
    assert(a != b)
  }
}
