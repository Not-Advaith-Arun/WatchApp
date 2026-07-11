.PHONY: validate contracts android ios

validate: contracts
	git diff --check

contracts:
	sh tools/run-contract-tests.sh

android:
	cd wear && ./gradlew testDebugUnitTest assembleDebug

ios:
	cd ios && xcodegen generate
	cd ios && xcodebuild -project WatchApp.xcodeproj -scheme WatchApp -destination 'platform=iOS Simulator,name=iPhone 16' CODE_SIGNING_ALLOWED=NO test
