import XCTest
@testable import WatchApp

final class FragmentAssemblerTests: XCTestCase {
    private func fragment(id:UInt16,index:UInt16,count:UInt16,payload:[UInt8])->Data { Data([UInt8(id>>8),UInt8(id),UInt8(index>>8),UInt8(index),UInt8(count>>8),UInt8(count)] + payload) }
    func testStrictOrderedAssembly() throws { var a=FragmentAssembler(maximumBytes:10); XCTAssertNil(try a.add(fragment(id:1,index:0,count:2,payload:[1,2]))); XCTAssertEqual(try a.add(fragment(id:1,index:1,count:2,payload:[3])),Data([1,2,3])); XCTAssertNil(a.messageID) }
    func testOutOfOrderAndDuplicateBehaviorMatchesWear() throws { var a=FragmentAssembler(); XCTAssertNil(try a.add(fragment(id:1,index:0,count:3,payload:[1]))); XCTAssertNil(try a.add(fragment(id:1,index:1,count:3,payload:[2]))); XCTAssertThrowsError(try a.add(fragment(id:1,index:1,count:3,payload:[2]))) { XCTAssertEqual($0 as? FragmentError,.outOfOrder) }; XCTAssertNil(try a.add(fragment(id:1,index:0,count:2,payload:[7]))); XCTAssertEqual(try a.add(fragment(id:1,index:1,count:2,payload:[8])),Data([7,8])) }
    func testInconsistentHeaderAndMalformedCountReject() throws { var a=FragmentAssembler(); XCTAssertNil(try a.add(fragment(id:1,index:0,count:2,payload:[1]))); XCTAssertThrowsError(try a.add(fragment(id:2,index:1,count:2,payload:[2]))); XCTAssertThrowsError(try a.add(fragment(id:1,index:0,count:0,payload:[]))) }
    func testOversizeRejectsBeforeAppend() throws { var a=FragmentAssembler(maximumBytes:2); XCTAssertThrowsError(try a.add(fragment(id:1,index:0,count:1,payload:[1,2,3]))) { XCTAssertEqual($0 as? FragmentError,.oversized) } }
    func testTimeoutResetsAssembly() throws { var a=FragmentAssembler(); let start=Date(timeIntervalSince1970:1); XCTAssertNil(try a.add(fragment(id:1,index:0,count:2,payload:[1]),now:start)); XCTAssertThrowsError(try a.add(fragment(id:1,index:1,count:2,payload:[2]),now:start.addingTimeInterval(31))) { XCTAssertEqual($0 as? FragmentError,.expired) } }
    func testShortFragmentRejects() { var a=FragmentAssembler(); XCTAssertThrowsError(try a.add(Data([0,1,2]))) }
}
