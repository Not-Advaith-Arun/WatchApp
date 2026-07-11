import XCTest
@testable import WatchApp

final class SubscriptionGateTests:XCTestCase {
    func testStartsOnlyAfterEveryRequiredSubscriptionAndOnlyOnce() {
        var gate=SubscriptionGate()
        XCTAssertFalse(gate.consumeReady())
        gate.confirm(BLEContract.control.uuidString); XCTAssertFalse(gate.consumeReady())
        gate.confirm(BLEContract.data.uuidString); XCTAssertFalse(gate.consumeReady())
        gate.confirm(BLEContract.live.uuidString); XCTAssertTrue(gate.consumeReady())
        XCTAssertFalse(gate.consumeReady())
    }
    func testUnrelatedConfirmationDoesNotAdvanceAndResetAllowsReconnect() {
        var gate=SubscriptionGate(); gate.confirm(BLEContract.status.uuidString); XCTAssertFalse(gate.consumeReady())
        SubscriptionGate.required.forEach{gate.confirm($0)}; XCTAssertTrue(gate.consumeReady())
        gate.reset(); XCTAssertFalse(gate.started); XCTAssertTrue(gate.confirmed.isEmpty)
    }
}
