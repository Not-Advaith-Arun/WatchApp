import Foundation

enum FragmentError: Error, Equatable { case malformed, outOfOrder, oversized, expired }

struct FragmentAssembler: Sendable {
    static let headerBytes = 6, maximumFragments = 4096, timeout: TimeInterval = 30
    let maximumBytes: Int
    private(set) var messageID: UInt16?
    private var expectedCount = 0, nextIndex = 0
    private var bytes = Data()
    private var updatedAt: Date?

    init(maximumBytes: Int = 8 * 1024 * 1024) { self.maximumBytes = maximumBytes }
    mutating func reset() { messageID=nil; expectedCount=0; nextIndex=0; bytes.removeAll(keepingCapacity:false); updatedAt=nil }

    mutating func add(_ fragment: Data, now: Date = .now) throws -> Data? {
        if let updatedAt, now.timeIntervalSince(updatedAt) > Self.timeout { reset(); throw FragmentError.expired }
        guard fragment.count >= Self.headerBytes else { reset(); throw FragmentError.malformed }
        let id = UInt16(fragment[0]) << 8 | UInt16(fragment[1])
        let index = Int(fragment[2]) << 8 | Int(fragment[3])
        let count = Int(fragment[4]) << 8 | Int(fragment[5])
        guard (1...Self.maximumFragments).contains(count), index < count else { reset(); throw FragmentError.malformed }
        if index == 0 { reset(); messageID=id; expectedCount=count }
        guard messageID == id, expectedCount == count, index == nextIndex else { reset(); throw FragmentError.outOfOrder }
        let payload = fragment.dropFirst(Self.headerBytes)
        guard payload.count <= maximumBytes, bytes.count <= maximumBytes - payload.count else { reset(); throw FragmentError.oversized }
        bytes += payload; nextIndex += 1; updatedAt=now
        guard nextIndex == expectedCount else { return nil }
        let complete=bytes; reset(); return complete
    }
}
