package dropnext.dss.domain


/**
 * Outcome of attempting to persist the freshly obtained Shopify Admin token to the monolith
 * during OAuth installation. The view renders one of these as a success / error notice.
 */
sealed interface MonolithPersistOutcome {
  data class Persisted(val storeId: Long) : MonolithPersistOutcome
  data class Failed(val httpStatus: Int, val detail: String?) : MonolithPersistOutcome
}
