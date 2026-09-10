package dropnext.dss.testutil.fake


/**
 * What every fake in this package is: a hand-written implementation of one of our interfaces that
 * records each call in a `<method>Calls` list and answers what the test configured.
 *
 * One shape, so a reader never has to open the fake to find out whether this method was recorded as
 * a counter, a `last…` field or a list. Assert on `createOrderCalls.size` for "how often" and on
 * `createOrderCalls.single()` for "with what"; both read the same list.
 */
interface RecordingFake {
  /** Forgets every recorded call and returns the stubbed answers to their defaults. */
  fun clear()
}
