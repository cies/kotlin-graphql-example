package dropnext.dss.lib.shopify.graphql.fulfillment

import dropnext.dss.lib.shopify.legacyIdFromGid
import dropnext.graphql.generated.fulfillmentcreatewithlineitems.Fulfillment as CreatedFulfillment


/** Run stats for sync-shipments logging (not part of the HTTP response contract). */
data class SyncShipmentsRunStats(
  val canceledCount: Int,
  val createdCount: Int,
  val skippedLines: Int,
  val skippedShipments: Int,
)

/** Builds the structured `info`-level summary line for a sync-shipments run. */
fun formatSyncShipmentsLogLine(
  shop: String,
  orderId: Long,
  stats: SyncShipmentsRunStats,
  fulfillmentIds: List<Long>,
): String =
  buildString {
    append("sync-shipments orderId=$orderId shop=$shop ")
    append("canceled=${stats.canceledCount} created=${stats.createdCount} ")
    append("skippedLines=${stats.skippedLines} skippedShipments=${stats.skippedShipments} ")
    append("fulfillmentIds=$fulfillmentIds")
  }

/** Resolves a numeric fulfillment id from a create-mutation payload; null when unparseable. */
fun parseCreatedFulfillmentId(fulfillment: CreatedFulfillment): Long? =
  fulfillment.legacyResourceId.toLongOrNull()
    ?: legacyIdFromGid(fulfillment.id)
